#pragma once
#include <vulkan/vulkan.h>
#include <algorithm>
#include <numeric>
#include <stdexcept>

namespace fc {
// Keep RGB stores well below the observed 2^24-pixel addressing boundary.
// Chunk starts must also satisfy every sliced binding's descriptor alignment.
inline uint32_t buffer_chunk_size(const VkPhysicalDeviceLimits& limits,
                                  uint32_t bytesPerElement, uint32_t maxElements = 4u * 1024u * 1024u) {
    const uint64_t alignment = std::max<VkDeviceSize>(1, limits.minStorageBufferOffsetAlignment);
    const uint64_t step = alignment / std::gcd(alignment, uint64_t(bytesPerElement));
    const uint64_t count = maxElements / step * step;
    if (!count) throw std::runtime_error("GPU chunk alignment unsupported");
    return uint32_t(count);
}
}
