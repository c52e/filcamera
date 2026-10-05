#include "film_spatial.h"
#include "gpu/vulkan_compute.h"
#include "model/grain.h"
#include "resident_spatial.h"
#include <cmath>
#include <stdexcept>
namespace spk {
thread_local uint32_t lab_grain_seed=0;
std::atomic<const char*> lab_stage{""};
void lab_grain(const Profile& film,const FilmingParams& params,const std::vector<float>& ndc,int w,int h,float* density) {
    const int n=film.n_density_pts,npix=w*h;
    if(!params.grain.sublayers_active || film.density_curves_layers.size()!=size_t(n)*9)
        throw std::runtime_error("GPU grain requires the selected profile's sublayers");
    const bool resident=audit::Session::current().spatialResident;
    std::unique_ptr<ResidentSpatial> frame;
    if(resident) frame=std::make_unique<ResidentSpatial>(w,h);
    std::vector<float> layers(resident?0:size_t(npix)*9);
    gpu::FilmingStageDiagnostics d{};
    if(!gpu::grain_layers(density,npix,w,h,ndc.data(),film.density_curves_layers.data(),n,film.is_positive(),layers.data(),&d))
        throw std::runtime_error("GPU grain layers failed");
    double maximum[9]{};
    for(int cell=0;cell<9;++cell) {
        maximum[cell]=-1;
        for(int k=0;k<n;++k) {float v=film.density_curves_layers[k*9+cell];if(std::isfinite(v) && v>maximum[cell]) maximum[cell]=v;}
        if(maximum[cell]<=0) throw std::runtime_error("Invalid grain profile cell");
    }
    GrainParams grain=params.grain;grain.allow_gpu_sampler=true;grain.seed_offset=lab_grain_seed;
    apply_grain_to_density_layers(layers.data(),npix,w,h,maximum,params.pixel_size_um,grain,density);
    if(frame) frame->finish();
}
}
