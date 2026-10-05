#include "runtime.h"
#include "tracking.h"
#include <cstdlib>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <array>
#include <vector>
#include <stdexcept>
#include <string>
#include <cstring>
#include <chrono>
#include <algorithm>
#include "raw_spv.h"
#include "raw_relaxed_spv.h"

namespace {
using Clock=std::chrono::steady_clock;
double ms(Clock::time_point t) { return std::chrono::duration<double,std::milli>(Clock::now()-t).count(); }
void check(VkResult r,const char* name) { if(r!=VK_SUCCESS) throw std::runtime_error(std::string(name)+": Vulkan result "+std::to_string(r)); }
void require(bool ok,const char* message) { if(!ok) throw std::runtime_error(message); }
struct Buffer { VkBuffer buffer{};VkDeviceMemory memory{};void* mapped{};VkDeviceSize size{}; };
// Per-call resources have one owner and are released on the calling native worker, including errors.
struct Session {
    FcGpuContext* shared=nullptr;
    VkInstance instance{};VkPhysicalDevice physical{};VkDevice device{};VkQueue queue{};
    VkDescriptorSetLayout setLayout{};VkPipelineLayout layout{};VkShaderModule shader{};VkPipeline pipeline{};
    VkDescriptorPool descriptors{};VkDescriptorSet set{};VkCommandPool commands{};VkCommandBuffer command{};VkFence fence{};
    VkPhysicalDeviceProperties properties{};VkPhysicalDeviceMemoryProperties memoryProperties{};
    std::array<Buffer,7> buffers{};uint32_t family=0;uint64_t allocated=0;
    uint32_t groupX=16,groupY=16;
    ~Session() { close(); }
    void close() {
        if(device) {
            vkDeviceWaitIdle(device);
            if(fence) vkDestroyFence(device,fence,nullptr);
            if(commands) vkDestroyCommandPool(device,commands,nullptr);
            if(descriptors) vkDestroyDescriptorPool(device,descriptors,nullptr);
            if(pipeline) vkDestroyPipeline(device,pipeline,nullptr);
            if(shader) vkDestroyShaderModule(device,shader,nullptr);
            if(layout) vkDestroyPipelineLayout(device,layout,nullptr);
            if(setLayout) vkDestroyDescriptorSetLayout(device,setLayout,nullptr);
            for(auto& b:buffers) {
                if(b.mapped) vkUnmapMemory(device,b.memory);
                if(b.buffer) vkDestroyBuffer(device,b.buffer,nullptr);
                if(b.memory) vkFreeMemory(device,b.memory,nullptr);
            }
            device=VK_NULL_HANDLE;
        }
        fc_gpu_release(shared);shared=nullptr;instance=VK_NULL_HANDLE;
    }
    void init() {
        shared=fc_gpu_acquire();require(shared,fc_gpu_error());
        instance=shared->instance;physical=shared->physical;device=shared->device;queue=shared->queue;
        family=shared->family;properties=shared->properties;memoryProperties=shared->memory;
        require(properties.limits.maxPerStageDescriptorStorageBuffers>=7 && properties.limits.maxDescriptorSetStorageBuffers>=7,"RAW requires seven storage buffers");
        require(properties.limits.maxComputeWorkGroupInvocations>=256 && properties.limits.maxComputeWorkGroupSize[0]>=16 && properties.limits.maxComputeWorkGroupSize[1]>=16,"RAW workgroup unsupported");
        std::array<VkDescriptorSetLayoutBinding,7> bindings{};
        for(uint32_t i=0;i<7;i++) bindings[i]={i,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr};
        VkDescriptorSetLayoutCreateInfo si{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};si.bindingCount=7;si.pBindings=bindings.data();
        check(vkCreateDescriptorSetLayout(device,&si,nullptr,&setLayout),"descriptor layout");
        VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,4};
        VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};li.setLayoutCount=1;li.pSetLayouts=&setLayout;li.pushConstantRangeCount=1;li.pPushConstantRanges=&push;
        check(vkCreatePipelineLayout(device,&li,nullptr,&layout),"pipeline layout");
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sm.codeSize=sizeof(raw_spv);sm.pCode=raw_spv;
        if(fc_gpu_relaxed()) {sm.codeSize=sizeof(raw_relaxed_spv);sm.pCode=raw_relaxed_spv;}
        check(vkCreateShaderModule(device,&sm,nullptr,&shader),"shader module");
        VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};pi.layout=layout;
        pi.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};pi.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;pi.stage.module=shader;pi.stage.pName="main";
        groupY=fc_gpu_raw_group_y();groupX=fc_gpu_raw_group_x();
        require(groupY==8 || groupY==16,"RAW workgroup invalid");
        const uint32_t size[]={groupX,groupY};VkSpecializationMapEntry entries[]={{0,0,4},{1,4,4}};VkSpecializationInfo spec{2,entries,sizeof(size),size};pi.stage.pSpecializationInfo=&spec;
        check(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&pipeline),"compute pipeline");
        VkCommandPoolCreateInfo ci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};ci.queueFamilyIndex=family;ci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device,&ci,nullptr,&commands),"command pool");
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=commands;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;
        check(vkAllocateCommandBuffers(device,&ca,&command),"command buffer");
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};check(vkCreateFence(device,&fi,nullptr,&fence),"fence");
    }
    void buffer(uint32_t index,VkDeviceSize size) {
        require(size>0 && size<=properties.limits.maxStorageBufferRange,"RAW storage buffer exceeds device limit");
        auto& b=buffers[index];b.size=size;
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bi.size=size;bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateBuffer(device,&bi,nullptr,&b.buffer),"create buffer");
        VkMemoryRequirements requirements;vkGetBufferMemoryRequirements(device,b.buffer,&requirements);
        uint32_t type=UINT32_MAX;
        for(uint32_t i=0;i<memoryProperties.memoryTypeCount;i++) {
            auto flags=memoryProperties.memoryTypes[i].propertyFlags;
            if((requirements.memoryTypeBits&(1u<<i)) && (flags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                type=i;if(flags&VK_MEMORY_PROPERTY_HOST_CACHED_BIT) break;
            }
        }
        require(type!=UINT32_MAX,"No coherent host-visible Vulkan memory");
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=requirements.size;ai.memoryTypeIndex=type;
        check(vkAllocateMemory(device,&ai,nullptr,&b.memory),"allocate buffer");allocated+=requirements.size;
        check(vkBindBufferMemory(device,b.buffer,b.memory,0),"bind buffer");
        check(vkMapMemory(device,b.memory,0,size,0,&b.mapped),"map buffer");
    }
    void bind() {
        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,7};VkDescriptorPoolCreateInfo di{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};di.maxSets=1;di.poolSizeCount=1;di.pPoolSizes=&size;
        check(vkCreateDescriptorPool(device,&di,nullptr,&descriptors),"descriptor pool");
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};ai.descriptorPool=descriptors;ai.descriptorSetCount=1;ai.pSetLayouts=&setLayout;
        check(vkAllocateDescriptorSets(device,&ai,&set),"descriptor set");
        std::array<VkDescriptorBufferInfo,7> infos{};std::array<VkWriteDescriptorSet,7> writes{};
        for(uint32_t i=0;i<7;i++) {
            infos[i]={buffers[i].buffer,0,buffers[i].size};writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[i].dstSet=set;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&infos[i];
        }
        vkUpdateDescriptorSets(device,7,writes.data(),0,nullptr);
    }
    void begin() {
        check(vkResetCommandBuffer(command,0),"reset command");check(vkResetFences(device,1,&fence),"reset fence");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(command,&begin),"begin command");
    }
    void dispatch(uint32_t pass,uint32_t width,uint32_t height) {
        require((width+groupX-1)/groupX<=properties.limits.maxComputeWorkGroupCount[0] && (height+groupY-1)/groupY<=properties.limits.maxComputeWorkGroupCount[1],"RAW dispatch exceeds device limit");
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,nullptr);
        vkCmdPushConstants(command,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,4,&pass);vkCmdDispatch(command,(width+groupX-1)/groupX,(height+groupY-1)/groupY,1);
        if(pass==4 || pass==5) {
            barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        }
    }
    void submit() {
        check(vkEndCommandBuffer(command),"end command");
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&command;
        check(vkQueueSubmit(queue,1,&submit,fence),"submit compute");
        check(vkWaitForFences(device,1,&fence,VK_TRUE,10'000'000'000ULL),"wait compute");
    }
};
}

extern "C" JNIEXPORT jstring JNICALL Java_io_github_c52e_filcamera_rawvulkan_VulkanRawDeveloper_process(
    JNIEnv* env,jobject,jobject input,jintArray parameters,jobject output,jdoubleArray timing,jlongArray imageHandle) {
    try {
        const auto started=Clock::now();std::array<double,17> times{};
        const auto gpuStart=fc_gpu_counts();
        auto length=env->GetArrayLength(parameters);require(length>=40,"RAW parameters truncated");
        std::vector<uint32_t> p(length);env->GetIntArrayRegion(parameters,0,length,reinterpret_cast<jint*>(p.data()));
        if(env->ExceptionCheck()) return nullptr;
        require(p[0]>=2 && p[1]>=2 && uint64_t(p[0])*p[1]<=16'000'000 && p[2]>0 && p[3]>0 && uint64_t(p[2])*p[3]<=16'000'000,"Invalid RAW dimensions");
        auto data=env->GetDirectBufferAddress(input);auto bytes=env->GetDirectBufferCapacity(input);
        require(data && bytes>0 && bytes<=256*1024*1024 && bytes%4==0,"Invalid RAW upload");
        auto destination=output?env->GetDirectBufferAddress(output):nullptr;
        require(env->GetArrayLength(timing)==17,"RAW timing size mismatch");
        require(output?(destination && env->GetDirectBufferCapacity(output)==int64_t(p[2])*p[3]*12):(imageHandle && env->GetArrayLength(imageHandle)==1 && p[38]==1),"RAW output size mismatch");
        require(p[38]<=1 && (p[39]==0 || p[39]==90 || p[39]==180 || p[39]==270),"Invalid RAW output mode");
        Session s;s.init();
        auto identityAxis=[&](uint32_t n,uint32_t offsets,uint32_t weights) {
            if(uint64_t(offsets)+n>=p.size()) return false;
            for(uint32_t i=0;i<n;++i) {
                const uint64_t pos=uint64_t(weights)+uint64_t(p[offsets+i])*2;
                if(p[offsets+i+1]!=p[offsets+i]+1 || pos+1>=p.size() || p[pos+1]!=0x3f800000u) return false;
            }
            return true;
        };
        const bool fast=(fc_gpu_optimizations()&FC_RAW_BATCH_AND_NATIVE) && identityAxis(p[2],p[10],p[12]) && identityAxis(p[3],p[11],p[13]);
        const bool batch=fc_gpu_optimizations()&FC_RAW_BATCH_AND_NATIVE;
        s.buffer(0,bytes);s.buffer(1,p.size()*4);s.buffer(2,uint64_t(p[0])*p[1]*4);s.buffer(3,uint64_t(p[0])*p[1]*4);
        s.buffer(4,fast?4:uint64_t(p[0])*p[1]*12);s.buffer(5,fast?4:uint64_t(p[2])*p[1]*12);s.buffer(6,uint64_t(p[2])*p[3]*12);
        std::memcpy(s.buffers[0].mapped,data,bytes);std::memcpy(s.buffers[1].mapped,p.data(),p.size()*4);s.bind();times[0]=ms(started);
        auto batchStart=Clock::now();if(batch) s.begin();
        for(uint32_t stage=0;stage<(fast?3u:5u);++stage) {
            uint32_t pass=fast && stage==2?5:stage;
            auto begin=Clock::now();if(!batch) s.begin();
            s.dispatch(pass,pass<3?p[0]:p[2],pass<3?p[1]:(pass==3?p[1]:p[3]));
            if(!batch) s.submit();times[stage+1]=ms(begin);
        }
        if(batch) {s.submit();times[1]=ms(batchStart);times[2]=times[3]=times[4]=times[5]=0;}
        auto read=Clock::now();require(static_cast<const uint32_t*>(s.buffers[1].mapped)[15]==0,"Nonfinite Vulkan RAW output");
        uint64_t adopted=0;
        if(destination) std::memcpy(destination,s.buffers[6].mapped,size_t(p[2])*p[3]*12);
        else {
            auto& b=s.buffers[6];const bool rotated=p[39]==90 || p[39]==270;
            FcGpuImage image{b.buffer,b.memory,b.mapped,b.size,rotated?p[3]:p[2],rotated?p[2]:p[3],1};
            adopted=fc_gpu_adopt(s.shared,&image);require(adopted!=0,fc_gpu_error());b={};
        }
        times[12]=batch?times[1]:0;
        times[6]=ms(read);times[7]=double(s.allocated);
        auto cleanup=Clock::now();s.close();times[8]=ms(cleanup);times[9]=ms(started);
        const auto gpuEnd=fc_gpu_counts();
        times[10]=gpuEnd.dispatchCount-gpuStart.dispatchCount;times[11]=gpuEnd.submitCount-gpuStart.submitCount;
        times[13]=(gpuEnd.gpuNs-gpuStart.gpuNs)/1e6;times[14]=(gpuEnd.waitNs-gpuStart.waitNs)/1e6;
        times[15]=gpuEnd.waitCount-gpuStart.waitCount;times[16]=gpuEnd.timedSubmissionCount-gpuStart.timedSubmissionCount;
        env->SetDoubleArrayRegion(timing,0,17,times.data());
        if(adopted) {jlong id=static_cast<jlong>(adopted);env->SetLongArrayRegion(imageHandle,0,1,&id);if(env->ExceptionCheck()) {fc_gpu_drop(adopted);return nullptr;}}
        return env->NewStringUTF(s.properties.deviceName);
    } catch(const std::exception& e) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_rawvulkan_VulkanRawDeveloper_dropImage(JNIEnv* env,jclass,jlong id) {
    if(!fc_gpu_drop(uint64_t(id))) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"GPU image closed or off owner thread");
}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_c52e_filcamera_rawvulkan_NativeBuffer_allocateNative(JNIEnv* env,jclass,jint bytes) {
    if(bytes<=0 || bytes>16*1000*1000*12) {env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Invalid native buffer size");return 0;}
    void* pointer=std::malloc(size_t(bytes));
    if(!pointer) {env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),"Native image allocation failed");return 0;}
    return reinterpret_cast<jlong>(pointer);
}
extern "C" JNIEXPORT jobject JNICALL Java_io_github_c52e_filcamera_rawvulkan_NativeBuffer_view(JNIEnv* env,jclass,jlong pointer,jint bytes) {return env->NewDirectByteBuffer(reinterpret_cast<void*>(pointer),bytes);}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_rawvulkan_NativeBuffer_free(JNIEnv*,jclass,jlong pointer) {std::free(reinterpret_cast<void*>(pointer));}
