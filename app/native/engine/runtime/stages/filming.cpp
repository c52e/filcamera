#include "resident_spatial.h"
#include "film_spatial.h"
#include "resident.h"
#include "runtime.h"
#include <stdexcept>
// FILCamera adaptation: CAT16, no pre-tri2quad xy clamp; D55 only.
/*
 * Spektrafilm for Android — native engine: filming stage (rgb -> raw -> density).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements runtime/stages/filming.h.
 */
#include "runtime/stages/filming.h"

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <new>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "kernels/exponential_filter.h"
#include "kernels/parallel.h"
#include "kernels/spectral_upsampling.h"
#include "runtime/stage_timer.h"
#include "model/couplers.h"
#include "model/density_curves.h"
#include "model/diffusion.h"
#include "model/grain.h"
#include "model/spectral.h"

namespace spk {
void audit_film_expose(const float*,float*,int,const double*,int,double,double);

namespace {
std::atomic<long long> g_gpu_halation_frames{0};

// Runs model/diffusion.cpp::apply_halation_um's model on the GPU. Returns true
// only when the whole pass succeeded and `raw` now holds its result; false
// leaves `raw` untouched for the CPU pass. A no-op parameter set is reported
// as success without engaging the device (the CPU pass would not change the
// image either).
// The camera diffusion filter on the GPU (#213). Same contract as
// gpu_halation_pass below: true only when the whole pass succeeded and `raw` holds
// its result, false leaves `raw` untouched for the CPU pass.
//
// It runs on the SAME kernel. The diffusion PSF decomposes into a weighted set of
// Gaussians (model/diffusion.cpp, build_diffusion_mixture) and its resolve,
// (1 - p_s) * E_in + p_s * (K_s * E_in), is the expression the scatter resolve
// already computes -- so the halation pass's mixture mode runs it unchanged.
//
// THIS IS AN APPROXIMATION, and a coarser one than the halation port. Fitting an
// exponential with Gaussians is worst at r = 0, where the exponential has a cusp,
// and that cusp is the bright core of the bloom. Measured against the exact PSF at
// 12.5 MP, Black Pro-Mist: max_abs 1.4e-03 with the 7-term fit -- 13x outside the
// 1e-4 oracle band (tests/bench_diffusion_mixture.cpp). It is therefore gated to the
// Fast GPU route only; Strict Exact keeps the FFT.
bool gpu_diffusion_pass(double* raw, int width, int height,
                        const DiffusionFilterParams& p, double pixel_size_um) {
    if (!gpu::available()) return false;

    std::vector<DiffusionGaussian> comps;
    double p_s = 0.0;
    int radius = 0;
    // kDiffusionMixtureTerms: Gaussians per exponential term. 7 measured 2.5x tighter
    // than the upstream 3-term OFX fit for ~2x the components; the components are
    // separable blurs whose cost is O(1) in sigma, so they are cheap to add.
    constexpr int kDiffusionMixtureTerms = 7;
    if (!build_diffusion_mixture(p, pixel_size_um, width, height,
                                 kDiffusionMixtureTerms, &comps, &p_s, &radius))
        return false;   // a no-op parameter set; let the CPU path's own gate decide
    if (comps.empty()) return false;

    std::vector<double> sigma(comps.size());
    std::vector<double> weight(comps.size() * 3u);
    for (size_t i = 0; i < comps.size(); ++i) {
        sigma[i] = comps[i].sigma_px;
        for (int c = 0; c < 3; ++c) weight[i * 3u + static_cast<size_t>(c)] = comps[i].weight[c];
    }

    gpu::HalationScatterRequest request{};
    request.raw_rgb = raw;
    request.width = width;
    request.height = height;
    // The component sigmas are already in pixels, so the pass must not scale them
    // again: pixel_size_um is unused for them and the spatial scale is unity.
    request.pixel_size_um = pixel_size_um > 0.0 ? pixel_size_um : 1.0;
    request.scatter_spatial_scale = 1.0;
    request.scatter_amount = p_s;          // the convex-combination fraction
    request.mixture_sigma_px = sigma.data();
    request.mixture_weight = weight.data();
    request.mixture_count = static_cast<int>(comps.size());
    // Halation is a separate stage and must not run here.
    request.halation_amount = 0.0;
    request.halation_n_bounces = 0;

    const size_t total = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    std::vector<double> out;
    try {
        out.resize(total);
    } catch (const std::bad_alloc&) {
        return false;
    }
    gpu::HalationScatterDiagnostics diagnostics{};
    const bool ok = gpu::halation_scatter(request, out.data(), &diagnostics);
    stage_timing_note_gpu_halation(diagnostics.attempted, diagnostics.engaged,
                                   diagnostics.reason, diagnostics.bands,
                                   diagnostics.dispatches, diagnostics.upload_ms,
                                   diagnostics.gpu_ms, diagnostics.readback_ms);
    if (!ok || !diagnostics.engaged) return false;
    parallel_for(0, static_cast<int>(total), [&](int lo, int hi) {
        for (int i = lo; i < hi; ++i) raw[i] = out[i];
    });
    return true;
}

// Observability for the pointwise filming route, on the same SPK_GPU_DEBUG
// switch the scanning and diffusion stages use. A refusal here is otherwise
// invisible: the CPU loop runs, the picture is right, and the only symptom is a
// stage timing that did not move.
static void note_gpu_filming(const char* what, const gpu::FilmingStageDiagnostics& d) {
    const char* dbg = std::getenv("SPK_GPU_DEBUG");
    if (!dbg || dbg[0] != '1') return;
    std::fprintf(stderr,
                 "[gpu-debug] filming_%s engaged=%d reason=%s up=%.1f gpu=%.1f down=%.1f\n",
                 what, d.engaged ? 1 : 0, d.reason && *d.reason ? d.reason : "-",
                 d.upload_ms, d.gpu_ms, d.readback_ms);
}

bool gpu_halation_pass(double* raw, int width, int height,
                       const HalationParams& p, double pixel_size_um,bool outputLog=false) {
    if (!gpu::available()) return false;
    gpu::HalationScatterRequest request{};
    request.output_log=outputLog;
    request.raw_rgb = raw;
    request.width = width;
    request.height = height;
    request.pixel_size_um = pixel_size_um;
    request.scatter_amount = p.scatter_amount;
    request.scatter_spatial_scale = p.scatter_spatial_scale;
    request.halation_amount = p.halation_amount;
    request.halation_spatial_scale = p.halation_spatial_scale;
    request.halation_n_bounces = p.halation_n_bounces;
    request.halation_bounce_decay = p.halation_bounce_decay;
    request.halation_renormalize = p.halation_renormalize;
    for (int c = 0; c < 3; ++c) {
        request.scatter_core_um[c] = p.scatter_core_um[c];
        request.scatter_tail_um[c] = p.scatter_tail_um[c];
        request.scatter_tail_weight[c] = p.scatter_tail_weight[c];
        request.halation_strength[c] = p.halation_strength[c];
        request.halation_first_sigma_um[c] = p.halation_first_sigma_um[c];
    }
    const size_t total = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    std::vector<double> out;
    try {
        if(!gpu::frame_active(width,height)) out.resize(total);
    } catch (const std::bad_alloc&) {
        return false;
    }
    gpu::HalationScatterDiagnostics diagnostics{};
    const bool ok = gpu::halation_scatter(request, out.data(), &diagnostics);
    stage_timing_note_gpu_halation(diagnostics.attempted, diagnostics.engaged,
                                   diagnostics.reason, diagnostics.bands,
                                   diagnostics.dispatches, diagnostics.upload_ms,
                                   diagnostics.gpu_ms, diagnostics.readback_ms);
    if (!ok) return false;
    if (diagnostics.engaged && !gpu::frame_active(width,height)) {
        parallel_for(0, static_cast<int>(total), [&](int lo, int hi) {
            for (int i = lo; i < hi; ++i) raw[i] = out[i];
        });
        g_gpu_halation_frames.fetch_add(1, std::memory_order_relaxed);
    }
    return true;
}
}  // namespace

unsigned long long gpu_halation_frames_rendered() {
    return static_cast<unsigned long long>(
        g_gpu_halation_frames.load(std::memory_order_relaxed));
}

namespace {

// ProPhoto RGB (linear) -> XYZ with CAT02 chromatic adaptation to the film's
// reference illuminant (D55), apply_cctf_decoding=False. Baked from
// colour.RGB_to_XYZ at full double precision (see oracle dump). Matches
// _rgb_to_tc_b's fixed linear transform for color_space='ProPhoto RGB'.
// Row-major 3x3: xyz[r] = sum_c M[r][c] * rgb[c].
constexpr double kProPhotoToXyzD55[3][3] = {
    {0.78940291537782703, 0.12282122346225685, 0.044468206688454449},
    {0.28698841093710081, 0.71347859576974382, -0.00046667083367552562},
    {0.0014710100935912593, 0.0038855140649431182, 0.91578379979792301},
};

inline double clip01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

// _rgb_to_tc_b for color_space='ProPhoto RGB', apply_cctf_decoding=False,
// reference_illuminant='D55'. Mirrors kernels rgb_to_tc_b but with the
// ProPhoto matrix.
void prophoto_rgb_to_tc_b(const double rgb[3], Vec2* out_tc, double* out_b) {
    double xyz[3];
    for (int r = 0; r < 3; ++r) {
        xyz[r] = kProPhotoToXyzD55[r][0] * rgb[0] + kProPhotoToXyzD55[r][1] * rgb[1] +
                 kProPhotoToXyzD55[r][2] * rgb[2];
    }
    double b = xyz[0] + xyz[1] + xyz[2];
    double denom = std::fmax(b, 1e-10);
    Vec2 xy{xyz[0] / denom, xyz[1] / denom};
    *out_tc = tri2quad(xy);
    *out_b = std::isnan(b) ? 0.0 : b;
}

// compute_band_pass_filter(filter_uv, filter_ir): the camera UV/IR cut filter
// (model/color_filters.py). filter_uv/ir = (amp, wavelength_nm, width_nm). Each is
//   sigmoid_erf(x, center, width) = 0.5*(1 + erf((x - center)/width))
//   filter_uv = 1-amp_uv + amp_uv*sigmoid_erf(wl, wl_uv, +width_uv)
//   filter_ir = 1-amp_ir + amp_ir*sigmoid_erf(wl, wl_ir, -width_ir)
//   band_pass = filter_uv * filter_ir            (common across channels)
// amp is clipped to [0,1] (np.clip), exactly as the oracle. Note width enters the
// erf argument as a plain divisor (NOT scaled by sqrt(2)) — unlike the erf4 window
// above — so this matches scipy.special.erf((x-center)/width) verbatim.
void eval_camera_band_pass(const float* filter_uv, const float* filter_ir,
                           const std::vector<float>& wavelengths,
                           std::vector<double>* band_pass /* (S,) common */) {
    double amp_uv = static_cast<double>(filter_uv[0]);
    double wl_uv = static_cast<double>(filter_uv[1]);
    double width_uv = static_cast<double>(filter_uv[2]);
    double amp_ir = static_cast<double>(filter_ir[0]);
    double wl_ir = static_cast<double>(filter_ir[1]);
    double width_ir = static_cast<double>(filter_ir[2]);
    if (amp_uv < 0.0) amp_uv = 0.0; else if (amp_uv > 1.0) amp_uv = 1.0;  // np.clip
    if (amp_ir < 0.0) amp_ir = 0.0; else if (amp_ir > 1.0) amp_ir = 1.0;
    int S = static_cast<int>(wavelengths.size());
    band_pass->resize(S);
    for (int s = 0; s < S; ++s) {
        double wl = static_cast<double>(wavelengths[s]);
        double sig_uv = 0.5 * (1.0 + std::erf((wl - wl_uv) / width_uv));
        double sig_ir = 0.5 * (1.0 + std::erf((wl - wl_ir) / (-width_ir)));
        double f_uv = 1.0 - amp_uv + amp_uv * sig_uv;
        double f_ir = 1.0 - amp_ir + amp_ir * sig_ir;
        (*band_pass)[s] = f_uv * f_ir;
    }
}

// eval_erf4_spectral_bandpass(window_params): common band-pass replicated across
// the 3 channels. params = [c_uv, sigma_uv, c_ir, sigma_ir].
//   edge_uv = 0.5*(1 + erf((wl - c_uv)/(sigma_uv*sqrt2)))
//   edge_ir = 0.5*(1 - erf((wl - c_ir)/(sigma_ir*sqrt2)))
//   window  = edge_uv * edge_ir
void eval_erf4_window(const double* window_params, const std::vector<float>& wavelengths,
                      std::vector<double>* window /* (S,) common */) {
    const double sqrt2 = std::sqrt(2.0);
    double c_uv = window_params[0], sigma_uv = window_params[1];
    double c_ir = window_params[2], sigma_ir = window_params[3];
    int S = static_cast<int>(wavelengths.size());
    window->resize(S);
    for (int s = 0; s < S; ++s) {
        double wl = static_cast<double>(wavelengths[s]);
        double edge_uv = 0.5 * (1.0 + std::erf((wl - c_uv) / (sigma_uv * sqrt2)));
        double edge_ir = 0.5 * (1.0 - std::erf((wl - c_ir) / (sigma_ir * sqrt2)));
        (*window)[s] = edge_uv * edge_ir;
    }
}

// ---- hanatos2025 log-exposure-correction surface (poly4 model) ----
// Mirrors spectral_upsampling.py::eval_poly4_log_exposure_surface +
// compute_hanatos2025_tc_lut's `raw_lut *= 2**surface`. The surface is a per-LUT-
// cell, per-channel log-exposure correction evaluated over the same (L, L) tc grid
// as the spectra LUT's first two axes, multiplied into the tc_lut as 2**surface.

constexpr double kHanatos2025MaxCorrectionStops = 2.0;  // _HANATOS2025_MAX_CORRECTION_STOPS

// _tri2quad: triangular chromaticity -> square coords (scalar).
inline void tri2quad(double tx, double ty, double* qx, double* qy) {
    double y = ty / std::fmax(1.0 - tx, 1e-10);
    double x = (1.0 - tx) * (1.0 - tx);
    *qx = x < 0.0 ? 0.0 : (x > 1.0 ? 1.0 : x);
    *qy = y < 0.0 ? 0.0 : (y > 1.0 ? 1.0 : y);
}

// hanika_sigmoid: algebraic sigmoid bounded to [-max_val, max_val].
inline double hanika_sigmoid(double z, double max_val) {
    return z / std::sqrt(1.0 + (z / max_val) * (z / max_val));
}

// poly2d_deg4(x, y; params, center): degree-4 2D polynomial. `params` is the
// per-channel coefficient row [c0..c14]; c0 is unused (the centre is the zero-
// correction point). Mirrors spectral_upsampling.py::poly2d_deg4.
inline double poly2d_deg4(double tcx, double tcy, const double* params, double cx,
                          double cy) {
    double x = tcx - cx;
    double y = tcy - cy;
    double x2 = x * x, y2 = y * y;
    double xy_term = x * y;
    double x3 = x2 * x, y3 = y2 * y;
    double c1 = params[1], c2 = params[2], c3 = params[3], c4 = params[4],
           c5 = params[5], c6 = params[6], c7 = params[7], c8 = params[8],
           c9 = params[9], c10 = params[10], c11 = params[11], c12 = params[12],
           c13 = params[13], c14 = params[14];
    return c1 * x + c2 * y + c3 * x2 + c4 * y2 + c5 * xy_term + c6 * x3 + c7 * y3 +
           c8 * (x2 * y) + c9 * (x * y2) + c10 * (x2 * x2) + c11 * (y2 * y2) +
           c12 * (x3 * y) + c13 * (x2 * y2) + c14 * (x * y3);
}

// eval_poly4_log_exposure_surface over the (L, L) tc grid (tc_base = linspace(0,1,L),
// tc[i,j] = (tc_base[i], tc_base[j]) with indexing='ij'). Writes surface[(i*L+j)*3+c].
// center_tc = tri2quad(illuminant_xy). Stores the per-cell log-exposure correction
// (in stops) — the caller multiplies the tc_lut by 2**surface.
void eval_poly4_surface(const double* surface_params, int cols, int L,
                        double illu_xy_x, double illu_xy_y,
                        std::vector<double>* surface /* (L*L*3,) */) {
    double cx, cy;
    tri2quad(illu_xy_x, illu_xy_y, &cx, &cy);
    surface->assign(static_cast<size_t>(L) * L * 3, 0.0);
    for (int i = 0; i < L; ++i) {
        double tcx = (L > 1) ? static_cast<double>(i) / (L - 1) : 0.0;
        for (int j = 0; j < L; ++j) {
            double tcy = (L > 1) ? static_cast<double>(j) / (L - 1) : 0.0;
            double* o = &(*surface)[(static_cast<size_t>(i) * L + j) * 3];
            for (int c = 0; c < 3; ++c) {
                const double* pp = &surface_params[static_cast<size_t>(c) * cols];
                double raw = poly2d_deg4(tcx, tcy, pp, cx, cy);
                o[c] = hanika_sigmoid(raw, kHanatos2025MaxCorrectionStops);
            }
        }
    }
}

}  // namespace

namespace {

// 1-D Gaussian blur of the spectra LUT along its spectral axis (axis 2), in
// float64, mirroring scipy.ndimage.gaussian_filter(lut, sigma=(0,0,sigma)) with
// the SciPy defaults order=0, mode='reflect', truncate=4.0. The first two LUT
// axes get sigma 0 (no blur). Ports compute_hanatos2025_tc_lut's optional blur.
//
// SciPy's gaussian_filter1d: radius lw = int(truncate*sigma + 0.5); the symmetric
// weights w[k] = exp(-0.5*(k/sigma)^2) for k in [-lw,lw], normalised; the result
// is correlate1d with mode='reflect' (d c b a | a b c d | d c b a). The kernel is
// symmetric so correlate == convolve. All arithmetic stays in double to match
// the oracle's np.double LUT path.
NdArray blur_spectra_lut_spectral(const NdArray& lut, double sigma) {
    const int ni = lut.shape[0];
    const int nj = lut.shape[1];
    const int ns = lut.shape[2];  // spectral axis length

    // SciPy: lw = int(truncate*sd + 0.5), truncate=4.0.
    int lw = static_cast<int>(4.0 * sigma + 0.5);
    if (lw < 0) lw = 0;
    if (lw == 0) return lut;  // degenerate kernel {1.0} -> identity

    // Weights: exp(-0.5/sigma^2 * x^2) over x in [-lw, lw], then normalise.
    const int ksize = 2 * lw + 1;
    std::vector<double> w(ksize);
    double wsum = 0.0;
    const double inv2s2 = 0.5 / (sigma * sigma);
    for (int k = 0; k < ksize; ++k) {
        double x = static_cast<double>(k - lw);
        double v = std::exp(-inv2s2 * x * x);
        w[k] = v;
        wsum += v;
    }
    for (int k = 0; k < ksize; ++k) w[k] /= wsum;

    NdArray out;
    out.shape = lut.shape;
    out.data.assign(lut.data.size(), 0.0);

    // reflect(i, n): scipy.ndimage mode='reflect'.
    auto reflect = [](int i, int n) -> int {
        if (i >= 0 && i < n) return i;
        if (i >= -n && i < 0) return -i - 1;
        if (i >= n && i < 2 * n) return 2 * n - 1 - i;
        int period = 2 * n;
        i = i % period;
        if (i < 0) i += period;
        if (i >= n) i = period - 1 - i;
        return i;
    };

    for (int i = 0; i < ni; ++i) {
        for (int j = 0; j < nj; ++j) {
            const double* in = &lut.data[(static_cast<size_t>(i) * nj + j) * ns];
            double* o = &out.data[(static_cast<size_t>(i) * nj + j) * ns];
            for (int s = 0; s < ns; ++s) {
                double acc = 0.0;
                for (int k = -lw; k <= lw; ++k) {
                    acc += w[k + lw] * in[reflect(s + k, ns)];
                }
                o[s] = acc;
            }
        }
    }
    return out;
}

}  // namespace

NdArray build_filming_tc_lut(const Profile& film, const NdArray& spectra_lut_in,
                             const double* illuminant,
                             float spectral_gaussian_blur, bool apply_window,
                             bool apply_surface, const float* filter_uv,
                             const float* filter_ir,
                             InputGamutCompress input_gamut_compress,
                             double in_gamut_knee_threshold,
                             double in_gamut_knee_limit,
                             double in_gamut_knee_power) {
    // Optional spectral-domain blur of the spectra LUT (default 0 -> no-op). Done
    // up front, before the sensitivity contraction, matching upstream
    // compute_hanatos2025_tc_lut.
    NdArray spectra_lut_blurred;
    const bool do_blur = spectral_gaussian_blur > 0.0f;
    if (do_blur) {
        spectra_lut_blurred =
            blur_spectra_lut_spectral(spectra_lut_in,
                                      static_cast<double>(spectral_gaussian_blur));
    }
    const NdArray& spectra_lut = do_blur ? spectra_lut_blurred : spectra_lut_in;

    int S = film.n_samples;  // 81
    // sensitivity = nan_to_num(10**log_sensitivity)  (S,3)
    std::vector<double> sens(static_cast<size_t>(S) * 3);
    for (int s = 0; s < S; ++s)
        for (int c = 0; c < 3; ++c) {
            double ls = static_cast<double>(film.log_sensitivity[s * 3 + c]);
            double v = std::pow(10.0, ls);
            if (std::isnan(v) || std::isinf(v)) v = 0.0;  // np.nan_to_num
            sens[s * 3 + c] = v;
        }

    // Camera UV/IR band-pass cut filter (filming.py::_rgb_to_film_raw, applied to
    // `sensitivity` BEFORE it is handed to compute_hanatos2025_tc_lut). Gated on
    // `filter_uv[0] > 0 OR filter_ir[0] > 0` exactly like the oracle, so the
    // default (amp 0/0) is a strict no-op. The band-pass is common across channels;
    // per-channel white-balance normalisation preserves the channel response to the
    // film reference illuminant:
    //   norm[c] = sum_s sens[s,c]*band[s]*illu[s] / sum_s sens[s,c]*illu[s]
    //   sens[s,c] *= band[s] / norm[c]
    // `illuminant` is the film reference illuminant (the same SPD the window
    // normalisation uses), matching standard_illuminant(film.reference_illuminant).
    if (filter_uv != nullptr && filter_ir != nullptr &&
        (filter_uv[0] > 0.0f || filter_ir[0] > 0.0f)) {
        std::vector<double> band;
        eval_camera_band_pass(filter_uv, filter_ir, film.wavelengths, &band);
        double bnum[3] = {0, 0, 0}, bden[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            for (int c = 0; c < 3; ++c) {
                double se = sens[s * 3 + c];
                bnum[c] += se * band[s] * ill;
                bden[c] += se * ill;
            }
        }
        double bnorm[3];
        for (int c = 0; c < 3; ++c) bnorm[c] = bnum[c] / bden[c];
        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c)
                sens[s * 3 + c] *= band[s] / bnorm[c];
    }

    // Per-channel sensitivity weights folded into the spectra contraction. When
    // apply_window is on, this is sens[s,c]*window[s]/norm[c] (white-balance-
    // preserving erf4 band-pass); otherwise it is the bare sensitivity. Mirrors
    // compute_hanatos2025_tc_lut's apply_window branch.
    std::vector<double> sw(static_cast<size_t>(S) * 3);
    if (apply_window) {
        // window (common across channels) via erf4, then per-channel white-balance
        // normalisation: window[s,c] /= norm[c].
        std::vector<double> window;
        eval_erf4_window(film.window_params.data(), film.wavelengths, &window);

        double num[3] = {0, 0, 0}, den[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            for (int c = 0; c < 3; ++c) {
                double se = sens[s * 3 + c];
                num[c] += se * ill * window[s];
                den[c] += se * ill;
            }
        }
        double norm[3];
        for (int c = 0; c < 3; ++c) norm[c] = num[c] / den[c];

        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c)
                sw[s * 3 + c] = sens[s * 3 + c] * window[s] / norm[c];
    } else {
        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c) sw[s * 3 + c] = sens[s * 3 + c];
    }

    // tc_lut[i,j,c] = sum_s spectra_lut[i,j,s] * sens_window[s,c]
    int L = spectra_lut.shape[0];
    int Ssl = spectra_lut.shape[2];
    NdArray tc_lut;
    tc_lut.shape = {L, L, 3};
    tc_lut.data.assign(static_cast<size_t>(L) * L * 3, 0.0);
    for (int i = 0; i < L; ++i) {
        for (int j = 0; j < L; ++j) {
            const double* spec = &spectra_lut.data[(static_cast<size_t>(i) * L + j) * Ssl];
            double acc[3] = {0, 0, 0};
            for (int s = 0; s < Ssl; ++s) {
                double sp = spec[s];
                acc[0] += sp * sw[s * 3 + 0];
                acc[1] += sp * sw[s * 3 + 1];
                acc[2] += sp * sw[s * 3 + 2];
            }
            double* o = &tc_lut.data[(static_cast<size_t>(i) * L + j) * 3];
            o[0] = acc[0];
            o[1] = acc[1];
            o[2] = acc[2];
        }
    }

    // Optional log-exposure-correction surface: raw_lut *= 2**surface, where
    // surface is the per-cell, per-channel poly4 correction over the (L, L) tc grid
    // evaluated at the film's reference illuminant chromaticity. Mirrors
    // compute_hanatos2025_tc_lut's apply_surface branch (model='poly4'). The
    // reference illuminant chromaticity is computed from the SAME illuminant SPD
    // used for the window normalisation, integrated against the CIE 1931 2deg CMFs
    // (xy is scale-invariant, so the SPD's normalisation is irrelevant).
    if (apply_surface && film.surface_params_cols > 0 &&
        static_cast<int>(film.surface_params.size()) == 3 * film.surface_params_cols) {
        double xyz[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            xyz[0] += ill * static_cast<double>(kCieCmf1931[s][0]);
            xyz[1] += ill * static_cast<double>(kCieCmf1931[s][1]);
            xyz[2] += ill * static_cast<double>(kCieCmf1931[s][2]);
        }
        double sum_xyz = xyz[0] + xyz[1] + xyz[2];
        double illu_xy_x = xyz[0] / sum_xyz;
        double illu_xy_y = xyz[1] / sum_xyz;

        std::vector<double> surface;
        eval_poly4_surface(film.surface_params.data(), film.surface_params_cols, L,
                           illu_xy_x, illu_xy_y, &surface);
        size_t ncell = static_cast<size_t>(L) * L * 3;
        for (size_t k = 0; k < ncell; ++k) {
            tc_lut.data[k] *= std::pow(2.0, surface[k]);
        }
    }

    // Optional INPUT gamut compression bake (opt-in; default kOff -> skipped, so the
    // tc_lut is byte-identical to the pre-feature build and every golden stays
    // bit-exact). Mirrors compute_hanatos2025_tc_lut appending
    // remap_tc_lut_for_compression when InputGamutCompressSpec.active: radially
    // compress each LUT cell's chromaticity toward the visible spectral locus around
    // the film reference illuminant, then resample the LUT. The reference illuminant
    // chromaticity is computed from the SAME illuminant SPD integrated against the CIE
    // 1931 2deg CMFs (xy is scale-invariant), matching the surface branch above and
    // the oracle's _illuminant_to_xy(reference_illuminant). kOklch is reserved (not
    // ported) and falls through as a no-op.
    if (input_gamut_compress == InputGamutCompress::kXy) {
        double xyz[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            xyz[0] += ill * static_cast<double>(kCieCmf1931[s][0]);
            xyz[1] += ill * static_cast<double>(kCieCmf1931[s][1]);
            xyz[2] += ill * static_cast<double>(kCieCmf1931[s][2]);
        }
        double sum_xyz = xyz[0] + xyz[1] + xyz[2];
        double white_xy[2] = {xyz[0] / sum_xyz, xyz[1] / sum_xyz};
        remap_tc_lut_for_compression(tc_lut, white_xy, in_gamut_knee_threshold,
                                     in_gamut_knee_limit, in_gamut_knee_power);
    }
    return tc_lut;
}

namespace {

// Pixel sources for expose(). Each returns the float64 RGB triple for pixel p.
//   - SrcF64: the materialized float64 image (the historical path).
//   - SrcF32Gain: the caller's float32 frame with the auto-exposure gain folded
//     into the load. Value-identical to materializing: float->double widening
//     is exact and `* gain` is the same double multiply apply_auto_exposure
//     performed in place, in the same order (EXPORT_FASTPATH item 4 — a 12 MP
//     export no longer materializes the ~288 MB float64 input at all).
struct SrcF64 {
    const double* rgb;
    inline void load(int p, double out[3]) const {
        out[0] = rgb[p * 3 + 0];
        out[1] = rgb[p * 3 + 1];
        out[2] = rgb[p * 3 + 2];
    }
};
struct SrcF32Gain {
    const float* rgb;
    double gain;
    inline void load(int p, double out[3]) const {
        out[0] = static_cast<double>(rgb[p * 3 + 0]) * gain;
        out[1] = static_cast<double>(rgb[p * 3 + 1]) * gain;
        out[2] = static_cast<double>(rgb[p * 3 + 2]) * gain;
    }
};

template <typename Src>
void expose_impl(const Src& src, int width, int height,
                 const FilmingParams& params, const NdArray& tc_lut,
                 float* log_raw_out,
                 // The same source the Src adapter wraps, handed to the GPU pass
                 // directly: exactly one is non-null, and `gpu_gain` is the
                 // expose_f32_gain auto-exposure factor (1.0 on the f64 path).
                 const double* gpu_rgb_f64, const float* gpu_rgb_f32,
                 double gpu_gain) {
    const int npix = width * height;
    int L = tc_lut.shape[0];
    double scale = static_cast<double>(L - 1);
    double exp_mult = std::pow(2.0, params.exposure_compensation_ev);  // 1.0 under goldens

    // When every effect between the irradiance computation and the log10 is
    // inert — each gate below mirrors that effect's own self-gate exactly —
    // the two passes fuse: per pixel, raw is a local and goes straight through
    // the log10 into the float32 output. Identical arithmetic on identical
    // operands (the ops between the passes were no-ops), so the output is
    // byte-identical; and no full-resolution float64 `raw` exists at all
    // (~288 MB at 12 MP, EXPORT_FASTPATH item 4). Any active effect takes the
    // materialized path below, unchanged.
    const bool pointwise_fused =
        !(params.halation.boost_ev > 0.0) &&        // apply_highlight_boost gate
        !params.diffusion_filter.active &&           // diffusion filter gate
        !(params.lens_blur_um > 0.0 && params.pixel_size_um > 0.0 &&
          params.lens_blur_um / params.pixel_size_um > 0.0) &&  // lens blur gate
        !params.halation.active &&                   // halation gate
        params.bw_exposure_correction == 1.0;        // b/w correction gate
    const char* audit_mode=std::getenv("FILMCAMERA_GPU_CAM16");
    if(audit_mode && std::string(audit_mode)=="1") {
        if(params.allow_gpu_filming || !gpu_rgb_f32 || gpu_rgb_f64)
            throw std::runtime_error("spatial exposure requires direct FP32 input");
        if(pointwise_fused) {ScopedStage stage(STG_FILMING_EXPOSE);audit_film_expose(gpu_rgb_f32,log_raw_out,npix,tc_lut.data.data(),L,gpu_gain,exp_mult);}
        else {
            if(audit::Session::current().spatialResident) {
                std::vector<float> extra={float(gpu_gain),float(exp_mult),float(L),1};extra.insert(extra.end(),tc_lut.data.begin(),tc_lut.data.end());float constants[501]{};
                {ScopedStage stage(STG_FILMING_EXPOSE);audit_spectral_dispatch(gpu_rgb_f32,nullptr,npix,0,0,1,6,constants,2,extra);}
                ResidentSpatial frame(width,height);
                if(params.diffusion_filter.active) {lab_stage="Film diffusion";ScopedStage stage(STG_DIFFUSION);apply_diffusion_filter_um(nullptr,width,height,params.diffusion_filter,params.pixel_size_um,true);}
                const bool fusedLog=(fc_gpu_optimizations()&FC_HALATION_LOG) && params.halation.active && params.halation.scatter_amount>0;
                if(params.halation.active) {lab_stage="Halation";ScopedStage stage(STG_HALATION);if(!gpu_halation_pass(nullptr,width,height,params.halation,params.pixel_size_um,fusedLog)) throw std::runtime_error("Resident halation failed");}
                gpu::FilmingStageDiagnostics diag{};if(!fusedLog && !gpu::exposure_log10(nullptr,nullptr,width,height,&diag)) throw std::runtime_error("Resident exposure log failed");
                frame.finish();return;
            }
            std::vector<float> linear(size_t(npix)*3);
            std::vector<float> extra={float(gpu_gain),float(exp_mult),float(L),1};
            extra.insert(extra.end(),tc_lut.data.begin(),tc_lut.data.end());
            float constants[501]{};
            audit_spectral_dispatch(gpu_rgb_f32,linear.data(),npix,0,0,1,6,constants,2,extra);
            std::vector<double> raw(linear.begin(),linear.end());
            if(params.diffusion_filter.active) {lab_stage="Film diffusion";ScopedStage stage(STG_DIFFUSION);apply_diffusion_filter_um(raw.data(),width,height,params.diffusion_filter,params.pixel_size_um,true);}
            if(params.halation.active) {lab_stage="Halation";ScopedStage stage(STG_HALATION);if(!gpu_halation_pass(raw.data(),width,height,params.halation,params.pixel_size_um)) throw std::runtime_error("GPU halation failed");}
            gpu::FilmingStageDiagnostics diag{};
            if(!gpu::exposure_log10(raw.data(),log_raw_out,width,height,&diag)) throw std::runtime_error("GPU exposure bridge failed");
        }
        return;
    }
    // GPU EXPOSE (#218). Tried first in both branches; on refusal nothing has
    // been written and the CPU loop below runs over the same memory. The latch
    // is off unless the caller set it, so the default route is unchanged.
    const bool gpu_expose_ok =
        params.allow_gpu_filming && tc_lut.shape.size() == 3 &&
        tc_lut.shape[0] >= 2 && tc_lut.shape[0] == tc_lut.shape[1] &&
        tc_lut.shape[2] == 3;

    if (pointwise_fused) {
        ScopedStage _t(STG_FILMING_EXPOSE);
        if (gpu_expose_ok) {
            gpu::FilmingStageDiagnostics gd{};
            const bool ok = gpu::filming_expose(
                gpu_rgb_f64, gpu_rgb_f32, gpu_gain,
                static_cast<uint32_t>(npix), width, height, tc_lut.data.data(),
                static_cast<uint32_t>(tc_lut.shape[0]), exp_mult,
                /*out_raw=*/nullptr, log_raw_out, &gd);
            note_gpu_filming("expose_fused", gd);
            if (ok) return;
        }
        parallel_for(0, npix, [&](int lo, int hi) {
            for (int p = lo; p < hi; ++p) {
                double in[3];
                src.load(p, in);
                Vec2 tc;
                double b;
                prophoto_rgb_to_tc_b(in, &tc, &b);
                double rr[3];
                cubic_interp_lut_at_2d(tc_lut, tc.x * scale, tc.y * scale, rr);
                for (int c = 0; c < 3; ++c) {
                    double rawv = rr[c] * b * exp_mult;
                    log_raw_out[p * 3 + c] = static_cast<float>(
                        std::log10(std::fmax(rawv, 0.0) + 1e-10));
                }
            }
        });
        return;
    }

    // Compute the float64 pre-log irradiance `raw` for every pixel.
    // raw = rgb_to_raw_hanatos2025(rgb) * brightness * 2^exposure_comp_ev.
    // Allocated UNINITIALIZED (not a zero-filled vector): the loop below writes
    // every element before anything reads it, so the old value-initialization
    // only cost a ~288 MB memset at 12 MP (EXPORT_FASTPATH item 4).
    std::unique_ptr<double[]> raw_buf(new double[static_cast<size_t>(npix) * 3]);
    double* const raw = raw_buf.get();
    { ScopedStage _t(STG_FILMING_EXPOSE);
    bool done = false;
    if (gpu_expose_ok) {
        gpu::FilmingStageDiagnostics gd{};
        done = gpu::filming_expose(
            gpu_rgb_f64, gpu_rgb_f32, gpu_gain, static_cast<uint32_t>(npix),
            width, height, tc_lut.data.data(),
            static_cast<uint32_t>(tc_lut.shape[0]), exp_mult,
            raw, /*out_log_raw=*/nullptr, &gd);
        note_gpu_filming("expose", gd);
    }
    if (!done)
    parallel_for(0, npix, [&](int lo, int hi) {
        for (int p = lo; p < hi; ++p) {
            double in[3];
            src.load(p, in);
            Vec2 tc;
            double b;
            prophoto_rgb_to_tc_b(in, &tc, &b);
            double rr[3];
            cubic_interp_lut_at_2d(tc_lut, tc.x * scale, tc.y * scale, rr);
            for (int c = 0; c < 3; ++c) raw[p * 3 + c] = rr[c] * b * exp_mult;
        }
    }); }

    // Highlight boost (numba_boost_hightlights.boost_highlights), applied on the
    // float64 irradiance AFTER exposure compensation and BEFORE the diffusion filter
    // / lens blur / halation — matching filming.py::expose, which calls
    // boost_highlights(raw, halation.boost_ev, halation.boost_range,
    // halation.protect_ev) right after `raw *= 2**exposure_compensation_ev`. It is
    // NOT gated by params.spatial_effects: the oracle's digest_params zeroes only the
    // scatter/halation sigmas under deactivate_spatial_effects (params_builder.py),
    // never boost_ev, so the boost fires whenever boost_ev > 0. boost_ev == 0
    // (schema/UI default) is a strict identity -> default goldens stay bit-exact.
    { ScopedStage _t(STG_HIGHLIGHT_BOOST);
      apply_highlight_boost(raw, width, height, params.halation,
                            params.allow_gpu_filming); }

    // Camera optical diffusion filter (Black Pro-Mist family), applied on the
    // float64 irradiance AFTER the highlight boost and BEFORE lens blur /
    // halation — matching filming.py::expose, which calls
    // apply_diffusion_filter_um(raw, camera.diffusion_filter, pixel_size_um)
    // before apply_gaussian_blur_um / apply_halation_um. Self-gated on
    // diffusion_filter.active — the oracle's ONLY gate (deactivate_spatial_effects
    // is expressed by the digest zeroing .active, params_builder.py). No-op unless
    // active (schema default false), so default params stay bit-exact.
    if (params.diffusion_filter.active) {
        ScopedStage _t(STG_DIFFUSION);
        // Off by default and nothing sets the latch -- see params.h for the device
        // numbers that took it off the Fast GPU route. The GPU pass returns false
        // without touching `raw` on any refusal, so this falls back rather than
        // half-applying, and with the latch clear it is the FFT unconditionally.
        if (!(params.allow_gpu_diffusion_filter &&
              gpu_diffusion_pass(raw, width, height, params.diffusion_filter,
                                 params.pixel_size_um))) {
            apply_diffusion_filter_um(raw, width, height,
                                      params.diffusion_filter, params.pixel_size_um,
                                      params.allow_gpu_diffusion_fft);
        }
    }

    // Camera lens blur (camera.lens_blur_um), applied on the float64 irradiance
    // AFTER the diffusion filter and BEFORE halation — matching filming.py::expose,
    // which calls apply_gaussian_blur_um(raw, camera.lens_blur_um, pixel_size_um)
    // between apply_diffusion_filter_um and apply_halation_um. apply_gaussian_blur_um
    // gates on sigma = lens_blur_um / pixel_size_um > 0 and blurs every channel with
    // the same scalar sigma (fast_gaussian_filter broadcasts the scalar across the
    // 3 channels). Self-gated on lens_blur_um > 0 — the oracle's only gate
    // (deactivate_spatial_effects is expressed by the digest zeroing lens_blur_um).
    // Default lens_blur_um == 0 => no-op (bit-exact).
    if (params.lens_blur_um > 0.0 &&
        params.pixel_size_um > 0.0) {
        double sigma = params.lens_blur_um / params.pixel_size_um;
        if (sigma > 0.0) {
            ScopedStage _t(STG_LENS_BLUR);
            double sg[3] = {sigma, sigma, sigma};
            // CPU, for the same measured reason as the scanner blurs (see
            // runtime/stages/scanning.cpp): routing a small FIR through the
            // mixture kernel costs a whole-frame f64 staging vector and two
            // conversions, which is more than the blur itself.
            // Same knob as the scanner blurs (runtime/stages/scanning.cpp):
            // the GPU route is correct and gated, and its last export
            // measurement predates the work buffers becoming GPU-private.
            // Same override as the scanner blurs (runtime/stages/scanning.cpp).
            const char* gv = std::getenv("SPK_GPU_BLUR");
            const bool want_gpu = (gv && gv[0] == '0')   ? false
                                  : (gv && gv[0] == '1') ? true
                                                         : params.allow_gpu_filming;
            if (!(want_gpu && gpu::gaussian_blur_rgb(raw, width, height, sg)))
                gaussian_blur_per_channel_d(raw, width, height, 3, sg);
        }
    }

    // In-emulsion scatter + back-reflection halation, applied to the float64
    // irradiance before the log10 (matching filming.py::expose, which calls
    // apply_halation_um on `raw`). Self-gated on halation.active (set by
    // digest_halation_params only when the spatial digest is on and the preset is
    // known), so the spatial-OFF goldens still skip it.
    if (params.halation.active) {
        ScopedStage _t(STG_HALATION);
        // Fast GPU export (#206): the same scatter + halation model on the
        // GPU (gpu/halation_scatter.comp, adapted from spektrafilm OFX, f32).
        // The result lands in a private buffer and replaces `raw` only when
        // the whole pass succeeded; any failure or refusal falls through to the
        // f64 CPU pass, which is the ground truth and stays byte-identical.
        if (!(params.allow_gpu_halation &&
              gpu_halation_pass(raw, width, height, params.halation,
                                params.pixel_size_um))) {
            apply_halation_um(raw, width, height, params.halation,
                              params.pixel_size_um);
        }
    }

    // Scanner BLACK/WHITE filming exposure correction (color_reference.py::
    // black_white_filming_exposure_correction), applied here as filming.py does
    // (`raw *= black_white_filming_exposure_correction()`, after halation, before
    // log10). 1.0 (a strict no-op) on every route except scan_film + positive film,
    // so the default goldens (negative film) stay bit-exact.
    if (params.bw_exposure_correction != 1.0) {
        const double c = params.bw_exposure_correction;
        parallel_for(0, npix * 3, [&](int lo, int hi) {
            for (int i = lo; i < hi; ++i) raw[i] *= c;
        });
    }

    // log_raw = log10(fmax(raw, 0) + 1e-10). Element-wise -> deterministic
    // parallel chunks (byte-identical to the serial loop for any thread count).
    {
        gpu::FilmingStageDiagnostics gd{};
        if (gpu_expose_ok &&
            gpu::exposure_log10(raw, log_raw_out, width, height, &gd)) {
            note_gpu_filming("log10", gd);
            return;
        }
        note_gpu_filming("log10", gd);
    }
    parallel_for(0, npix * 3, [&](int lo, int hi) {
        for (int i = lo; i < hi; ++i) {
            double lr = std::log10(std::fmax(raw[i], 0.0) + 1e-10);
            log_raw_out[i] = static_cast<float>(lr);
        }
    });
}

}  // namespace

void expose(const double* rgb, int width, int height, const FilmingParams& params,
            const NdArray& tc_lut, float* log_raw_out) {
    expose_impl(SrcF64{rgb}, width, height, params, tc_lut, log_raw_out,
                rgb, nullptr, 1.0);
}

void expose_f32_gain(const float* rgb, double gain, int width, int height,
                     const FilmingParams& params, const NdArray& tc_lut,
                     float* log_raw_out) {
    expose_impl(SrcF32Gain{rgb, gain}, width, height, params, tc_lut,
                log_raw_out, nullptr, rgb, gain);
}

void audit_develop_dispatch(const float*,float*,int,const std::vector<float>&);
static std::vector<float> audit_develop_tables(const Profile& film,const FilmingParams& f,const std::vector<float>& ndc) {
    const int n=film.n_density_pts;
    const float* log_exposure=film.log_exposure.data();
    const float* density_curves=ndc.data();
    const bool positive_film=film.is_positive();
    const auto& params=f.dir_couplers;
    double M[9];
    compute_dir_couplers_matrix(params, M);

    // log_exposure axis in double.
    std::vector<double> le(n);
    for (int k = 0; k < n; ++k) le[k] = static_cast<double>(log_exposure[k]);

    // ---- compute_density_curves_before_dir_couplers (negative path) ----
    // density_curves_silver = density_curves (negative)
    // For positive film: silver = nanmax(curves,axis=0) - curves (not exercised
    // by the parity goldens, but kept for correctness).
    std::vector<double> dc(static_cast<size_t>(n) * 3);
    for (int i = 0; i < n * 3; ++i) dc[i] = static_cast<double>(density_curves[i]);

    std::vector<double> silver_curve(static_cast<size_t>(n) * 3);
    if (positive_film) {
        double dmax[3] = {-1e300, -1e300, -1e300};
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c) {
                double v = dc[k * 3 + c];
                if (!std::isnan(v) && v > dmax[c]) dmax[c] = v;
            }
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c)
                silver_curve[k * 3 + c] = dmax[c] - dc[k * 3 + c];
    } else {
        silver_curve = dc;
    }

    // couplers_amount_curves[j,m] = sum_k silver_curve[j,k] * M[k,m]
    // log_exposure_0[j,m] = le[j] - couplers_amount_curves[j,m]
    // dc0[:,i] = interp(le, log_exposure_0[:,i], (+/-)dc[:,i])
    std::vector<double> dc0(static_cast<size_t>(n) * 3);
    std::vector<double> le0(static_cast<size_t>(n));   // per-channel x axis buffer
    std::vector<double> ycol(static_cast<size_t>(n));  // per-channel y values
    std::vector<double> vbuf(static_cast<size_t>(n));  // np.interp output buffer
    for (int c = 0; c < 3; ++c) {
        for (int j = 0; j < n; ++j) {
            double amt = 0.0;
            for (int k = 0; k < 3; ++k) amt += silver_curve[j * 3 + k] * M[k * 3 + c];
            le0[j] = le[j] - amt;
            // negative: y = dc[:,c]; positive: interp on -dc, then negate.
            ycol[j] = positive_film ? -dc[j * 3 + c] : dc[j * 3 + c];
        }
        np_interp_array(le.data(), n, le0.data(), ycol.data(), n, vbuf.data());
        for (int j = 0; j < n; ++j)
            dc0[j * 3 + c] = positive_film ? -vbuf[j] : vbuf[j];
    }

    // ---- compute_exposure_correction_dir_couplers (diffusion off) ----
    //   density_silver = density_cmy (negative) or density_max - density_cmy
    //                    (positive); += high_exposure_shift * silver^2
    //   log_raw_0 = log_raw - density_silver @ M     (diffusion_size_pixel == 0)
    // For positive film density_max is the per-channel nanmax of density_curves.
    double dmax_cmy[3] = {0.0, 0.0, 0.0};
    if (positive_film) {
        dmax_cmy[0] = dmax_cmy[1] = dmax_cmy[2] = -1e300;
        for (int k = 0; k < n; ++k)
            for (int c = 0; c < 3; ++c) {
                double v = dc[k * 3 + c];
                if (!std::isnan(v) && v > dmax_cmy[c]) dmax_cmy[c] = v;
            }
    }
    const double shift = params.high_exposure_couplers_shift;


    std::vector<float> packed={float(n),params.active?1.0f:0.0f,positive_film?1.0f:0.0f,float(shift)};
    packed.insert(packed.end(),M,M+9);packed.insert(packed.end(),dmax_cmy,dmax_cmy+3);
    for(int k=0;k<n;++k) for(int c=0;c<3;++c) packed.push_back(log_exposure[k]/f.density_curve_gamma[c]);
    packed.insert(packed.end(),ndc.begin(),ndc.end());
    for(int k=0;k<n;++k) for(int c=0;c<3;++c) packed.push_back(float(double(log_exposure[k])/double(f.density_curve_gamma[c])));
    packed.insert(packed.end(),dc0.begin(),dc0.end());
    for(float v:packed) if(!std::isfinite(v)) throw std::runtime_error("develop constants nonfinite");
    return packed;
}

void develop(const float* log_raw, int width, int height, const Profile& film,
             const FilmingParams& params, float* density_cmy_out) {
    int n = film.n_density_pts;
    const int npix = width * height;

    const char* mode=std::getenv("FILMCAMERA_GPU_CAM16");
    const bool gpu_develop=mode && std::string(mode)=="1" && !std::getenv("FILMCAMERA_GPU_DEVELOP_CPU");
    if(!log_raw && (!gpu_develop || !audit::Session::current().awaitingDevelop(size_t(npix)*3)))
        throw std::runtime_error("develop requires resident exposure");
    if(gpu_develop) {
        if(n<2 || n>1024 || film.log_exposure.size()!=size_t(n) || film.density_curves.size()!=size_t(n)*3 ||
           params.allow_gpu_filming)
            throw std::runtime_error("develop GPU scope unsupported");
        for(float g:params.density_curve_gamma) if(!std::isfinite(g) || g<=0) throw std::runtime_error("develop gamma unsupported");
        for(int k=0;k<n;++k) if(!std::isfinite(film.log_exposure[k]) || (k && film.log_exposure[k]<=film.log_exposure[k-1]))
            throw std::runtime_error("develop axis unsupported");
    }
    // normalized_density_curves = density_curves - nanmin(density_curves, axis=0)
    std::vector<float> ndc(static_cast<size_t>(n) * 3);
    normalize_density_curves(film.density_curves.data(), n, ndc.data());

    if(gpu_develop) {
        ScopedStage timing(STG_DEVELOP);
        auto curves=audit_develop_tables(film,params,ndc);
        if(params.dir_couplers.active && params.dir_couplers.diffusion_size_um>0) {
            lab_stage="DIR spatial development";ScopedStage stage(STG_DIR_COUPLERS);
            if(audit::Session::current().spatialResident) {
                auto& session=audit::Session::current();session.checkpoint();float constants[501]{};
                audit_spectral_dispatch(nullptr,nullptr,npix,0,0,1,6,constants,5,curves);
                {
                    ResidentSpatial frame(width,height);gpu::HalationScatterRequest r{};r.width=width;r.height=height;
                    r.pixel_size_um=params.pixel_size_um;r.scatter_amount=1;r.scatter_spatial_scale=1;r.halation_n_bounces=0;
                    for(int c=0;c<3;++c) {r.scatter_core_um[c]=params.dir_couplers.diffusion_size_um;r.scatter_tail_um[c]=params.dir_couplers.diffusion_tail_um;r.scatter_tail_weight[c]=params.dir_couplers.diffusion_tail_weight;}
                    gpu::HalationScatterDiagnostics d{};if(!gpu::halation_scatter(r,nullptr,&d) || !d.engaged) throw std::runtime_error("Resident DIR diffusion failed");frame.finish();
                }
                audit_spectral_dispatch(nullptr,nullptr,npix,0,0,1,6,constants,6,curves);
            } else {
            std::vector<float> corr(size_t(npix)*3);float constants[501]{};
            audit_spectral_dispatch(log_raw,corr.data(),npix,0,0,1,6,constants,5,curves);
            std::vector<double> source(corr.begin(),corr.end()), diffused(source.size());
            gpu::HalationScatterRequest r{};r.raw_rgb=source.data();r.width=width;r.height=height;
            r.pixel_size_um=params.pixel_size_um;r.scatter_amount=1;r.scatter_spatial_scale=1;r.halation_n_bounces=0;
            for(int c=0;c<3;++c) {r.scatter_core_um[c]=params.dir_couplers.diffusion_size_um;r.scatter_tail_um[c]=params.dir_couplers.diffusion_tail_um;r.scatter_tail_weight[c]=params.dir_couplers.diffusion_tail_weight;}
            gpu::HalationScatterDiagnostics d{};
            if(!gpu::halation_scatter(r,diffused.data(),&d) || !d.engaged) throw std::runtime_error("GPU DIR diffusion failed");
            auto& aux=audit::Session::current().auxiliary;aux.assign(diffused.begin(),diffused.end());
            audit_spectral_dispatch(log_raw,density_cmy_out,npix,0,0,1,6,constants,6,curves);
            std::vector<float>().swap(aux);
            }
        } else audit_develop_dispatch(log_raw,density_cmy_out,npix,curves);
        if(params.grain.active) {lab_stage="Grain";ScopedStage stage(STG_GRAIN);lab_grain(film,params,ndc,width,height,density_cmy_out);}
        return;
    }
    // density_cmy = interpolate_exposure_to_density(log_raw, ndc, le, gamma)
    { ScopedStage _t(STG_DEVELOP);
      bool done = false;
      if (params.allow_gpu_filming && n >= 2) {
          // The GPU kernel owns no parameter, so the per-channel axis
          // interpolate_exposure_to_density builds internally --
          // xp[k,c] = log_exposure[k] / gamma[c] -- is built here instead.
          const float* g = params.density_curve_gamma;
          std::vector<float> xp(static_cast<size_t>(n) * 3);
          for (int k = 0; k < n; ++k)
              for (int c = 0; c < 3; ++c)
                  xp[static_cast<size_t>(k) * 3 + c] =
                      (g[c] != 0.0f) ? (film.log_exposure[k] / g[c])
                                     : film.log_exposure[k];
          gpu::FilmingStageDiagnostics gd{};
          done = gpu::filming_develop(log_raw, static_cast<uint32_t>(npix),
                                      width, height, xp.data(), ndc.data(),
                                      static_cast<uint32_t>(n),
                                      density_cmy_out, &gd);
          note_gpu_filming("develop", gd);
      }
      if (!done)
      interpolate_exposure_to_density(log_raw, npix, ndc.data(),
                                      film.log_exposure.data(), n,
                                      params.density_curve_gamma, density_cmy_out); }

    // apply_density_correction_dir_couplers. The spatial variant diffuses the
    // inhibitor correction (Gaussian + exponential tail); it self-gates and
    // delegates to the pointwise path when diffusion_size_um == 0 (the digest
    // zeroes the size when the spatial digest is off, mirroring the oracle's
    // deactivate_spatial_effects).
    { ScopedStage _t(STG_DIR_COUPLERS);
      apply_density_correction_dir_couplers_spatial(
          density_cmy_out, width, height, log_raw, film.log_exposure.data(),
          ndc.data(), n, params.dir_couplers, film.is_positive(),
          params.density_curve_gamma,
          params.pixel_size_um, density_cmy_out); }

    // Stochastic grain (AgX particle model). Identity unless grain.active (set by
    // digest_grain_params when grain_active && stochastic effects are on). The
    // model operates on the post-coupler density_cmy, mirroring emulsion.py::
    // develop, which calls apply_grain after apply_density_correction_dir_couplers.
    if (params.grain.active) {
        ScopedStage _t(STG_GRAIN);
        stage_timing_note_grain_sampler(params.grain.fast_sampler);
        GrainParams grain = params.grain;
        // The sublayer path is selected when sublayers_active AND the profile
        // actually carries density_curves_layers; otherwise fall back to the
        // non-sublayer path (mirrors apply_grain's structure, where the layers
        // are required for the sublayer branch).
        const bool have_layers =
            static_cast<int>(film.density_curves_layers.size()) == n * 9;
        if (grain.sublayers_active && have_layers) {
            // interp_density_cmy_layers(density_cmy, normalized_density_curves,
            //                           density_curves_layers_RAW, positive_film).
            // NOTE: apply_grain passes the RAW (un-normalized) density_curves_layers
            // here, while density_curves is the normalized curve axis.
            // 450 MB at 12.5 MP: allocated, zero-initialised, then filled.
            // Timed together because the allocation and the fill are one cost.
            std::vector<float> layers;
            {
                ScopedGrainPhase _p(GrainPhase::Layers);
                layers.resize(static_cast<size_t>(npix) * 9);
                // Nine interpolations per pixel -- 112M at 12.5 MP, and the
                // largest CPU item left in this stage. Its output is the GPU
                // grain sampler's input, so the 450 MB buffer is on the wrong
                // side of the bus while this runs on the host (#223).
                gpu::FilmingStageDiagnostics gd{};
                const bool on_gpu =
                    params.allow_gpu_filming &&
                    gpu::grain_layers(density_cmy_out, static_cast<uint32_t>(npix),
                                      width, height, ndc.data(),
                                      film.density_curves_layers.data(),
                                      static_cast<uint32_t>(n), film.is_positive(),
                                      layers.data(), &gd);
                note_gpu_filming("grain_layers", gd);
                if (!on_gpu)
                    interp_density_cmy_layers(density_cmy_out, npix, ndc.data(),
                                              film.density_curves_layers.data(), n,
                                              film.is_positive(), layers.data());
            }
            // density_max_layers[sl,c] = nanmax over the log-exposure axis of the
            // RAW density_curves_layers (NOT normalized).
            double density_max_layers[9];
            for (int sl = 0; sl < 3; ++sl) {
                for (int c = 0; c < 3; ++c) {
                    double mx = -1.0;
                    bool any = false;
                    for (int k = 0; k < n; ++k) {
                        float v = film.density_curves_layers[
                            static_cast<size_t>(k) * 9 + sl * 3 + c];
                        if (std::isnan(v)) continue;
                        if (!any || v > mx) { mx = v; any = true; }
                    }
                    density_max_layers[sl * 3 + c] = any ? mx : 2.2;
                }
            }
            apply_grain_to_density_layers(layers.data(), npix, width, height,
                                          density_max_layers, params.pixel_size_um,
                                          grain, density_cmy_out);
        } else {
            // Non-sublayer path: density_max_curves[c] = nanmax(ndc[:,c]).
            for (int c = 0; c < 3; ++c) {
                double mx = -1.0;
                bool any = false;
                for (int k = 0; k < n; ++k) {
                    float v = ndc[static_cast<size_t>(k) * 3 + c];
                    if (std::isnan(v)) continue;
                    if (!any || v > mx) { mx = v; any = true; }
                }
                grain.density_max_curves[c] = any ? mx : 2.2;
            }
            apply_grain_to_density(density_cmy_out, npix, width, height,
                                   params.pixel_size_um, grain, density_cmy_out);
        }
    }
}

}  // namespace spk
