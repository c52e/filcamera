#pragma once
#include <vulkan/vulkan.h>
#include <stdint.h>

// Product ceiling; Vulkan device limits remain mandatory.
constexpr uint64_t FC_MAX_PIXELS = 60000000ULL;

// C ABI: shared by libraries that each statically link libc++. No STL ownership
// or exceptions cross this boundary. All operations belong to the calling worker.
struct FcGpuContext {
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    uint32_t timestampBits;
    bool storage16;
    bool r16Image;
    VkPhysicalDeviceProperties properties;
    VkPhysicalDeviceMemoryProperties memory;
};
struct FcGpuImage {
    VkBuffer buffer;
    VkDeviceMemory memory;
    void* mapped;
    uint64_t bytes;
    uint32_t width, height;
    uint32_t color; // 1 = scene-linear ProPhoto RGB, interleaved float32
};
// A synchronous, non-owning lease. The producer retains its allocation until
// the consumer's fence completes. Views never cross JNI or survive a context.
enum FcGpuElement {FC_GPU_F32,FC_GPU_F16};
struct FcGpuBufferView {FcGpuContext* owner;VkBuffer buffer;uint64_t bytes;FcGpuElement element=FC_GPU_F32;};
enum FcGpuResourceKind {FC_GPU_BUFFER,FC_GPU_IMAGE};
struct FcGpuPlaneView {
    FcGpuContext* owner;FcGpuResourceKind kind;FcGpuElement element;
    uint32_t width,height,layers;uint64_t bytes;
    VkBuffer buffer;VkImageView image;
};
struct FcGpuTransfers {
    uint64_t imageUpload,intermediateReadback,outputReadback,statisticsReadback,constantsUpload,deviceCopy;
    uint64_t waitNs,allocationCount,currentAllocationBytes,peakAllocationBytes;
    uint64_t dispatchCount,submitCount,waitCount;
    uint64_t gpuNs,timedSubmissionCount;
};
enum FcTransferKind {FC_IMAGE_UPLOAD,FC_INTERMEDIATE_READBACK,FC_OUTPUT_READBACK,FC_STATISTIC_READBACK,FC_CONSTANT_UPLOAD,FC_DEVICE_COPY};
// Test ablations use the same binary. Production never retries with a different mask.
enum FcGpuOptimization : uint32_t {
    FC_RAW_BATCH_AND_NATIVE=1, FC_BUFFER_HANDOFF=2, FC_GRAIN_SUBTRACT=4,
    FC_HALATION_LOG=8, FC_ACCUMULATOR_INIT=16, FC_PRINT_DIFFUSION=32,
    FC_HALATION_BATCH=128, FC_COMPLETED_RELEASE=256, FC_SPATIAL_MULTIRATE=512,
    FC_DIFFUSION_RADIAL_TABLE=1024, FC_DIFFUSION_MULTISCALE=2048
};
constexpr uint32_t FC_GPU_DEFAULT_MASK=FC_RAW_BATCH_AND_NATIVE|FC_BUFFER_HANDOFF|FC_GRAIN_SUBTRACT|
    FC_HALATION_LOG|FC_ACCUMULATOR_INIT|FC_PRINT_DIFFUSION|FC_HALATION_BATCH|FC_COMPLETED_RELEASE|FC_SPATIAL_MULTIRATE|FC_DIFFUSION_RADIAL_TABLE|FC_DIFFUSION_MULTISCALE;
extern "C" {
// Per-task failure classification; independent of allocation statistics/cached buffers.
void fc_gpu_clear_memory_error() noexcept;
bool fc_gpu_memory_failed() noexcept;
// Test selection is made only between closed render sessions: -1 = production.
int fc_gpu_storage() noexcept;
int fc_gpu_selected_storage() noexcept;
void fc_gpu_set_storage(int) noexcept;
bool fc_gpu_relaxed() noexcept;
void fc_gpu_set_relaxed(bool) noexcept;
uint32_t fc_gpu_workgroup() noexcept;
uint32_t fc_gpu_raw_group_y() noexcept;
uint32_t fc_gpu_raw_group_x() noexcept;
void fc_gpu_set_workgroup(uint32_t,uint32_t,uint32_t) noexcept;
uint32_t fc_gpu_optimizations() noexcept;
void fc_gpu_set_optimizations(uint32_t) noexcept;
void fc_vkCmdDispatch(VkCommandBuffer,uint32_t,uint32_t,uint32_t) noexcept;
VkResult fc_vkQueueSubmit(VkQueue,uint32_t,const VkSubmitInfo*,VkFence) noexcept;
VkResult fc_vkAllocateCommandBuffers(VkDevice,const VkCommandBufferAllocateInfo*,VkCommandBuffer*) noexcept;
void fc_vkFreeCommandBuffers(VkDevice,VkCommandPool,uint32_t,const VkCommandBuffer*) noexcept;
void fc_vkDestroyCommandPool(VkDevice,VkCommandPool,const VkAllocationCallbacks*) noexcept;
VkResult fc_vkBeginCommandBuffer(VkCommandBuffer,const VkCommandBufferBeginInfo*) noexcept;
VkResult fc_vkEndCommandBuffer(VkCommandBuffer) noexcept;
FcGpuContext* fc_gpu_acquire() noexcept;
void fc_gpu_release(FcGpuContext*) noexcept;
const char* fc_gpu_error() noexcept;
// Adopt a completed allocation. On failure ownership stays with the caller.
uint64_t fc_gpu_adopt(FcGpuContext*, const FcGpuImage*) noexcept;
const FcGpuImage* fc_gpu_image(uint64_t, FcGpuContext*) noexcept;
bool fc_gpu_drop(uint64_t) noexcept;
uint64_t fc_gpu_live_images() noexcept;
// Exact model's center-weighted/256 px AA metering. Only row sums cross to CPU.
bool fc_gpu_meter(uint64_t image,double* ev) noexcept;
bool fc_gpu_copy(FcGpuContext*,VkBuffer source,VkBuffer destination,uint64_t bytes) noexcept;
bool fc_gpu_plane(FcGpuContext*,VkBuffer source,uint64_t sourceBytes,VkBuffer target,uint64_t targetBytes,
    uint32_t count,uint32_t sourceStride,uint32_t sourceOffset,uint32_t targetStride,uint32_t targetOffset,uint32_t op,float amount) noexcept;
bool fc_gpu_diffusion_resample(FcGpuContext*,VkBuffer source,uint64_t sourceBytes,VkBuffer target,uint64_t targetBytes,
    VkBuffer original,uint64_t originalBytes,uint32_t width,uint32_t height,uint32_t factor,uint32_t offset,
    uint32_t channel,bool gather,const double* core) noexcept;
void fc_gpu_count(FcTransferKind,uint64_t bytes) noexcept;
void fc_gpu_reset_counts() noexcept;
FcGpuTransfers fc_gpu_counts() noexcept;
VkResult fc_vkAllocateMemory(VkDevice,const VkMemoryAllocateInfo*,const VkAllocationCallbacks*,VkDeviceMemory*) noexcept;
void fc_vkFreeMemory(VkDevice,VkDeviceMemory,const VkAllocationCallbacks*) noexcept;
VkResult fc_vkWaitForFences(VkDevice,uint32_t,const VkFence*,VkBool32,uint64_t) noexcept;
VkResult fc_vkDeviceWaitIdle(VkDevice) noexcept;
}
