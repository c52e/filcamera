#include "compute.h"
#include "meter_spv.h"
#include <cmath>
#include <algorithm>

extern "C" bool fc_gpu_meter(uint64_t imageId,double* ev) noexcept {
    auto* g=fc_gpu_acquire();if(!g) return false;
    try {
        const auto* image=fc_gpu_image(imageId,g);if(!image || !ev) throw std::runtime_error("Invalid metering image");
        const int w=image->width,h=image->height,longest=std::max(w,h);
        const double scale=std::min(1.0,256.0/longest);
        const int ow=std::max(1,int(std::nearbyint(w*scale))),oh=std::max(1,int(std::nearbyint(h*scale)));
        std::vector<float> parameters;
        auto kernel=[&](double sigma) {
            if(sigma<=0) {parameters.push_back(1);return 0;}
            const int radius=int(4*sigma+.5);std::vector<double> values(2*radius+1);double sum=0;
            for(int i=-radius;i<=radius;++i) {values[i+radius]=std::exp(-.5*(double(i)/sigma)*(double(i)/sigma));sum+=values[i+radius];}
            for(double v:values) parameters.push_back(float(v/sum));return radius;
        };
        const int rx=kernel(std::max(0.0,(double(w)/ow-1)/2)),ry=kernel(std::max(0.0,(double(h)/oh-1)/2));
        for(int x=0;x<ow;++x) parameters.push_back(float(std::nearbyint((x+.5)/(double(ow)/w)-.5)));
        for(int y=0;y<oh;++y) parameters.push_back(float(std::nearbyint((y+.5)/(double(oh)/h)-.5)));
        std::vector<double> weights(size_t(ow)*oh);double weightSum=0;
        for(int y=0;y<oh;++y) for(int x=0;x<ow;++x) {const double xx=(double(x)/ow-.5)*ow/std::max(ow,oh),yy=(double(y)/oh-.5)*oh/std::max(ow,oh);const double v=std::exp(-(xx*xx+yy*yy)/(.08));weights[size_t(y)*ow+x]=v;weightSum+=v;}
        for(double v:weights) parameters.push_back(float(v/weightSum));
        {
            fc::Buffer horizontal(g,uint64_t(ow)*h*12,false),constants(g,parameters.size()*4,true),result(g,oh*4,true);
            std::memcpy(constants.mapped,parameters.data(),parameters.size()*4);
            fc_gpu_count(FC_CONSTANT_UPLOAD,parameters.size()*4);
            fc::Compute compute(g,meter_spv,sizeof(meter_spv),4,28);
            struct Push {uint32_t w,h,ow,oh;int32_t rx,ry;uint32_t op;} push{uint32_t(w),uint32_t(h),uint32_t(ow),uint32_t(oh),rx,ry,0};
            const std::vector<VkDescriptorBufferInfo> bindings{{image->buffer,0,image->bytes},horizontal.descriptor(),constants.descriptor(),result.descriptor()};
            compute.run(bindings,&push,sizeof(push),ow*h*3);push.op=1;compute.run(bindings,&push,sizeof(push),oh);
            double value=0;for(int i=0;i<oh;++i) value+=static_cast<float*>(result.mapped)[i];
            fc_gpu_count(FC_STATISTIC_READBACK,oh*4);
            *ev=-std::log2(value/.184);if(std::isinf(*ev)) *ev=0;
            if(!std::isfinite(*ev)) throw std::runtime_error("Nonfinite GPU exposure statistic");
        }
        fc_gpu_release(g);return true;
    } catch(...) {fc_gpu_release(g);return false;}
}
