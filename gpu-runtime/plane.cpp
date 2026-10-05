#include "compute.h"
#include "plane_spv.h"
extern "C" bool fc_gpu_plane(FcGpuContext* g,VkBuffer source,uint64_t sourceBytes,VkBuffer target,uint64_t targetBytes,
    uint32_t count,uint32_t sourceStride,uint32_t sourceOffset,uint32_t targetStride,uint32_t targetOffset,uint32_t op,float amount) noexcept {
    try {
        if(!g || !source || !target || !count || op>2 || uint64_t(count-1)*sourceStride+sourceOffset>=sourceBytes/4 || uint64_t(count-1)*targetStride+targetOffset>=targetBytes/4)
            throw std::runtime_error("Invalid GPU plane operation");
        fc::Compute compute(g,plane_spv,sizeof(plane_spv),2,28);
        struct Push {uint32_t count,ss,so,ds,dso,op;float amount;} push{count,sourceStride,sourceOffset,targetStride,targetOffset,op,amount};
        compute.run({{source,0,sourceBytes},{target,0,targetBytes}},&push,sizeof(push),count);fc_gpu_count(FC_DEVICE_COPY,uint64_t(count)*4);return true;
    }catch(...) {return false;}
}
