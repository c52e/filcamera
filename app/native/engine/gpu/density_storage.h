// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include "compute.h"
#include <memory>

namespace spk::gpu {
// Private synchronous resource. Never exposed through JNI. The context and both
// producers/consumers must outlive its last submitted use (Compute waits a fence).
class DensityStorage {
    struct Impl;
    std::unique_ptr<Impl> impl;
public:
    DensityStorage(FcGpuContext*,uint32_t width,uint32_t height,int mode);
    ~DensityStorage();
    DensityStorage(const DensityStorage&)=delete;
    bool matches(FcGpuContext*,uint32_t,uint32_t,int) const;
    FcGpuPlaneView view() const;
    std::shared_ptr<fc::Buffer> completedScratch() const;
    void layers(FcGpuBufferView source,const float* curves,size_t count,const void* push,size_t pushBytes);
    uint32_t sample(FcGpuBufferView output,const float* cells,size_t count,const void* push,size_t pushBytes);
};
}
