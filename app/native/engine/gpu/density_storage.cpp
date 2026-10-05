// SPDX-License-Identifier: GPL-3.0-only
#include "density_storage.h"
#include <array>
namespace {
const uint32_t layers0[]=
#include "density_layers0.inc"
;
const uint32_t layers1[]=
#include "density_layers1.inc"
;
#ifdef SPK_STORAGE_PROBES
const uint32_t layers2[]=
#include "density_layers2.inc"
;
#endif
const uint32_t sample0[]=
#include "density_sample0.inc"
;
const uint32_t sample1[]=
#include "density_sample1.inc"
;
#ifdef SPK_STORAGE_PROBES
const uint32_t sample2[]=
#include "density_sample2.inc"
;
#endif
}
namespace spk::gpu {
struct DensityStorage::Impl {
    FcGpuContext* g;uint32_t width,height;int mode;uint32_t group=fc_gpu_workgroup();
    VkImage image{};VkDeviceMemory memory{};VkImageView view{};VkSampler sampler{};
    bool initialized=false,ready=false;
    std::shared_ptr<fc::Buffer> density;
    std::unique_ptr<fc::Buffer> table,cells,counter,dummy;
    std::unique_ptr<fc::Compute> producer,consumer;
    Impl(FcGpuContext* context,uint32_t w,uint32_t h,int m):g(context),width(w),height(h),mode(m) {}
    ~Impl() {
        // Compute covers any pending exceptional submission before resources die.
        consumer.reset();producer.reset();
        if(sampler) vkDestroySampler(g->device,sampler,nullptr);
        if(view) vkDestroyImageView(g->device,view,nullptr);
        if(image) vkDestroyImage(g->device,image,nullptr);
        if(memory) fc_vkFreeMemory(g->device,memory,nullptr);
    }
    void initialize() {
        if(!width || !height || uint64_t(width)*height>16000000 || mode<0 || mode>2) throw std::runtime_error("Invalid density resource");
        if(mode==1 && !g->storage16) throw std::runtime_error("16-bit density storage unsupported");
        if(mode==2 && !g->r16Image) throw std::runtime_error("R16F density image unsupported");
        dummy=std::make_unique<fc::Buffer>(g,16,false);
        counter=std::make_unique<fc::Buffer>(g,16,true);
        if(mode!=2) density=std::make_shared<fc::Buffer>(g,uint64_t(width)*height*9*(mode==1?2:4),false);
        else {
#ifdef SPK_STORAGE_PROBES
            VkImageFormatProperties limits{};
            fc::check(vkGetPhysicalDeviceImageFormatProperties(g->physical,VK_FORMAT_R16_SFLOAT,VK_IMAGE_TYPE_2D,VK_IMAGE_TILING_OPTIMAL,VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,0,&limits),"density image support");
            if(width>limits.maxExtent.width || height>limits.maxExtent.height || limits.maxArrayLayers<9) throw std::runtime_error("Density image dimensions unsupported");
            VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ci.imageType=VK_IMAGE_TYPE_2D;ci.format=VK_FORMAT_R16_SFLOAT;ci.extent={width,height,1};ci.mipLevels=1;ci.arrayLayers=9;ci.samples=VK_SAMPLE_COUNT_1_BIT;ci.tiling=VK_IMAGE_TILING_OPTIMAL;ci.usage=VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
            fc::check(vkCreateImage(g->device,&ci,nullptr,&image),"create density image");
            VkMemoryRequirements req{};vkGetImageMemoryRequirements(g->device,image,&req);
            uint32_t type=UINT32_MAX;
            for(uint32_t i=0;i<g->memory.memoryTypeCount;++i) if((req.memoryTypeBits&(1u<<i)) && (g->memory.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {type=i;if(!(g->memory.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) break;}
            if(type==UINT32_MAX) throw std::runtime_error("Density image memory unsupported");
            VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;ai.memoryTypeIndex=type;
            fc::check(fc_vkAllocateMemory(g->device,&ai,nullptr,&memory),"allocate density image");
            fc::check(vkBindImageMemory(g->device,image,memory,0),"bind density image");
            VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=image;vi.viewType=VK_IMAGE_VIEW_TYPE_2D_ARRAY;vi.format=ci.format;vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,9};
            fc::check(vkCreateImageView(g->device,&vi,nullptr,&view),"density image view");
            VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};si.magFilter=si.minFilter=VK_FILTER_NEAREST;si.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;si.addressModeU=si.addressModeV=si.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            fc::check(vkCreateSampler(g->device,&si,nullptr,&sampler),"density sampler");
#else
            throw std::runtime_error("Image storage probe excluded from product");
#endif
        }
        const uint32_t* lp[]={layers0,layers1};const size_t lb[]={sizeof(layers0),sizeof(layers1)};
        const uint32_t* sp[]={sample0,sample1};const size_t sb[]={sizeof(sample0),sizeof(sample1)};
        const uint32_t *producerCode=lp[mode==2?0:mode],*consumerCode=sp[mode==2?0:mode];
        size_t producerBytes=lb[mode==2?0:mode],consumerBytes=sb[mode==2?0:mode];
#ifdef SPK_STORAGE_PROBES
        if(mode==2) {producerCode=layers2;producerBytes=sizeof(layers2);consumerCode=sample2;consumerBytes=sizeof(sample2);}
#endif
        std::vector<VkDescriptorType> pt(5,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),st(4,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        if(mode==2) {pt[4]=VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;st[0]=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;}
        producer=std::make_unique<fc::Compute>(g,producerCode,producerBytes,5,68,pt,group);
        consumer=std::make_unique<fc::Compute>(g,consumerCode,consumerBytes,4,36,st,group);
    }
    void validate(FcGpuBufferView v) const {
        if(v.owner!=g || !v.buffer || v.element!=FC_GPU_F32 || v.bytes<uint64_t(width)*height*12) throw std::runtime_error("Density frame view mismatch");
    }
    void upload(std::unique_ptr<fc::Buffer>& buffer,const float* data,size_t count) {
        if(!data || !count) throw std::runtime_error("Empty density parameters");
        const size_t bytes=count*sizeof(float);
        if(!buffer || buffer->bytes!=bytes) buffer=std::make_unique<fc::Buffer>(g,bytes,true);
        std::memcpy(buffer->mapped,data,bytes);fc_gpu_count(FC_CONSTANT_UPLOAD,bytes);
    }
};
DensityStorage::DensityStorage(FcGpuContext* g,uint32_t w,uint32_t h,int m):impl(std::make_unique<Impl>(g,w,h,m)) {impl->initialize();}
DensityStorage::~DensityStorage()=default;
std::shared_ptr<fc::Buffer> DensityStorage::completedScratch() const {
    if(impl->ready) throw std::runtime_error("Density plane is still live");
    return impl->density;
}
FcGpuPlaneView DensityStorage::view() const {
    const auto& s=*impl;
    return {s.g,s.mode==2?FC_GPU_IMAGE:FC_GPU_BUFFER,s.mode==0?FC_GPU_F32:FC_GPU_F16,
            s.width,s.height,9,uint64_t(s.width)*s.height*9*(s.mode==0?4:2),s.density?s.density->buffer:VK_NULL_HANDLE,s.view};
}
bool DensityStorage::matches(FcGpuContext* g,uint32_t w,uint32_t h,int m) const {return impl->g==g && impl->width==w && impl->height==h && impl->mode==m && impl->group==fc_gpu_workgroup();}
void DensityStorage::layers(FcGpuBufferView source,const float* curves,size_t count,const void* push,size_t pushBytes) {
    auto& s=*impl;s.validate(source);if(pushBytes!=68) throw std::runtime_error("Density producer push mismatch");s.ready=false;s.upload(s.table,curves,count);
    std::vector<VkDescriptorBufferInfo> b={{source.buffer,0,source.bytes},s.mode==0?s.density->descriptor():s.dummy->descriptor(),s.dummy->descriptor(),s.table->descriptor(),s.mode==1?s.density->descriptor():s.dummy->descriptor()};
    std::vector<VkDescriptorImageInfo> images(5);images[4]={VK_NULL_HANDLE,s.view,VK_IMAGE_LAYOUT_GENERAL};
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};barrier.image=s.image;barrier.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;barrier.newLayout=VK_IMAGE_LAYOUT_GENERAL;barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;barrier.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,9};barrier.dstAccessMask=VK_ACCESS_SHADER_WRITE_BIT;
    std::array<uint32_t,17> params{};std::memcpy(params.data(),push,pushBytes);
    const uint32_t groups=(s.width*s.height+s.group-1)/s.group;params[1]=std::min(groups,s.g->properties.limits.maxComputeWorkGroupCount[0]);params[2]=groups;
    s.producer->run(b,params.data(),uint32_t(pushBytes),s.width*s.height,images,s.mode==2&&!s.initialized?&barrier:nullptr);
    s.initialized=true;s.ready=true;
}
uint32_t DensityStorage::sample(FcGpuBufferView output,const float* cells,size_t count,const void* push,size_t pushBytes) {
    auto& s=*impl;s.validate(output);if(!s.ready || pushBytes!=36) throw std::runtime_error("Density sampler has no matching producer");s.upload(s.cells,cells,count);
    std::memset(s.counter->mapped,0,16);fc_gpu_count(FC_CONSTANT_UPLOAD,16);
    std::vector<VkDescriptorBufferInfo> b={s.mode==2?s.dummy->descriptor():s.density->descriptor(),{output.buffer,0,output.bytes},s.cells->descriptor(),s.counter->descriptor()};
    std::vector<VkDescriptorImageInfo> images(4);images[0]={s.sampler,s.view,VK_IMAGE_LAYOUT_GENERAL};
    std::array<uint32_t,9> params{};std::memcpy(params.data(),push,pushBytes);
    params[3]=std::min((s.width*s.height+s.group-1)/s.group,s.g->properties.limits.maxComputeWorkGroupCount[0]);
    s.consumer->run(b,params.data(),uint32_t(pushBytes),s.width*s.height,images);s.ready=false;
    // The caller may now reuse this allocation as later spatial scratch. The
    // next producer overwrites it only after those synchronous stages finish.
    fc_gpu_count(FC_STATISTIC_READBACK,4);return *static_cast<uint32_t*>(s.counter->mapped);
}
}
