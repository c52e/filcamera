#include "film_spatial.h"
#include "gpu/vulkan_compute.h"
#include "model/grain.h"
#include "kernels/parallel.h"
#include "resident_spatial.h"
#include <cmath>
#include <stdexcept>
namespace spk {
thread_local uint32_t lab_grain_seed=0;
thread_local float lab_grain_strength=1;
std::atomic<const char*> lab_stage{""};
void lab_grain(const Profile& film,const FilmingParams& params,const std::vector<float>& ndc,int w,int h,float* density) {
    const int n=film.n_density_pts,npix=w*h;
    if(!params.grain.sublayers_active || film.density_curves_layers.size()!=size_t(n)*9)
        throw std::runtime_error("GPU grain requires the selected profile's sublayers");
    auto& session=audit::Session::current();
    const bool resident=session.spatialResident;
    const float strength=lab_grain_strength;
    const int components=npix*3;
    std::vector<float> cleanDensity;
    if(strength!=1) {
        if(resident) session.checkpoint();
        else cleanDensity.assign(density,density+components);
    }
    std::unique_ptr<ResidentSpatial> frame;
    if(resident) frame=std::make_unique<ResidentSpatial>(w,h);
    std::vector<float> layers(resident?0:size_t(npix)*9);
    gpu::FilmingStageDiagnostics d{};
    if(!gpu::grain_layers(density,npix,w,h,ndc.data(),film.density_curves_layers.data(),n,film.is_positive(),layers.data(),&d))
        throw std::runtime_error(std::string("GPU grain layers failed: ")+(d.reason?d.reason:"unknown"));
    double maximum[9]{};
    for(int cell=0;cell<9;++cell) {
        maximum[cell]=-1;
        for(int k=0;k<n;++k) {float v=film.density_curves_layers[k*9+cell];if(std::isfinite(v) && v>maximum[cell]) maximum[cell]=v;}
        if(maximum[cell]<=0) throw std::runtime_error("Invalid grain profile cell");
    }
    GrainParams grain=params.grain;grain.allow_gpu_sampler=true;grain.seed_offset=lab_grain_seed;
    apply_grain_to_density_layers(layers.data(),npix,w,h,maximum,params.pixel_size_um,grain,density);
    // Blend in film density after the original grain blur so both the texture
    // and its softening fade toward the clean negative at low strength.
    if(strength!=1) {
        if(resident) session.blendCheckpoint(gpu::lab_frame_result(),strength);
        else parallel_for(0,components,[&](int begin,int end) {
            for(int i=begin;i<end;++i)
                density[i]=(1-strength)*cleanDensity[i]+strength*density[i];
        });
    }
    if(frame) frame->finish();
}
}
