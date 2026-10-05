// Original JNI adapter. libjpeg-turbo is used only to unpack lossless RAW samples.
#include <jni.h>
#include <cstdio>
#include <csetjmp>
#include <cstdint>
#include <memory>
#include <vector>
#include <stdexcept>
extern "C" {
#include <jpeglib.h>
}
namespace {
struct Error {jpeg_error_mgr api{};jmp_buf jump;char message[JMSG_LENGTH_MAX]{};};
void fail(j_common_ptr c) {
    auto* e=reinterpret_cast<Error*>(c->err);
    c->err->format_message(c,e->message);longjmp(e->jump,1);
}
void warning(j_common_ptr c,int level) {if(level<0) fail(c);}
struct Decoder {
    jpeg_decompress_struct c{};Error error{};
    ~Decoder(){jpeg_destroy_decompress(&c);}
};
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_c52e_filcamera_rawvulkan_DngRawFile_decodeJpeg(
    JNIEnv* env,jobject,jbyteArray source,jint width,jint height) {
    try {
        if(!source || width<1 || height<1 || width>4096 || height>4096 || int64_t(width)*height>4194304)
            throw std::runtime_error("Unsupported DNG tile dimensions");
        const auto bytes=env->GetArrayLength(source);
        if(bytes<4 || bytes>16*1024*1024) throw std::runtime_error("Invalid DNG JPEG tile size");
        std::vector<unsigned char> input(bytes);
        std::vector<uint16_t> output(size_t(width)*height);
        env->GetByteArrayRegion(source,0,bytes,reinterpret_cast<jbyte*>(input.data()));
        if(env->ExceptionCheck()) return nullptr;
        auto d=std::make_unique<Decoder>();
        d->c.err=jpeg_std_error(&d->error.api);d->error.api.error_exit=fail;d->error.api.emit_message=warning;
        if(setjmp(d->error.jump)) throw std::runtime_error(d->error.message);
        jpeg_create_decompress(&d->c);
        jpeg_mem_src(&d->c,input.data(),input.size());
        if(jpeg_read_header(&d->c,TRUE)!=JPEG_HEADER_OK || d->c.data_precision!=16 ||
           d->c.num_components<1 || d->c.num_components>4 ||
           uint64_t(d->c.image_width)*d->c.num_components!=uint64_t(width) || d->c.image_height!=uint32_t(height) ||
           d->c.Ss<1 || d->c.Ss>7 || d->c.Se!=0 || d->c.Ah!=0 || d->c.Al!=0)
            throw std::runtime_error("Expected 16-bit lossless Bayer JPEG tile");
        for(int i=0;i<d->c.num_components;++i)
            if(d->c.comp_info[i].h_samp_factor!=1 || d->c.comp_info[i].v_samp_factor!=1)
                throw std::runtime_error("Subsampled RAW JPEG is unsupported");
        // Bayer samples are components, not RGB. No color transform or scaling.
        d->c.jpeg_color_space=JCS_UNKNOWN;d->c.out_color_space=JCS_UNKNOWN;
        if(!jpeg_start_decompress(&d->c) || d->c.output_width*d->c.output_components!=uint32_t(width) || d->c.output_height!=uint32_t(height))
            throw std::runtime_error("RAW JPEG output shape mismatch");
        while(d->c.output_scanline<d->c.output_height) {
            J16SAMPROW row=output.data()+size_t(d->c.output_scanline)*width;
            if(jpeg16_read_scanlines(&d->c,&row,1)!=1) throw std::runtime_error("Truncated RAW JPEG");
        }
        if(!jpeg_finish_decompress(&d->c)) throw std::runtime_error("Incomplete RAW JPEG");
        auto result=env->NewByteArray(jint(output.size()*2));
        if(result) env->SetByteArrayRegion(result,0,jint(output.size()*2),reinterpret_cast<const jbyte*>(output.data()));
        return result;
    } catch(const std::exception& e) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),e.what());
        return nullptr;
    }
}
