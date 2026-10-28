#include "compute.h"
#include <numeric>
#include "bitmap_spv.h"
#include <android/bitmap.h>
#include <jni.h>
#include <cmath>

namespace {
void require(bool condition,const char* message) {if(!condition) throw std::runtime_error(message);}
struct Context {
    FcGpuContext* gpu=fc_gpu_acquire();
    Context() {require(gpu,fc_gpu_error());}
    ~Context() {fc_gpu_release(gpu);}
};
struct Pixels {
    JNIEnv* env;jobject bitmap;void* data=nullptr;
    Pixels(JNIEnv* e,jobject b):env(e),bitmap(b) {require(AndroidBitmap_lockPixels(e,b,&data)==ANDROID_BITMAP_RESULT_SUCCESS,"Cannot lock bitmap pixels");}
    ~Pixels() {AndroidBitmap_unlockPixels(env,bitmap);}
};
}
extern "C" JNIEXPORT jlong JNICALL Java_io_github_c52e_filcamera_rawvulkan_VulkanRawDeveloper_convertBitmap(JNIEnv* env,jclass,jobject bitmap,jfloatArray matrix,jobject readback) {
    try {
        fc_gpu_clear_memory_error();
        AndroidBitmapInfo info{};
        require(AndroidBitmap_getInfo(env,bitmap,&info)==ANDROID_BITMAP_RESULT_SUCCESS,"Cannot read bitmap header");
        require(info.width>0 && info.height>0 && info.width<=16384 && info.height<=16384 && uint64_t(info.width)*info.height<=FC_MAX_PIXELS,"Unsupported bitmap dimensions");
        const bool half=info.format==ANDROID_BITMAP_FORMAT_RGBA_F16;
        require(half || info.format==ANDROID_BITMAP_FORMAT_RGBA_8888,"Bitmap conversion requires RGBA8888 or RGBAF16");
        require(info.stride%4==0 && uint64_t(info.stride)>=uint64_t(info.width)*(half?8:4),"Invalid bitmap stride");
        require(matrix && env->GetArrayLength(matrix)==9,"Invalid ProPhoto matrix");
        float m[9];env->GetFloatArrayRegion(matrix,0,9,m);if(env->ExceptionCheck()) return 0;
        for(float v:m) require(std::isfinite(v),"Nonfinite ProPhoto matrix");
        const uint64_t bytes=uint64_t(info.width)*info.height*12;
        void* destination=readback?env->GetDirectBufferAddress(readback):nullptr;
        require(!readback || (destination && env->GetDirectBufferCapacity(readback)==int64_t(bytes)),"Invalid bitmap test readback");
        Context context;auto* gpu=context.gpu;
        fc::Buffer output(gpu,bytes,true),source(gpu,uint64_t(info.stride)*info.height,true),status(gpu,4,true);
        {Pixels pixels(env,bitmap);std::memcpy(source.mapped,pixels.data,size_t(source.bytes));}
        *static_cast<uint32_t*>(status.mapped)=0;
        struct Push {uint32_t width,height,stride,half;float r[4],g[4],b[4];} push{info.width,info.height,info.stride/4,uint32_t(half),{m[0],m[1],m[2],0},{m[3],m[4],m[5],0},{m[6],m[7],m[8],0}};
        {
            fc::Compute compute(gpu,bitmap_spv,sizeof(bitmap_spv),3,sizeof(push));
            const uint64_t alignment=std::max<VkDeviceSize>(1,gpu->properties.limits.minStorageBufferOffsetAlignment);
            const uint64_t rowStep=std::lcm(alignment/std::gcd(alignment,uint64_t(info.stride)),
                                           alignment/std::gcd(alignment,uint64_t(info.width)*12));
            const uint32_t rows=uint32_t((4u*1024u*1024u/info.width)/rowStep*rowStep);
            require(rows>0,"Bitmap chunk alignment unsupported");
            for(uint32_t row=0;row<info.height;row+=rows) {
                push.height=std::min(rows,info.height-row);
                VkDescriptorBufferInfo src{source.buffer,uint64_t(row)*info.stride,uint64_t(push.height)*info.stride};
                VkDescriptorBufferInfo dst{output.buffer,uint64_t(row)*info.width*12,uint64_t(push.height)*info.width*12};
                compute.run({src,dst,status.descriptor()},&push,sizeof(push),info.width*push.height);
            }
        }
        fc_gpu_count(FC_IMAGE_UPLOAD,source.bytes);fc_gpu_count(FC_STATISTIC_READBACK,4);
        require(*static_cast<uint32_t*>(status.mapped)==0,"Nonfinite bitmap input");
        if(destination) {std::memcpy(destination,output.mapped,size_t(bytes));fc_gpu_count(FC_INTERMEDIATE_READBACK,bytes);}
        FcGpuImage image{output.buffer,output.memory,output.mapped,output.bytes,info.width,info.height,1};
        const uint64_t id=fc_gpu_adopt(gpu,&image);require(id!=0,fc_gpu_error());
        output.buffer={};output.memory={};output.mapped=nullptr;
        return static_cast<jlong>(id);
    } catch(const std::bad_alloc&) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),"GPU bitmap conversion allocation failed");
    } catch(const std::exception& e) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass(fc_gpu_memory_failed()?"java/lang/OutOfMemoryError":"java/lang/IllegalStateException"),e.what());
    }
    return 0;
}
