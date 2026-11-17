// FILCamera spatial film-engine adapter (GPL-3.0-only).
#pragma once
#include <vector>
#include <cstdint>
#include <atomic>
#include <string>
#include "spektra.h"
#include "runtime/params.h"
#include "profiles/profile.h"
namespace spk {
void audit_spectral_dispatch(const float*,float*,int,int,double,double,double,const float*,uint32_t,const std::vector<float>&);
extern thread_local uint32_t lab_grain_seed;
extern thread_local float lab_grain_strength;
extern std::atomic<const char*> lab_stage;
struct LabRenderReport {
    LabRenderReport();
    ~LabRenderReport();
    LabRenderReport(const LabRenderReport&) = delete;
    LabRenderReport& operator=(const LabRenderReport&) = delete;
    std::string json_fields(float frame_mm) const;

    FilmingParams film;
    DiffusionFilterParams print_diffusion;
    double unsharp_sigma = 0.0;
    double unsharp_amount = 0.0;
    const char* neutral_filter_source = "not-applicable";
    double neutral_filter_cc[3]{};

private:
    LabRenderReport* previous;
};
extern thread_local LabRenderReport* lab_render_report;
void validate_lab_film_route(spk_engine*, const char* film, bool direct_scan);
void lab_grain(const Profile&,const FilmingParams&,const std::vector<float>&,int,int,float*);
namespace gpu { void lab_close_gpu(); }
}
