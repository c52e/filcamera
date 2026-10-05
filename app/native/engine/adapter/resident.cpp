#include "tracking.h"
#include "runtime.h"
#include "resident.h"
#include <vulkan/vulkan.h>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <cstdlib>
namespace audit {
namespace {
thread_local Session* active=nullptr;
void checked(VkResult result,const char* action) {
    if(result!=VK_SUCCESS) throw std::runtime_error(std::string(action)+": "+std::to_string(result));
}
}
struct Session::Impl {
    FcGpuContext* shared=nullptr;
    VkInstance instance{};VkPhysicalDevice physical{};VkDevice device{};VkQueue queue{};
    uint32_t family=0;VkPhysicalDeviceProperties properties{};
    VkShaderModule shader{};VkDescriptorSetLayout layout{};VkPipelineLayout pipelineLayout{};
    VkPipeline pipeline{};VkDescriptorPool descriptors{};VkDescriptorSet descriptorSet{};
    VkCommandPool commands{};VkCommandBuffer command{};VkFence fence{};
    uint32_t pipelines=0,buffers=0;
    struct Buffer {VkBuffer handle{};VkDeviceMemory memory{};void* mapped=nullptr;VkDeviceSize size=0,allocated=0;};
    Buffer image,constants,packed,saved;
    size_t activeBytes=0;
    VkBuffer externalSource=VK_NULL_HANDLE; // Borrowed from the spatial context until the next dispatch.
    VkBuffer blendSource=VK_NULL_HANDLE;
    void release(Buffer& b) noexcept {
        if(b.mapped) vkUnmapMemory(device,b.memory);
        if(b.handle) vkDestroyBuffer(device,b.handle,nullptr);
        if(b.memory) vkFreeMemory(device,b.memory,nullptr);
        b={};
    }
    ~Impl() {
        if(device) {
            // Also covers the exceptional path following a submitted command.
            vkDeviceWaitIdle(device);
            if(fence) vkDestroyFence(device,fence,nullptr);
            if(commands) vkDestroyCommandPool(device,commands,nullptr);
            if(descriptors) vkDestroyDescriptorPool(device,descriptors,nullptr);
            if(pipeline) vkDestroyPipeline(device,pipeline,nullptr);
            if(pipelineLayout) vkDestroyPipelineLayout(device,pipelineLayout,nullptr);
            if(layout) vkDestroyDescriptorSetLayout(device,layout,nullptr);
            if(shader) vkDestroyShaderModule(device,shader,nullptr);
            release(image);release(constants);release(packed);release(saved);

        }
        fc_gpu_release(shared);
    }
    void make(Buffer& b,VkDeviceSize bytes) {
        if(bytes==0 || bytes>properties.limits.maxStorageBufferRange) throw std::runtime_error("storage buffer limit");
        release(b);
        VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};ci.size=bytes;ci.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        ci.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        checked(vkCreateBuffer(device,&ci,nullptr,&b.handle),"create buffer");b.size=bytes;++buffers;
        VkMemoryRequirements req{};vkGetBufferMemoryRequirements(device,b.handle,&req);
        VkPhysicalDeviceMemoryProperties mp{};vkGetPhysicalDeviceMemoryProperties(physical,&mp);
        uint32_t type=mp.memoryTypeCount;
        for(uint32_t i=0;i<mp.memoryTypeCount;++i)
            if((req.memoryTypeBits&(1u<<i)) && (mp.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {type=i;break;}
        if(type==mp.memoryTypeCount) throw std::runtime_error("host coherent memory unavailable");
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;ai.memoryTypeIndex=type;
        checked(vkAllocateMemory(device,&ai,nullptr,&b.memory),"allocate buffer");b.allocated=req.size;
        checked(vkBindBufferMemory(device,b.handle,b.memory,0),"bind buffer");
        checked(vkMapMemory(device,b.memory,0,bytes,0,&b.mapped),"map buffer");
    }
    void initialize(const uint32_t* words,size_t bytes,const std::vector<float>& table) {
        if(!words || !bytes || bytes%4 || table.empty()) throw std::runtime_error("shader/table invalid");
        shared=fc_gpu_acquire();
        if(!shared) throw std::runtime_error(fc_gpu_error());
        instance=shared->instance;physical=shared->physical;device=shared->device;queue=shared->queue;
        family=shared->family;properties=shared->properties;
        if(properties.limits.maxComputeWorkGroupInvocations<64 || properties.limits.maxComputeWorkGroupSize[0]<64 || properties.limits.maxPushConstantsSize<24)
            throw std::runtime_error("compute limits unsupported");
        VkShaderModuleCreateInfo sci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sci.codeSize=bytes;sci.pCode=words;
        checked(vkCreateShaderModule(device,&sci,nullptr,&shader),"create shader");
        VkDescriptorSetLayoutBinding bindings[5]{};
        for(uint32_t i=0;i<5;++i) bindings[i]={i,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr};
        VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};lci.bindingCount=5;lci.pBindings=bindings;
        checked(vkCreateDescriptorSetLayout(device,&lci,nullptr,&layout),"create descriptor layout");
        VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT,0,24};
        VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};plci.setLayoutCount=1;plci.pSetLayouts=&layout;plci.pushConstantRangeCount=1;plci.pPushConstantRanges=&range;
        checked(vkCreatePipelineLayout(device,&plci,nullptr,&pipelineLayout),"create pipeline layout");
        VkComputePipelineCreateInfo pci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};pci.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pci.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;pci.stage.module=shader;pci.stage.pName="main";pci.layout=pipelineLayout;
        const uint32_t group=fc_gpu_workgroup();VkSpecializationMapEntry entry{0,0,4};VkSpecializationInfo spec{1,&entry,4,&group};pci.stage.pSpecializationInfo=&spec;
        checked(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&pci,nullptr,&pipeline),"create pipeline");++pipelines;
        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,5};
        VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dpci.maxSets=1;dpci.poolSizeCount=1;dpci.pPoolSizes=&poolSize;
        checked(vkCreateDescriptorPool(device,&dpci,nullptr,&descriptors),"create descriptor pool");
        VkDescriptorSetAllocateInfo dsai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};dsai.descriptorPool=descriptors;dsai.descriptorSetCount=1;dsai.pSetLayouts=&layout;
        checked(vkAllocateDescriptorSets(device,&dsai,&descriptorSet),"allocate descriptors");
        VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cpci.queueFamilyIndex=family;cpci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        checked(vkCreateCommandPool(device,&cpci,nullptr,&commands),"create command pool");
        VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};cbai.commandPool=commands;cbai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;cbai.commandBufferCount=1;
        checked(vkAllocateCommandBuffers(device,&cbai,&command),"allocate command buffer");
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};checked(vkCreateFence(device,&fci,nullptr,&fence),"create fence");
        make(constants,table.size()*sizeof(float));std::memcpy(constants.mapped,table.data(),constants.size);
    }
    void execute(const float* input,float* output,size_t count,float threshold,float limit,float power,const std::vector<float>& table,uint32_t operation,void* rgbaTarget,uint64_t inputImage) {
        const bool encode=rgbaTarget && (operation==0 || operation==11);
        const size_t packedBytes=encode?(count/3+1)*4:4;
        if(packed.size<packedBytes) make(packed,packedBytes);
        *static_cast<uint32_t*>(packed.mapped)=0;
        size_t pixels=count/3;const uint32_t group=fc_gpu_workgroup();uint32_t groups=uint32_t((pixels+group-1)/group);
        uint32_t gx=std::min(groups,properties.limits.maxComputeWorkGroupCount[0]);uint32_t gy=(groups+gx-1)/gx;
        if(gy>properties.limits.maxComputeWorkGroupCount[1]) throw std::runtime_error("dispatch group limit");
        if(image.size<count*4) {
            if(!input) throw std::runtime_error("resident image capacity lost");
            make(image,count*4);
        }
        if(std::getenv("FILMCAMERA_GPU_RESIDENT_FAIL")) throw std::runtime_error("injected failure after allocation");
        auto values=static_cast<float*>(image.mapped);
        activeBytes=count*4;
        const FcGpuImage* borrowed=nullptr;
        if(input && inputImage) {
            auto candidate=fc_gpu_image(inputImage,shared);
            if(!candidate) throw std::runtime_error(fc_gpu_error());
            if(candidate->mapped==input) borrowed=candidate;
        }
        if(borrowed && borrowed->bytes!=count*4) throw std::runtime_error("GPU input size mismatch");
        if(input && !borrowed) fc_gpu_count(FC_IMAGE_UPLOAD,count*4);
        if(input && !borrowed) for(size_t i=0;i<count;++i) {values[i]=input[i];if(!std::isfinite(values[i])) throw std::runtime_error("nonfinite input");}
        if(constants.size!=table.size()*sizeof(float)) make(constants,table.size()*sizeof(float));
        std::memcpy(constants.mapped,table.data(),constants.size);fc_gpu_count(FC_CONSTANT_UPLOAD,constants.size);
        // Pointwise operations may alias their own RGB triple. Scanner convolution
        // binds a separate source plane; its final original-image read is per pixel.
        const bool direct=borrowed && (fc_gpu_optimizations()&FC_BUFFER_HANDOFF);
        VkBuffer current=externalSource?externalSource:image.handle;
        VkDescriptorBufferInfo info[5]={{direct?borrowed->buffer:(operation==6 && saved.handle?saved.handle:current),0,count*4},{image.handle,0,image.size},{constants.handle,0,constants.size},{packed.handle,0,packedBytes},{blendSource?blendSource:current,0,count*4}};
        VkWriteDescriptorSet writes[5]{};
        for(uint32_t i=0;i<5;++i) {writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;writes[i].dstSet=descriptorSet;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&info[i];}
        vkUpdateDescriptorSets(device,5,writes,0,nullptr);
        checked(vkResetCommandBuffer(command,0),"reset command buffer");checked(vkResetFences(device,1,&fence),"reset fence");
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        checked(vkBeginCommandBuffer(command,&bi),"begin command buffer");
        if(borrowed && !direct) {
            VkMemoryBarrier copyBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};copyBarrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;copyBarrier.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&copyBarrier,0,nullptr,0,nullptr);
            fc_gpu_count(FC_DEVICE_COPY,count*4);
            VkBufferCopy region{0,0,count*4};vkCmdCopyBuffer(command,borrowed->buffer,image.handle,1,&region);
            copyBarrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;copyBarrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&copyBarrier,0,nullptr,0,nullptr);
        }
        VkMemoryBarrier before{VK_STRUCTURE_TYPE_MEMORY_BARRIER};before.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT;before.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&before,0,nullptr,0,nullptr);
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,1,&descriptorSet,0,nullptr);
        struct Push {uint32_t count;float threshold,limit,power;uint32_t operation,rgba8;} push{uint32_t(pixels),threshold,limit,power,operation,operation==6 && saved.handle?2u:(encode?1u:0u)};
        vkCmdPushConstants(command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(push),&push);
        vkCmdDispatch(command,gx,gy,1);
        VkMemoryBarrier after{VK_STRUCTURE_TYPE_MEMORY_BARRIER};after.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;after.dstAccessMask=(output || encode)?VK_ACCESS_HOST_READ_BIT:VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,(output || encode)?VK_PIPELINE_STAGE_HOST_BIT:VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&after,0,nullptr,0,nullptr);
        checked(vkEndCommandBuffer(command),"end command buffer");
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&command;
        checked(vkQueueSubmit(queue,1,&si,fence),"submit");
        checked(vkWaitForFences(device,1,&fence,VK_TRUE,UINT64_MAX),"wait fence");
        externalSource=VK_NULL_HANDLE;
        blendSource=VK_NULL_HANDLE;
        if(operation==6) release(saved);
        if(encode) {
            fc_gpu_count(FC_OUTPUT_READBACK,pixels*4);fc_gpu_count(FC_STATISTIC_READBACK,4);
            auto data=static_cast<uint32_t*>(packed.mapped);
            if(data[0]) throw std::runtime_error("nonfinite packed output");
            std::memcpy(rgbaTarget,data+1,pixels*4);
            return;
        }
        if(output) fc_gpu_count((operation==0 || operation==11)?FC_OUTPUT_READBACK:FC_INTERMEDIATE_READBACK,count*4);
        if(output) for(size_t i=0;i<count;++i) if(!std::isfinite(values[i])) throw std::runtime_error("nonfinite output");
        if(output) for(size_t i=0;i<count;++i) output[i]=values[i];
    }
};
Session::Session():previous(active){active=this;}
Session::~Session(){close();active=previous;}
void Session::close() noexcept {scannerWidth=0;scannerSigma=scannerAmount=0;spatialStarted=false;spatialResident=false;chainPhase=0;chainCount=0;uploaded=readback=0;impl.reset();std::vector<float>().swap(table);std::vector<float>().swap(auxiliary);}
void Session::reset() noexcept {close();}
Session& Session::current(){if(!active) throw std::runtime_error("GPU session scope missing");return *active;}
void Session::run(const float* input,float* output,size_t count,const uint32_t* shader,size_t shaderBytes,float threshold,float limit,float power,uint32_t operation) {
    try {
        if(spatialResident) {
            if(spatialStarted) input=nullptr;
            if((operation!=0)) output=nullptr;
        }
        if(operation>9 || !count || count%3 || count>48000000 || !std::isfinite(threshold) || !std::isfinite(limit) || !std::isfinite(power) || limit<=threshold || power<=0)
            throw std::runtime_error("invalid resident dispatch");
        if(chainPhase) {
            if(count!=chainCount || !((chainPhase==1 && operation==3 && input && !output) ||
                                     (chainPhase==2 && (operation==0) && !input && (output || rgbaTarget)) ||
                                     (chainPhase==11 && operation==2 && input && !output) ||
                                     (chainPhase==12 && operation==4 && !input && !output) ||
                                     (chainPhase==13 && operation==3 && !input && !output) ||
                                     (chainPhase==14 && (operation==0) && !input && (output || rgbaTarget))))
                throw std::runtime_error("invalid paper scan sequence");
        } else if(!spatialResident && (!input || (!output && !(rgbaTarget && (operation==0))))) throw std::runtime_error("resident dispatch requires a lease");
        if(!impl){impl=std::make_unique<Impl>();impl->initialize(shader,shaderBytes,table);}
        if(operation==0 && scannerSigma>0 && scannerAmount>0) {
            if(scannerWidth<=0 || (count/3)%scannerWidth || !std::isfinite(scannerSigma) || scannerSigma>3 || !std::isfinite(scannerAmount))
                throw std::runtime_error("Invalid scanner sharpening parameters");
            impl->execute(input,nullptr,count,threshold,limit,power,table,0,nullptr,inputImage);
            // Retain original linear RGB by exchanging ownership, without a device copy.
            std::swap(impl->image,impl->saved);
            if(impl->image.size<count*4) impl->make(impl->image,count*4);
            impl->externalSource=impl->saved.handle;
            impl->execute(nullptr,nullptr,count,float(scannerWidth),scannerSigma,scannerAmount,table,10,nullptr,0);
            // Original RGB is the output; horizontal blur reads the distinct vertical plane.
            std::swap(impl->image,impl->saved);
            impl->externalSource=impl->saved.handle;impl->blendSource=impl->image.handle;
            impl->execute(nullptr,output,count,float(scannerWidth),scannerSigma,scannerAmount,table,11,rgbaTarget,0);
            impl->release(impl->saved);
        } else impl->execute(input,output,count,threshold,limit,power,table,operation,rgbaTarget,inputImage);
        if(rgbaTarget && (operation==0)) rgbaReady=true;
        if(spatialResident) spatialStarted=true;
        uploaded=input && (!inputImage || fc_gpu_image(inputImage,nullptr)->mapped!=input)?count*sizeof(float):0;
        readback=rgbaTarget && (operation==0)?count/3*4:(output?count*sizeof(float):0);
        if(chainPhase) ++chainPhase;
    }catch(...){close();throw;}
}
void Session::beginPaperScan(size_t count) {
    if(chainPhase || !count || count%3 || count>48000000) {close();throw std::runtime_error("invalid paper scan lease");}
    chainPhase=1;chainCount=count;
}
void Session::endPaperScan() {
    if(chainPhase!=3) {close();throw std::runtime_error("incomplete paper scan lease");}
    chainPhase=0;chainCount=0;
}
void Session::beginFilmScan(size_t count) {
    if(chainPhase || !count || count%3 || count>48000000) {close();throw std::runtime_error("invalid film scan lease");}
    chainPhase=11;chainCount=count;
}
void Session::endFilmScan() {
    if(chainPhase!=15) {close();throw std::runtime_error("incomplete film scan lease");}
    chainPhase=0;chainCount=0;
}
uint64_t Session::allocationBytes() const{return impl?impl->image.allocated+impl->constants.allocated+impl->packed.allocated:0;}
uint32_t Session::pipelineCreates() const{return impl?impl->pipelines:0;}
uint32_t Session::bufferCreates() const{return impl?impl->buffers:0;}
}

namespace audit {
FcGpuBufferView Session::deviceView() const {if(!impl || !impl->image.handle) throw std::runtime_error("Missing resident image");return {impl->shared,impl->externalSource?impl->externalSource:impl->image.handle,impl->activeBytes};}
void Session::importDevice(FcGpuBufferView source,size_t bytes) {
    if(!impl || source.owner!=impl->shared || !source.buffer || source.element!=FC_GPU_F32 || bytes!=impl->activeBytes || bytes>source.bytes)
        throw std::runtime_error("Invalid resident buffer lease");
    if(fc_gpu_optimizations()&FC_BUFFER_HANDOFF) {impl->externalSource=source.buffer;return;}
    if(!fc_gpu_copy(impl->shared,source.buffer,impl->image.handle,bytes)) throw std::runtime_error("Resident image copy failed");
}
void Session::checkpoint() {
    if(!impl || !impl->image.handle) throw std::runtime_error("Missing checkpoint image");
    impl->make(impl->saved,impl->activeBytes);
    if(!fc_gpu_copy(impl->shared,deviceView().buffer,impl->saved.handle,impl->activeBytes)) throw std::runtime_error("Resident checkpoint failed");
}
void Session::importBlend(FcGpuBufferView source,FcGpuBufferView filtered,size_t bytes) {
    if(!impl || source.owner!=impl->shared || filtered.owner!=impl->shared || !source.buffer || !filtered.buffer || source.element!=FC_GPU_F32 || filtered.element!=FC_GPU_F32 || !bytes || bytes!=impl->activeBytes || bytes>source.bytes || bytes>filtered.bytes)
        throw std::runtime_error("Invalid deferred diffusion buffers");
    impl->externalSource=source.buffer;impl->blendSource=filtered.buffer;
}
}
