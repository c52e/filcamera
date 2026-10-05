#include "runtime.h"
#include <atomic>
#include <memory>
#include <unordered_map>
#include <vector>
#include <string>
#include <stdexcept>
#include <chrono>
#include <algorithm>

namespace {
struct Owner {
    struct Timer {VkCommandPool pool{};VkQueryPool query{};bool pending=false;};
    FcGpuContext gpu{};
    uint32_t refs=0;
    std::unordered_map<uint64_t,FcGpuImage> images;
    std::unordered_map<VkDeviceMemory,uint64_t> allocations;
    uint64_t allocated=0;
    std::unordered_map<VkCommandBuffer,Timer> timers;
    ~Owner() {
        if(gpu.device) {
            vkDeviceWaitIdle(gpu.device);
            for(auto& entry:timers) if(entry.second.query) vkDestroyQueryPool(gpu.device,entry.second.query,nullptr);
            for(const auto& entry:images) {const auto& image=entry.second;if(image.mapped) vkUnmapMemory(gpu.device,image.memory);vkDestroyBuffer(gpu.device,image.buffer,nullptr);vkFreeMemory(gpu.device,image.memory,nullptr);}
            vkDestroyDevice(gpu.device,nullptr);
        }
        if(gpu.instance) vkDestroyInstance(gpu.instance,nullptr);
    }
};
thread_local std::unique_ptr<Owner> owner;
thread_local std::string error;
thread_local FcGpuTransfers transfers{};
thread_local uint32_t optimizations=FC_GPU_DEFAULT_MASK;
thread_local int storageMode=-1;
thread_local bool relaxedMath=true;
thread_local uint32_t groupSize=256,rawGroupX=16,rawGroupY=8;
std::atomic<uint64_t> next{1};
void check(VkResult r,const char* action) {
    if(r!=VK_SUCCESS) throw std::runtime_error(std::string(action)+": "+std::to_string(r));
}
}
extern "C" int fc_gpu_storage() noexcept {return storageMode;}
extern "C" int fc_gpu_selected_storage() noexcept {return storageMode==-1?(owner && owner->gpu.storage16?1:0):storageMode;}
extern "C" void fc_gpu_set_storage(int mode) noexcept {storageMode=mode;}
extern "C" bool fc_gpu_relaxed() noexcept {return relaxedMath;}
extern "C" void fc_gpu_set_relaxed(bool enabled) noexcept {relaxedMath=enabled;}
extern "C" uint32_t fc_gpu_workgroup() noexcept {
    // Capability fallback only; no startup benchmarking or runtime retry.
    if(owner && (groupSize>owner->gpu.properties.limits.maxComputeWorkGroupInvocations || groupSize>owner->gpu.properties.limits.maxComputeWorkGroupSize[0])) return 64;
    return groupSize;
}
extern "C" uint32_t fc_gpu_raw_group_y() noexcept {return rawGroupY;}
extern "C" uint32_t fc_gpu_raw_group_x() noexcept {return rawGroupX;}
extern "C" void fc_gpu_set_workgroup(uint32_t group,uint32_t rawX,uint32_t rawY) noexcept {if(group==64 || group==128 || group==256) groupSize=group;if(rawX==8 || rawX==16) rawGroupX=rawX;if(rawY==8 || rawY==16) rawGroupY=rawY;}
extern "C" FcGpuContext* fc_gpu_acquire() noexcept {
    try {
        if(!owner) {
            auto o=std::make_unique<Owner>();auto& g=o->gpu;
            VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="FILCamera";app.apiVersion=VK_API_VERSION_1_1;
            VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};info.pApplicationInfo=&app;
            check(vkCreateInstance(&info,nullptr,&g.instance),"create shared Vulkan instance");
            uint32_t n=0;check(vkEnumeratePhysicalDevices(g.instance,&n,nullptr),"enumerate devices");
            std::vector<VkPhysicalDevice> devices(n);check(vkEnumeratePhysicalDevices(g.instance,&n,devices.data()),"enumerate devices");
            for(auto d:devices) {
                uint32_t count=0;vkGetPhysicalDeviceQueueFamilyProperties(d,&count,nullptr);
                std::vector<VkQueueFamilyProperties> families(count);vkGetPhysicalDeviceQueueFamilyProperties(d,&count,families.data());
                for(uint32_t i=0;i<count;++i) if(families[i].queueCount && (families[i].queueFlags&VK_QUEUE_COMPUTE_BIT)) {g.physical=d;g.family=i;g.timestampBits=families[i].timestampValidBits;break;}
                if(g.physical) break;
            }
            if(!g.physical) throw std::runtime_error("No Vulkan compute queue");
            vkGetPhysicalDeviceProperties(g.physical,&g.properties);vkGetPhysicalDeviceMemoryProperties(g.physical,&g.memory);
            float priority=1;VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};q.queueFamilyIndex=g.family;q.queueCount=1;q.pQueuePriorities=&priority;
            VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};ci.queueCreateInfoCount=1;ci.pQueueCreateInfos=&q;
            VkPhysicalDevice16BitStorageFeatures storage{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
            VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};features.pNext=&storage;
            vkGetPhysicalDeviceFeatures2(g.physical,&features);
            g.storage16=storage.storageBuffer16BitAccess;
            storage.uniformAndStorageBuffer16BitAccess=VK_FALSE;storage.storagePushConstant16=VK_FALSE;storage.storageInputOutput16=VK_FALSE;
            ci.pNext=&storage;
            VkPhysicalDeviceFeatures enabled{};enabled.shaderStorageImageExtendedFormats=features.features.shaderStorageImageExtendedFormats;ci.pEnabledFeatures=&enabled;
            VkFormatProperties format{};vkGetPhysicalDeviceFormatProperties(g.physical,VK_FORMAT_R16_SFLOAT,&format);
            VkImageFormatProperties image{};
            g.r16Image=enabled.shaderStorageImageExtendedFormats &&
                (format.optimalTilingFeatures&(VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))==(VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) &&
                vkGetPhysicalDeviceImageFormatProperties(g.physical,VK_FORMAT_R16_SFLOAT,VK_IMAGE_TYPE_2D,VK_IMAGE_TILING_OPTIMAL,VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,0,&image)==VK_SUCCESS && image.maxArrayLayers>=9;
            check(vkCreateDevice(g.physical,&ci,nullptr,&g.device),"create shared Vulkan device");vkGetDeviceQueue(g.device,g.family,0,&g.queue);
            owner=std::move(o);
        }
        ++owner->refs;return &owner->gpu;
    } catch(const std::exception& e) {error=e.what();return nullptr;}
      catch(...) {error="Shared Vulkan allocation failed";return nullptr;}
}
extern "C" void fc_gpu_release(FcGpuContext* g) noexcept {
    if(!owner || g!=&owner->gpu || !owner->refs) return;
    if(--owner->refs==0) owner.reset();
}
extern "C" const char* fc_gpu_error() noexcept {return error.c_str();}
extern "C" uint64_t fc_gpu_adopt(FcGpuContext* g,const FcGpuImage* image) noexcept {
    try {
        if(!owner || g!=&owner->gpu || !image || !image->buffer || !image->memory || !image->width || !image->height || image->color!=1 ||
           uint64_t(image->width)*image->height>16000000 || image->bytes!=uint64_t(image->width)*image->height*12)
            throw std::runtime_error("Invalid GPU image adoption");
        const auto id=next.fetch_add(1);if(!id) throw std::runtime_error("GPU image identifier exhausted");
        owner->images.emplace(id,*image);++owner->refs;return id;
    } catch(const std::exception& e) {error=e.what();return 0;} catch(...) {error="GPU image registry allocation failed";return 0;}
}
extern "C" const FcGpuImage* fc_gpu_image(uint64_t id,FcGpuContext* g) noexcept {
    if(!owner || (g && g!=&owner->gpu)) {error="GPU image context mismatch";return nullptr;}
    const auto it=owner->images.find(id);
    if(it==owner->images.end()) {error="GPU image closed, invalid, or used off its owner thread";return nullptr;}
    return &it->second;
}
extern "C" bool fc_gpu_drop(uint64_t id) noexcept {
    if(!owner) return false;const auto it=owner->images.find(id);if(it==owner->images.end()) return false;
    auto g=&owner->gpu;const auto image=it->second;
    vkDeviceWaitIdle(g->device);
    if(image.mapped) vkUnmapMemory(g->device,image.memory);
    vkDestroyBuffer(g->device,image.buffer,nullptr);fc_vkFreeMemory(g->device,image.memory,nullptr);
    owner->images.erase(it);fc_gpu_release(g);return true;
}
extern "C" uint64_t fc_gpu_live_images() noexcept {return owner?owner->images.size():0;}
extern "C" void fc_gpu_reset_counts() noexcept {transfers={};transfers.peakAllocationBytes=owner?owner->allocated:0;}
extern "C" FcGpuTransfers fc_gpu_counts() noexcept {auto result=transfers;result.currentAllocationBytes=owner?owner->allocated:0;return result;}
extern "C" uint32_t fc_gpu_optimizations() noexcept {return optimizations;}
extern "C" void fc_gpu_set_optimizations(uint32_t value) noexcept {optimizations=value;}
namespace {
void collectTimers() noexcept {
    if(!owner) return;
    for(auto& entry:owner->timers) {
        auto& t=entry.second;if(!t.pending || !t.query) continue;
        uint64_t values[2]{};
        if(vkGetQueryPoolResults(owner->gpu.device,t.query,0,2,sizeof(values),values,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT)!=VK_SUCCESS) continue;
        const uint32_t bits=owner->gpu.timestampBits;
        const uint64_t mask=bits>=64?UINT64_MAX:((uint64_t(1)<<bits)-1);
        transfers.gpuNs+=uint64_t(double((values[1]-values[0])&mask)*owner->gpu.properties.limits.timestampPeriod);
        ++transfers.timedSubmissionCount;t.pending=false;
    }
}
}
extern "C" VkResult fc_vkAllocateCommandBuffers(VkDevice d,const VkCommandBufferAllocateInfo* info,VkCommandBuffer* commands) noexcept {
    auto result=vkAllocateCommandBuffers(d,info,commands);
    if(result!=VK_SUCCESS || !owner || owner->gpu.device!=d || !owner->gpu.timestampBits) return result;
    try {
        for(uint32_t i=0;i<info->commandBufferCount;++i) owner->timers.emplace(commands[i],Owner::Timer{info->commandPool});
    } catch(...) { /* Optional diagnostics must not break image processing. */ }
    return result;
}
extern "C" void fc_vkFreeCommandBuffers(VkDevice d,VkCommandPool pool,uint32_t n,const VkCommandBuffer* commands) noexcept {
    collectTimers();
    if(owner && owner->gpu.device==d) for(uint32_t i=0;i<n;++i) {
        auto it=owner->timers.find(commands[i]);if(it==owner->timers.end()) continue;
        if(it->second.query) vkDestroyQueryPool(d,it->second.query,nullptr);owner->timers.erase(it);
    }
    vkFreeCommandBuffers(d,pool,n,commands);
}
extern "C" void fc_vkDestroyCommandPool(VkDevice d,VkCommandPool pool,const VkAllocationCallbacks* callbacks) noexcept {
    collectTimers();
    if(owner && owner->gpu.device==d) for(auto it=owner->timers.begin();it!=owner->timers.end();) {
        if(it->second.pool==pool) {if(it->second.query) vkDestroyQueryPool(d,it->second.query,nullptr);it=owner->timers.erase(it);} else ++it;
    }
    vkDestroyCommandPool(d,pool,callbacks);
}
extern "C" VkResult fc_vkBeginCommandBuffer(VkCommandBuffer command,const VkCommandBufferBeginInfo* info) noexcept {
    auto result=vkBeginCommandBuffer(command,info);if(result!=VK_SUCCESS || !owner) return result;
    auto it=owner->timers.find(command);if(it==owner->timers.end()) return result;
    auto& t=it->second;
    if(!t.query) {VkQueryPoolCreateInfo ci{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};ci.queryType=VK_QUERY_TYPE_TIMESTAMP;ci.queryCount=2;if(vkCreateQueryPool(owner->gpu.device,&ci,nullptr,&t.query)!=VK_SUCCESS) return result;}
    vkCmdResetQueryPool(command,t.query,0,2);
    vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,t.query,0);
    t.pending=false;return result;
}
extern "C" VkResult fc_vkEndCommandBuffer(VkCommandBuffer command) noexcept {
    if(owner) {auto it=owner->timers.find(command);if(it!=owner->timers.end() && it->second.query) vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,it->second.query,1);}
    return vkEndCommandBuffer(command);
}
extern "C" void fc_vkCmdDispatch(VkCommandBuffer cmd,uint32_t x,uint32_t y,uint32_t z) noexcept {
    ++transfers.dispatchCount;vkCmdDispatch(cmd,x,y,z);
}
extern "C" VkResult fc_vkQueueSubmit(VkQueue q,uint32_t n,const VkSubmitInfo* info,VkFence fence) noexcept {
    ++transfers.submitCount;auto result=vkQueueSubmit(q,n,info,fence);
    if(result==VK_SUCCESS && owner) for(uint32_t i=0;i<n;++i) for(uint32_t j=0;j<info[i].commandBufferCount;++j) {
        auto it=owner->timers.find(info[i].pCommandBuffers[j]);if(it!=owner->timers.end()) it->second.pending=true;
    }
    return result;
}
extern "C" VkResult fc_vkAllocateMemory(VkDevice d,const VkMemoryAllocateInfo* info,const VkAllocationCallbacks* callbacks,VkDeviceMemory* memory) noexcept {
    const auto result=vkAllocateMemory(d,info,callbacks,memory);if(result!=VK_SUCCESS) return result;
    if(owner && owner->gpu.device==d) {
        try {owner->allocations.emplace(*memory,info->allocationSize);owner->allocated+=info->allocationSize;++transfers.allocationCount;transfers.peakAllocationBytes=std::max(transfers.peakAllocationBytes,owner->allocated);}
        catch(...) {vkFreeMemory(d,*memory,callbacks);*memory=VK_NULL_HANDLE;return VK_ERROR_OUT_OF_HOST_MEMORY;}
    }
    return result;
}
extern "C" void fc_vkFreeMemory(VkDevice d,VkDeviceMemory memory,const VkAllocationCallbacks* callbacks) noexcept {
    if(owner && owner->gpu.device==d) {const auto it=owner->allocations.find(memory);if(it!=owner->allocations.end()) {owner->allocated-=it->second;owner->allocations.erase(it);}}
    vkFreeMemory(d,memory,callbacks);
}
extern "C" VkResult fc_vkWaitForFences(VkDevice d,uint32_t n,const VkFence* f,VkBool32 all,uint64_t timeout) noexcept {
    ++transfers.waitCount;
    const auto start=std::chrono::steady_clock::now();const auto result=vkWaitForFences(d,n,f,all,timeout);
    transfers.waitNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();collectTimers();return result;
}
extern "C" VkResult fc_vkDeviceWaitIdle(VkDevice d) noexcept {
    ++transfers.waitCount;
    const auto start=std::chrono::steady_clock::now();const auto result=vkDeviceWaitIdle(d);
    transfers.waitNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();collectTimers();return result;
}
extern "C" void fc_gpu_count(FcTransferKind kind,uint64_t bytes) noexcept {
    switch(kind) {
        case FC_IMAGE_UPLOAD:transfers.imageUpload+=bytes;break;
        case FC_INTERMEDIATE_READBACK:transfers.intermediateReadback+=bytes;break;
        case FC_OUTPUT_READBACK:transfers.outputReadback+=bytes;break;
        case FC_STATISTIC_READBACK:transfers.statisticsReadback+=bytes;break;
        case FC_CONSTANT_UPLOAD:transfers.constantsUpload+=bytes;break;
        case FC_DEVICE_COPY:transfers.deviceCopy+=bytes;break;
    }
}
extern "C" bool fc_gpu_copy(FcGpuContext* g,VkBuffer src,VkBuffer dst,uint64_t bytes) noexcept {
    VkCommandPool pool{};VkFence fence{};bool success=false;
    try {
        if(!owner || g!=&owner->gpu || !src || !dst || !bytes) throw std::runtime_error("Invalid GPU copy");
        VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pi.queueFamilyIndex=g->family;check(vkCreateCommandPool(g->device,&pi,nullptr,&pool),"copy command pool");
        VkCommandBuffer cmd{};VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ai.commandPool=pool;ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ai.commandBufferCount=1;check(fc_vkAllocateCommandBuffers(g->device,&ai,&cmd),"copy command");
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};check(vkCreateFence(g->device,&fi,nullptr,&fence),"copy fence");
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;check(fc_vkBeginCommandBuffer(cmd,&bi),"begin copy");
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_HOST_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        VkBufferCopy region{0,0,bytes};vkCmdCopyBuffer(cmd,src,dst,1,&region);
        barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        check(fc_vkEndCommandBuffer(cmd),"end copy");VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&cmd;check(fc_vkQueueSubmit(g->queue,1,&si,fence),"submit copy");check(fc_vkWaitForFences(g->device,1,&fence,VK_TRUE,UINT64_MAX),"wait copy");success=true;
    } catch(const std::exception& e) {error=e.what();} catch(...) {error="GPU copy failed";}
    if(!success && g && g->device) fc_vkDeviceWaitIdle(g->device);
    if(fence) vkDestroyFence(g->device,fence,nullptr);if(pool) fc_vkDestroyCommandPool(g->device,pool,nullptr);if(success) fc_gpu_count(FC_DEVICE_COPY,bytes);return success;
}
