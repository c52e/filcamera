#include "compute.h"
#include "diffusion_resample_spv.h"
extern "C" bool fc_gpu_diffusion_resample(FcGpuContext* g,VkBuffer source,uint64_t sourceBytes,
    VkBuffer target,uint64_t targetBytes,VkBuffer original,uint64_t originalBytes,
    uint32_t w,uint32_t h,uint32_t factor,uint32_t offset,uint32_t channel,bool gather,const double* core) noexcept {
    try {
        if(!g || !source || !target || !original || (factor!=2 && factor!=4) || w<16 || h<16 ||
           w%factor || h%factor || channel>2 || (!gather && !core)) return false;
        const uint64_t pixels=uint64_t(w)*h,small=pixels/(factor*factor);
        if(pixels*12>originalBytes || (gather ? pixels*12>sourceBytes || (offset+small)*4>targetBytes :
            (offset+small)*4>sourceBytes || pixels*12>targetBytes)) return false;
        fc::Buffer kernel(g,81*4,true);
        auto* values=static_cast<float*>(kernel.mapped);
        for(int i=0;i<81;++i)values[i]=core?float(core[i]):0.0f;
        fc_gpu_count(FC_CONSTANT_UPLOAD,81*4);
        fc::Compute compute(g,diffusion_resample_spv,sizeof(diffusion_resample_spv),4,24);
        struct Push {uint32_t w,h,factor,offset,ch,gather;} push{w,h,factor,offset,channel,uint32_t(gather)};
        compute.run({{source,0,sourceBytes},{target,0,targetBytes},{original,0,originalBytes},{kernel.buffer,0,kernel.bytes}},
                    &push,sizeof(push),uint32_t(gather?small:pixels));
        return true;
    } catch(...) {return false;}
}
