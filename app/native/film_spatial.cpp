#include "film_spatial.h"
#include "gpu/vulkan_compute.h"
#include "model/grain.h"
#include "kernels/parallel.h"
#include "resident_spatial.h"
#include <cmath>
#include <locale>
#include <sstream>
#include <stdexcept>
namespace spk {
thread_local uint32_t lab_grain_seed=0;
thread_local float lab_grain_strength=1;
std::atomic<const char*> lab_stage{""};
thread_local LabRenderReport* lab_render_report = nullptr;

LabRenderReport::LabRenderReport() : previous(lab_render_report) {
    lab_render_report = this;
}
LabRenderReport::~LabRenderReport() { lab_render_report = previous; }

namespace {
template <size_t N>
std::ostream& json_array(std::ostream& out, const double (&values)[N]) {
    out << '[';
    for (size_t i = 0; i < N; ++i) {
        if (i) out << ',';
        out << values[i];
    }
    return out << ']';
}

void append_diffusion(std::ostream& out, const DiffusionFilterParams& p) {
    constexpr const char* families[] = {"glimmerglass", "black_pro_mist", "pro_mist", "cinebloom"};
    out << "{\"active\":" << p.active << ",\"strength\":" << (p.active ? p.strength : 0.0)
        << ",\"family\":\"" << families[static_cast<int>(p.family)] << '"'
        << ",\"spatialScale\":" << p.spatial_scale << ",\"warmth\":" << p.halo_warmth
        << ",\"coreIntensity\":" << p.core_intensity << ",\"coreSize\":" << p.core_size
        << ",\"haloIntensity\":" << p.halo_intensity << ",\"haloSize\":" << p.halo_size
        << ",\"bloomIntensity\":" << p.bloom_intensity << ",\"bloomSize\":" << p.bloom_size << '}';
}
}  // namespace

std::string LabRenderReport::json_fields(float frame_mm) const {
    const auto& d = film.dir_couplers;
    const auto& h = film.halation;
    const auto& g = film.grain;
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << std::boolalpha << "\"filmReferenceIlluminant\":\"" << (film.tungsten_reference ? "T" : "D55")
        << "\",\"resolvedSpatial\":{\"frameLongEdgeMm\":" << frame_mm
        << ",\"dirActive\":" << d.active
        << ",\"dir\":{\"sizeUm\":" << (d.active ? d.diffusion_size_um : 0.0)
        << ",\"tailUm\":" << d.diffusion_tail_um << ",\"tailWeight\":" << d.diffusion_tail_weight
        << ",\"amount\":" << d.amount << ",\"sameLayer\":" << d.inhibition_samelayer
        << ",\"interLayer\":" << d.inhibition_interlayer << ",\"sameLayerGamma\":";
    json_array(out, d.gamma_samelayer_rgb) << ",\"rToGB\":";
    json_array(out, d.gamma_interlayer_r_to_gb) << ",\"gToRB\":";
    json_array(out, d.gamma_interlayer_g_to_rb) << ",\"bToRG\":";
    json_array(out, d.gamma_interlayer_b_to_rg) << "},\"filmDiffusion\":";
    append_diffusion(out, film.diffusion_filter);
    out << ",\"printDiffusion\":";
    append_diffusion(out, print_diffusion);
    out << ",\"halation\":{\"active\":" << h.active << ",\"scatterAmount\":" << h.scatter_amount
        << ",\"scatterScale\":" << h.scatter_spatial_scale << ",\"reflectionAmount\":" << h.halation_amount
        << ",\"reflectionScale\":" << h.halation_spatial_scale << ",\"boostEv\":" << h.boost_ev
        << ",\"bounces\":" << h.halation_n_bounces << ",\"bounceDecay\":" << h.halation_bounce_decay
        << ",\"renormalize\":" << h.halation_renormalize << ",\"scatterCoreUm\":";
    json_array(out, h.scatter_core_um) << ",\"scatterTailUm\":";
    json_array(out, h.scatter_tail_um) << ",\"scatterTailWeight\":";
    json_array(out, h.scatter_tail_weight) << ",\"reflectionStrength\":";
    json_array(out, h.halation_strength) << ",\"firstSigmaUm\":";
    json_array(out, h.halation_first_sigma_um) << '}';
    out << ",\"grain\":{\"active\":" << g.active << ",\"strength\":" << (g.active ? lab_grain_strength : 0.0)
        << ",\"monochrome\":" << g.monochrome
        << ",\"model\":\"profile-density-sublayers-poisson-binomial\""
        << ",\"seedAlgorithm\":\"pcg32-box-muller-normal-v1\""
        << ",\"particleAreaUm2\":" << g.agx_particle_area_um2
        << ",\"blurPixels\":" << g.blur << ",\"dyeCloudScale\":" << g.blur_dye_clouds_um
        << ",\"particleScale\":";
    json_array(out, g.agx_particle_scale) << ",\"sublayerScale\":";
    json_array(out, g.agx_particle_scale_layers) << ",\"densityMin\":";
    json_array(out, g.density_min) << ",\"uniformity\":";
    json_array(out, g.uniformity) << ",\"microStructure\":";
    json_array(out, g.micro_structure) << '}';
    out << ",\"scanner\":{\"active\":" << (unsharp_sigma > 0 && unsharp_amount > 0)
        << ",\"unsharpSigmaPixels\":" << unsharp_sigma << ",\"unsharpAmount\":" << unsharp_amount << "}}"
        << ",\"neutralPrintFilters\":{\"source\":\"" << neutral_filter_source << "\",\"cmyCc\":";
    json_array(out, neutral_filter_cc) << '}';
    return out.str();
}

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
