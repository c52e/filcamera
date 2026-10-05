#pragma once
#include "resident.h"
#include "gpu/vulkan_compute.h"
namespace spk::gpu {void lab_frame_open(FcGpuBufferView,int,int);FcGpuBufferView lab_frame_result();FcGpuBufferView lab_frame_filtered();}
namespace spk {
struct ResidentSpatial {
    int w,h;
    ResidentSpatial(int width,int height):w(width),h(height){gpu::lab_frame_open(audit::Session::current().deviceView(),w,h);}
    ~ResidentSpatial(){gpu::frame_discard();}
    void finish(){audit::Session::current().importDevice(gpu::lab_frame_result(),size_t(w)*h*12);}
};
}
