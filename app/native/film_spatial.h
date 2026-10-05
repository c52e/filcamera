// FILCamera spatial film-engine adapter (GPL-3.0-only).
#pragma once
#include <vector>
#include <cstdint>
#include <atomic>
#include "runtime/params.h"
#include "profiles/profile.h"
namespace spk {
void audit_spectral_dispatch(const float*,float*,int,int,double,double,double,const float*,uint32_t,const std::vector<float>&);
extern thread_local uint32_t lab_grain_seed;
extern std::atomic<const char*> lab_stage;
void lab_grain(const Profile&,const FilmingParams&,const std::vector<float>&,int,int,float*);
namespace gpu { void lab_close_gpu(); }
}
