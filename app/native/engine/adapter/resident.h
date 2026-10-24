#include <vulkan/vulkan.h>
#include "runtime.h"
// GPU-resident image transport for the FILCamera film engine.
#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <tuple>
#include <vector>
namespace audit {
// Product adapter's complete film-side inputs. GPU image IDs are immutable,
// monotonically assigned leases, never addresses or recyclable Vulkan handles.
// Print-side controls deliberately do not participate in this key.
struct FilmCacheKey {
    uint64_t inputImage=0;
    uint32_t width=0,height=0,seed=0,optimizations=0,workgroup=0;
    int storage=0;
    bool relaxed=false,dirSpatial=false,filmDiffusion=false,grain=false,halation=false;
    float exposure=0,gamma=1;
    std::string profile;
    bool operator==(const FilmCacheKey& b) const {
        return std::tie(inputImage,width,height,seed,optimizations,workgroup,storage,relaxed,
                        dirSpatial,filmDiffusion,grain,halation,exposure,gamma,profile)==
               std::tie(b.inputImage,b.width,b.height,b.seed,b.optimizations,b.workgroup,b.storage,b.relaxed,
                        b.dirSpatial,b.filmDiffusion,b.grain,b.halation,b.exposure,b.gamma,b.profile);
    }
};
class Session {
    struct Impl;
    std::unique_ptr<Impl> impl;
    Session* previous=nullptr;
    friend class PaperScan;
    friend class FilmScan;
    void beginFilmScan(size_t count);
    void endFilmScan();
    unsigned chainPhase=0;
    size_t chainCount=0;
    uint64_t uploaded=0,readback=0;
    FilmCacheKey requestedFilm,cachedFilm;
    bool filmCacheEnabled_=false,filmValid=false;
    const char* filmCacheStatus="disabled";
    void beginPaperScan(size_t count);
    void endPaperScan();
public:
    Session();
    ~Session();
    Session(const Session&)=delete;
    Session& operator=(const Session&)=delete;
    std::vector<float> table, auxiliary;
    // Borrowed only for the synchronous JNI render; never retained across requests.
    void* rgbaTarget=nullptr;
    bool rgbaReady=false;
    uint64_t inputImage=0;
    int scannerWidth=0;
    float scannerSigma=0,scannerAmount=0;
    bool spatialResident=false,spatialStarted=false;
    FcGpuBufferView deviceView() const;
    void importDevice(FcGpuBufferView source,size_t bytes);
    void importBlend(FcGpuBufferView source,FcGpuBufferView filtered,size_t bytes);
    void checkpoint();
    void configureFilmCache(const FilmCacheKey* key);
    bool filmCacheEnabled() const {return filmCacheEnabled_;}
    bool reuseFilm(size_t count);
    void retainFilm(size_t count) noexcept;
    const char* filmCacheState() const {return filmCacheStatus;}
    uint64_t filmCacheBytes() const;
    void close() noexcept;
    void reset() noexcept;
    void run(const float* input,float* output,size_t count,const uint32_t* shader,size_t shaderBytes,
             float threshold,float limit,float power,uint32_t operation=0);
    uint64_t uploadBytes() const {return uploaded;}
    uint64_t readbackBytes() const {return readback;}
    bool awaitingScan(size_t count) const {return spatialResident || (chainPhase==2 || chainPhase==14) && chainCount==count;}
    bool awaitingDevelop(size_t count) const {return spatialResident || (chainPhase==12 && chainCount==count);}
    bool awaitingPaper(size_t count) const {return spatialResident || (chainPhase==13 && chainCount==count);}
    uint64_t allocationBytes() const;
    uint32_t pipelineCreates() const;
    uint32_t bufferCreates() const;
    static Session& current();
};
// A lexical lease, not a CPU-pointer cache. An incomplete scope discards GPU state.
class FilmScan {
    Session& session;
    bool complete=false;
public:
    explicit FilmScan(size_t count):session(Session::current()){session.beginFilmScan(count);}
    ~FilmScan(){if(!complete) session.close();}
    FilmScan(const FilmScan&)=delete;
    FilmScan& operator=(const FilmScan&)=delete;
    void finish(){session.endFilmScan();complete=true;}
};
class PaperScan {
    Session& session;
    bool complete=false;
public:
    explicit PaperScan(size_t count):session(Session::current()){session.beginPaperScan(count);}
    ~PaperScan(){if(!complete) session.close();}
    PaperScan(const PaperScan&)=delete;
    PaperScan& operator=(const PaperScan&)=delete;
    void finish(){session.endPaperScan();complete=true;}
};
}
