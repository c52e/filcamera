#include "runtime.h"
// FILCamera JNI bridge for the GPL film engine.
#include <jni.h>
#include <resident.h>
#include "spektra.h"
#include "film_spatial.h"
#include "gpu/density_storage.h"
#include "runtime/memory_budget.h"
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>
#include <cstdint>
#include <set>
namespace {
std::mutex cancellationMutex;
std::set<long long> cancelledRenders;
std::atomic<long long> cancelledBakeThrough{0};
std::atomic<long long> activeBakeRequest{0};
std::atomic<long long> activeRenderRequest{0};
struct RenderScope {
    long long id;
    explicit RenderScope(long long value):id(value){spk::lab_stage="Preparing film parameters";activeRenderRequest=id;}
    ~RenderScope(){activeRenderRequest=0;std::lock_guard<std::mutex> lock(cancellationMutex);cancelledRenders.erase(id);}
};
std::once_flag environment;
struct Context {
    audit::Session gpu; // Created/destroyed only on the owner worker thread.
    std::unique_ptr<spk_engine,decltype(&spk_engine_destroy)> engine{nullptr,spk_engine_destroy};
    std::string assets;
    ~Context(){spk::gpu::lab_close_gpu();}
    explicit Context(const std::string& path):assets(path) {
        spk_engine* raw=nullptr;auto status=spk_engine_create(path.c_str(),&raw);engine.reset(raw);
        if(status!=SPK_OK || !raw) throw std::runtime_error("Cannot load film profiles");
    }
};
thread_local std::unique_ptr<Context> context;
struct Utf {
    JNIEnv* env;jstring value;const char* bytes;
    Utf(JNIEnv* e,jstring v):env(e),value(v),bytes(v?e->GetStringUTFChars(v,nullptr):nullptr){if(!bytes) throw std::runtime_error("Missing path");}
    ~Utf(){env->ReleaseStringUTFChars(value,bytes);}
};
spk_params modelParams(float ev, bool compensatePrint, bool autoExposure, float gamma, float printEv, float contrast, float magenta, float yellow, const char* filmProfile, const char* paperProfile) {
        if(!std::isfinite(ev) || std::abs(ev)>2 || !std::isfinite(gamma) || gamma<.5f || gamma>1.5f ||
           !std::isfinite(printEv) || std::abs(printEv)>3 || !std::isfinite(contrast) || contrast<.5f || contrast>2 ||
           !std::isfinite(magenta) || std::abs(magenta)>30 || !std::isfinite(yellow) || std::abs(yellow)>30)
            throw std::runtime_error("Recipe parameter outside supported range");
        const std::vector<std::string> films={"kodak_portra_400","kodak_portra_160","kodak_portra_800","kodak_portra_800_push1","kodak_portra_800_push2","kodak_ektar_100","kodak_gold_200","kodak_ultramax_400","fujifilm_pro_400h","fujifilm_c200","fujifilm_xtra_400"};
        const std::vector<std::string> papers={"kodak_portra_endura","kodak_supra_endura","kodak_ultra_endura","kodak_endura_premier","kodak_ektacolor_edge","fujifilm_crystal_archive_typeii"};
        if(std::find(films.begin(),films.end(),filmProfile)==films.end() || std::find(papers.begin(),papers.end(),paperProfile)==papers.end())
            throw std::runtime_error("Unsupported film or paper profile");
        spk_params p{};spk_default_params(&p);
        p.disable_buffer_memos=1;p.film_profile=filmProfile;p.print_profile=paperProfile;
        p.input_color_space="ProPhoto RGB";p.input_cctf_decoding=0;p.auto_exposure=autoExposure?1:0;p.exposure_compensation_ev=ev;
        p.print_exposure=1;p.normalize_print_exposure=1;p.print_exposure_compensation=compensatePrint?1:0;
        p.grain_active=0;p.halation_active=0;p.glare_active=0;p.dir_diffusion_size_um=0;
        p.scanner_unsharp[0]=p.scanner_unsharp[1]=0;p.scan_film=0;p.output_color_space=SPK_CS_SRGB;p.output_cctf_encoding=0;
        p.input_gamut_compress=1;p.output_gamut_compress=6;p.print_morph_active=1;
        p.gpu_preview=0;p.gpu_export=0;p.allow_gpu_scan=0;p.use_enlarger_lut=0;p.use_scanner_lut=0;
        p.crop=0;p.upscale_factor=1;
        p.density_curve_gamma=gamma;p.print_exposure=std::exp2(printEv);
        p.print_morph_gamma_factor=contrast;p.m_filter_shift=magenta;p.y_filter_shift=yellow;
        return p;
}
int cancelled(void* id){std::lock_guard<std::mutex> lock(cancellationMutex);return cancelledRenders.count(*static_cast<long long*>(id))!=0;}
int bakeCancelled(void* id){return *static_cast<long long*>(id)<=cancelledBakeThrough.load();}
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_cancelBake(JNIEnv*,jobject,jlong id) {
    auto old=cancelledBakeThrough.load();while(old<id && !cancelledBakeThrough.compare_exchange_weak(old,id)){}
}
extern "C" JNIEXPORT jlong JNICALL Java_io_github_c52e_filcamera_FilmEngine_activeBakeRequest(JNIEnv*,jobject) {
    return activeBakeRequest.load();
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_c52e_filcamera_FilmEngine_bakeNative(
    JNIEnv* env,jobject,jstring assets,jlong request,jstring target,jfloat ev,jboolean compensatePrint,jfloat gamma,jfloat printEv,jfloat contrast,jfloat magenta,jfloat yellow,jstring filmProfile,jstring paperProfile) {
    try {
        Utf a(env,assets),t(env,target),f(env,filmProfile),paper(env,paperProfile);long long id=request;
        if(id<=0 || std::filesystem::exists(t.bytes)) throw std::runtime_error("Invalid bake request or occupied output path");
        struct OutputGuard { std::string path;bool committed=false;~OutputGuard(){if(!committed){std::error_code ec;std::filesystem::remove(path,ec);}} } guard{t.bytes};
        const auto start=std::chrono::steady_clock::now();
        if(bakeCancelled(&id)) return env->NewStringUTF("{\"cancelled\":true}");
        struct ActiveGuard {
            long long id;
            explicit ActiveGuard(long long value):id(value){activeBakeRequest.store(id);}
            ~ActiveGuard(){auto expected=id;activeBakeRequest.compare_exchange_strong(expected,0);}
        } active{id};
        constexpr int n=65,count=n*n*n;
        constexpr double m[9]={.5166701867968089,.3338387768040661,.1497350135838748,
            .09086585594167676,.8660848117382414,.04298256592271297,
            .01513186629596188,.10112047717856135,.8839682133619462};
        double axis[n];for(int i=0;i<n;i++) axis[i]=std::expm1(double(i)/64*std::log(129.0))/32;
        std::vector<float> grid(count*3);
        for(int b=0;b<n;b++) { if(bakeCancelled(&id)) return env->NewStringUTF("{\"cancelled\":true}");
            for(int g=0;g<n;g++)for(int r=0;r<n;r++)for(int c=0;c<3;c++)
                grid[((b*n+g)*n+r)*3+c]=float(m[c*3]*axis[r]+m[c*3+1]*axis[g]+m[c*3+2]*axis[b]);
        }
        std::call_once(environment,[]{setenv("FILMCAMERA_GPU_CAM16","1",1);});
        if(!context || context->assets!=a.bytes){context.reset();context=std::make_unique<Context>(a.bytes);}
        auto p=modelParams(ev,compensatePrint,false,gamma,printEv,contrast,magenta,yellow,f.bytes,paper.bytes);
        spk_image input{grid.data(),845,325,SPK_CS_PROPHOTO},out{};
        struct Release {spk_image* image;~Release(){spk_image_free(image);}} release{&out};
        const auto renderStart=std::chrono::steady_clock::now();
        auto status=spk_simulate_cancellable(context->engine.get(),&input,&p,&out,bakeCancelled,&id);
        const double renderMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-renderStart).count();
        if(status==SPK_ERR_CANCELLED || bakeCancelled(&id)) { context.reset();return env->NewStringUTF("{\"cancelled\":true}"); }
        if(status!=SPK_OK || !out.data || out.width!=845 || out.height!=325) throw std::runtime_error("LUT render failed");
        std::vector<uint16_t> table(count*4);
        for(int i=0;i<count;i++) {
            if((i%4096)==0 && bakeCancelled(&id)) {context.reset();return env->NewStringUTF("{\"cancelled\":true}");}
            for(int c=0;c<4;c++) {
                float v=1;
                if(c<3) {
                    const float x=out.data[i*3+c];if(!std::isfinite(x)) throw std::runtime_error("Nonfinite LUT sample");
                    v=std::clamp(x<=.0031308f?12.92f*x:1.055f*std::pow(x,1.f/2.4f)-.055f,0.f,1.f);
                }
                __fp16 half=static_cast<__fp16>(v);std::memcpy(&table[i*4+c],&half,2);
            }
        }
        std::ofstream file(t.bytes,std::ios::binary);file.write(reinterpret_cast<const char*>(table.data()),table.size()*2);file.close();
        if(!file) throw std::runtime_error("Cannot write LUT");
        if(bakeCancelled(&id)){context.reset();return env->NewStringUTF("{\"cancelled\":true}");}
        const double total=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        std::string json="{\"cancelled\":false,\"dimension\":65,\"bytes\":2197000,\"engine\":\"resident-pointwise-v1\",\"renderMs\":"+
            std::to_string(renderMs)+",\"totalMs\":"+std::to_string(total)+",\"gpuAllocationBytes\":"+std::to_string(context->gpu.allocationBytes())+
            ",\"relaxedMath\":"+(fc_gpu_relaxed()?"true":"false")+",\"lutStorage\":\"rgba16f\",\"workgroup\":"+std::to_string(fc_gpu_workgroup())+"}";
        auto result=env->NewStringUTF(json.c_str());if(result) guard.committed=true;return result;
    } catch(const std::exception& e) {context.reset();if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return nullptr;}
    catch(...) {context.reset();env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"LUT bake failure");return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_cancel(JNIEnv*,jobject,jlong id) {
    std::lock_guard<std::mutex> lock(cancellationMutex);
    // Exact request ownership: cancelling an edit must not cancel an older RAW render.
    cancelledRenders.insert(id);
    // Requests skipped before JNI entry never create a RenderScope. Keep bounded
    // tombstones for them while always preserving cancellation of the active job.
    while(cancelledRenders.size()>256) {
        auto it=cancelledRenders.begin();if(*it==activeRenderRequest.load()) ++it;
        cancelledRenders.erase(it);
    }
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_close(JNIEnv*,jobject){context.reset();}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_c52e_filcamera_FilmEngine_renderStage(JNIEnv* env,jobject,jlong id){
    return env->NewStringUTF(activeRenderRequest.load()==id?spk::lab_stage.load():"");
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_c52e_filcamera_FilmEngine_memorySnapshot(JNIEnv* env,jobject){
    return env->NewStringUTF(spk::memory::memory_budget_snapshot_json(spk::memory::process_memory_budget().snapshot()).c_str());
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_testGpuFailure(JNIEnv*,jobject,jboolean enabled){
    if(enabled) setenv("FILMCAMERA_GPU_RESIDENT_FAIL","1",1);else unsetenv("FILMCAMERA_GPU_RESIDENT_FAIL");
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_testResidentEngine(JNIEnv*,jobject,jboolean enabled){
    if(enabled) setenv("FILMCAMERA_RESIDENT_TEST","1",1);else unsetenv("FILMCAMERA_RESIDENT_TEST");
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_testOptimizations(JNIEnv*,jobject,jint mask){fc_gpu_set_optimizations(uint32_t(mask));}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_testStorage(JNIEnv*,jobject,jint mode){fc_gpu_set_storage(mode);}
extern "C" JNIEXPORT jint JNICALL Java_io_github_c52e_filcamera_FilmEngine_testStorageCapabilities(JNIEnv*,jobject){
    auto* g=fc_gpu_acquire();if(!g) return 0;int bits=1|(g->storage16?2:0);
#ifdef SPK_STORAGE_PROBES
    if(g->r16Image) bits|=4;
#endif
    fc_gpu_release(g);return bits;
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_testRelaxed(JNIEnv*,jobject,jboolean enabled){fc_gpu_set_relaxed(enabled);}
extern "C" JNIEXPORT jint JNICALL Java_io_github_c52e_filcamera_FilmEngine_testDensityGuards(JNIEnv* env,jobject) {
    auto* g=fc_gpu_acquire();if(!g) {env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),fc_gpu_error());return 0;}
    int rejected=0;
    try {
        fc::Buffer frame(g,16*16*12,false);
        for(int mode=0;mode<=(g->storage16?1:0);++mode) {
            spk::gpu::DensityStorage plane(g,16,16,mode);
            const auto view=plane.view();
            if(view.owner!=g || view.layers!=9 || view.kind!=FC_GPU_BUFFER || view.element!=(mode?FC_GPU_F16:FC_GPU_F32)) throw std::runtime_error("Density resource identity mismatch");
            float params[17]{},table[4]{};
            auto reject=[&](FcGpuBufferView input) {
                try {plane.layers(input,table,4,params,sizeof(params));}
                catch(const std::runtime_error&) {++rejected;return;}
                throw std::runtime_error("Invalid density view accepted");
            };
            reject({g,frame.buffer,frame.bytes,FC_GPU_F16});
            reject({nullptr,frame.buffer,frame.bytes});
            reject({g,frame.buffer,4});
            try {plane.sample({g,frame.buffer,frame.bytes},table,4,params,36);throw std::logic_error("Uninitialized density accepted");}
            catch(const std::runtime_error&) {++rejected;}
        }
    } catch(const std::exception& e) {env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
    fc_gpu_release(g);return rejected;
}
extern "C" JNIEXPORT void JNICALL Java_io_github_c52e_filcamera_FilmEngine_testWorkgroup(JNIEnv*,jobject,jint size,jint rawX,jint rawY){fc_gpu_set_workgroup(size,rawX,rawY);}
extern "C" JNIEXPORT jlong JNICALL Java_io_github_c52e_filcamera_FilmEngine_liveGpuImages(JNIEnv*,jobject){return jlong(fc_gpu_live_images());}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_c52e_filcamera_FilmEngine_renderNative(
    JNIEnv* env,jobject,jstring assets,jstring source,jint width,jint height,jfloat ev,jlong request,jstring target,jboolean compensatePrint,jfloat gamma,jfloat printEv,jfloat contrast,jfloat magenta,jfloat yellow,jstring filmProfile,jstring paperProfile,jboolean dirSpatial,jboolean filmDiffusion,jboolean printDiffusion,jboolean grain,jboolean halation,jlong seed,jobject rgba,jobject memoryInput,jlong gpuImage) {
    long long id=request;RenderScope progress(id);
    try {
        fc_gpu_reset_counts();
        Utf a(env,assets),s(env,source),t(env,target),f(env,filmProfile),paper(env,paperProfile);
        if(width<1 || height<1 || width>16384 || height>16384 || size_t(width)*height>16000000 || !std::isfinite(ev) || std::abs(ev)>2 || id<=0)
            throw std::runtime_error("Unsupported dimensions or exposure");
        if(cancelled(&id)) return env->NewStringUTF("{\"cancelled\":true}");
        size_t count=size_t(width)*height*3;
        std::vector<float> filePixels;
        float* pixels=nullptr;
        const FcGpuImage* deviceInput=gpuImage?fc_gpu_image(uint64_t(gpuImage),nullptr):nullptr;
        if(gpuImage && (!deviceInput || deviceInput->width!=uint32_t(width) || deviceInput->height!=uint32_t(height) || deviceInput->color!=1))
            throw std::runtime_error("Invalid GPU ProPhoto image handle or shape");
        if(deviceInput) pixels=static_cast<float*>(deviceInput->mapped);
        else if(memoryInput) {
            pixels=static_cast<float*>(env->GetDirectBufferAddress(memoryInput));
            if(!pixels || env->GetDirectBufferCapacity(memoryInput)!=jlong(count*sizeof(float)))
                throw std::runtime_error("ProPhoto input requires exact-sized direct buffer");
        } else {
            if(std::filesystem::file_size(s.bytes)!=count*sizeof(float)) throw std::runtime_error("Input byte size mismatch");
            filePixels.resize(count);std::ifstream input(s.bytes,std::ios::binary);
            if(!input.read(reinterpret_cast<char*>(filePixels.data()),count*sizeof(float))) throw std::runtime_error("Cannot read input");
            pixels=filePixels.data();
        }
        if(!deviceInput) for(size_t i=0;i<count;i++) if(!std::isfinite(pixels[i])) throw std::runtime_error("Nonfinite input");
        std::call_once(environment,[]{setenv("FILMCAMERA_GPU_CAM16","1",1);});
        if(!context || context->assets!=a.bytes){context.reset();context=std::make_unique<Context>(a.bytes);}
        void* packed=rgba?env->GetDirectBufferAddress(rgba):nullptr;
        if(rgba && (!packed || env->GetDirectBufferCapacity(rgba)!=jlong(width)*height*4))
            throw std::runtime_error("RGBA output requires exact-sized direct buffer");
        struct PackedScope {
            audit::Session& gpu;
            PackedScope(audit::Session& g,void* p):gpu(g){gpu.rgbaTarget=p;gpu.rgbaReady=false;}
            ~PackedScope(){gpu.rgbaTarget=nullptr;gpu.rgbaReady=false;gpu.inputImage=0;}
        } packedScope(context->gpu,packed);
        context->gpu.inputImage=uint64_t(gpuImage);
        auto p=modelParams(ev,compensatePrint,true,gamma,printEv,contrast,magenta,yellow,f.bytes,paper.bytes);
        if(seed<0 || seed>0xffffffffLL) throw std::runtime_error("Invalid grain seed");
        if(!dirSpatial && (filmDiffusion || printDiffusion || grain || halation)) throw std::runtime_error("Invalid spatial policy");
        spk::lab_grain_seed=uint32_t(seed);
        p.film_format_mm=36;
        // Pinned Python ScannerParams default. LUT generation keeps this off.
        p.scanner_unsharp[0]=p.scanner_unsharp[1]=.7f;
        if(dirSpatial) {
            p.film_format_mm=36;p.dir_couplers_active=1;p.dir_amount=1;
            p.dir_inhibition_samelayer=1;p.dir_inhibition_interlayer=1;
            p.dir_diffusion_size_um=20;p.dir_diffusion_tail_um=200;p.dir_diffusion_tail_weight=.03f;
            p.grain_active=grain;p.grain_blur=.65f;p.grain_blur_dye_clouds_um=1;
            // Original pinned Python defaults; upstream Android's older C defaults differ.
            p.grain_particle_area_um2=.2f;
            p.grain_particle_scale[0]=1.6f;p.grain_particle_scale[1]=1.6f;p.grain_particle_scale[2]=3.2f;
            p.grain_particle_scale_layers[0]=2;p.grain_particle_scale_layers[1]=1;p.grain_particle_scale_layers[2]=.5f;
            p.grain_density_min[0]=p.grain_density_min[1]=p.grain_density_min[2]=.03f;
            p.grain_uniformity[0]=.97f;p.grain_uniformity[1]=.99f;p.grain_uniformity[2]=.97f;
            p.halation_active=halation;p.halation_scatter_amount=1;p.halation_scatter_spatial_scale=1;
            p.halation_halation_amount=1;p.halation_halation_spatial_scale=1;
            p.camera_diffusion_active=filmDiffusion;p.enlarger_diffusion_active=printDiffusion;
            p.camera_diffusion_strength=p.enlarger_diffusion_strength=.5f;
            p.camera_diffusion_spatial_scale=p.enlarger_diffusion_spatial_scale=1;
            p.camera_diffusion_halo_warmth=p.enlarger_diffusion_halo_warmth=0;
            p.camera_diffusion_family=p.enlarger_diffusion_family=0;
        }
        p.print_glare_active=0;

        spk_image in{pixels,width,height,SPK_CS_PROPHOTO},out{};
        struct Release {spk_image* image;~Release(){spk_image_free(image);}} release{&out};
        auto start=std::chrono::steady_clock::now();
        auto status=spk_simulate_cancellable(context->engine.get(),&in,&p,&out,cancelled,&id);
        double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        if(status==SPK_ERR_CANCELLED || cancelled(&id)){context->gpu.close();return env->NewStringUTF("{\"cancelled\":true}");}
        if(status!=SPK_OK) throw std::runtime_error(std::string("Render failed: ")+spk_last_error_message());
        if((!packed && !out.data) || out.width!=width || out.height!=height) throw std::runtime_error("Output shape mismatch");
        if(packed) {
            if(!context->gpu.rgbaReady) throw std::runtime_error("GPU packed output was not produced");
        } else {
            for(size_t i=0;i<count;++i) if(!std::isfinite(out.data[i])) throw std::runtime_error("Nonfinite output");
            std::ofstream file(t.bytes,std::ios::binary|std::ios::trunc);
            file.write(reinterpret_cast<char*>(out.data),count*sizeof(float));file.close();
            if(!file) {std::filesystem::remove(t.bytes);throw std::runtime_error("Output write failed");}
        }
        if(cancelled(&id)){if(!packed) std::filesystem::remove(t.bytes);context->gpu.close();return env->NewStringUTF("{\"cancelled\":true}");}
        std::string result="{\"cancelled\":false,\"filmEv\":"+std::to_string(ev)+",\"printExposureCompensation\":"+(compensatePrint?"true":"false")+",\"renderMs\":"+std::to_string(ms)+",\"gpuAllocationBytes\":"+std::to_string(context->gpu.allocationBytes())+"}";
        result.pop_back();result+=",\"outputTransport\":\"";
        result+=packed?"gpu-fused-srgb-rgba8":"linear-srgb-f32";result+="\"}";
        char timings[32768]{};
        if(spk_stage_timings_json(timings,sizeof(timings))>0) {result.pop_back();result+=",\"stageTimings\":";result+=timings;result+="}";}
        const auto transfers=fc_gpu_counts();
        result.pop_back();result+=",\"gpuTransfers\":{\"imageUploadBytes\":"+std::to_string(transfers.imageUpload)+
            ",\"optimizationMask\":"+std::to_string(fc_gpu_optimizations())+
            ",\"storageMode\":"+std::to_string(fc_gpu_selected_storage())+",\"relaxedMath\":"+(fc_gpu_relaxed()?"true":"false")+
            ",\"workgroup\":"+std::to_string(fc_gpu_workgroup())+
            ",\"intermediateReadbackBytes\":"+std::to_string(transfers.intermediateReadback)+",\"outputReadbackBytes\":"+std::to_string(transfers.outputReadback)+
            ",\"statisticsReadbackBytes\":"+std::to_string(transfers.statisticsReadback)+",\"constantsUploadBytes\":"+std::to_string(transfers.constantsUpload)+
            ",\"deviceCopyBytes\":"+std::to_string(transfers.deviceCopy)+",\"gpuWaitMs\":"+std::to_string(transfers.waitNs/1e6)+
            ",\"dispatchCount\":"+std::to_string(transfers.dispatchCount)+",\"submitCount\":"+std::to_string(transfers.submitCount)+",\"waitCount\":"+std::to_string(transfers.waitCount)+
            ",\"gpuTimestampMs\":"+std::to_string(transfers.gpuNs/1e6)+",\"timedSubmissions\":"+std::to_string(transfers.timedSubmissionCount)+
            ",\"allocationCount\":"+std::to_string(transfers.allocationCount)+",\"currentAllocationBytes\":"+std::to_string(transfers.currentAllocationBytes)+
            ",\"peakAllocationBytes\":"+std::to_string(transfers.peakAllocationBytes)+"}}";
        if(deviceInput && packed && (transfers.imageUpload || transfers.intermediateReadback)) throw std::runtime_error("Unexpected host transfer in resident render");
        return env->NewStringUTF(result.c_str());
    }catch(const std::exception& e){context.reset();if(cancelled(&id)) return env->NewStringUTF("{\"cancelled\":true}");if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return nullptr;}
    catch(...){context.reset();if(cancelled(&id)) return env->NewStringUTF("{\"cancelled\":true}");env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Native render failure");return nullptr;}
}

