"""Build the independent, original RAW Vulkan library; no film-engine dependency."""
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import urllib.request
import tarfile

from build_support import ROOT, ndk_path, shader_tool, configure_tools, prefix_map_flags


def build():
    configure_tools()
    (ROOT/'.build').mkdir(exist_ok=True)
    jpeg_archive=ROOT/'.build/libjpeg-turbo-3.2.0.tar.gz'
    jpeg_sha='6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e'
    if not jpeg_archive.exists():
        urllib.request.urlretrieve('https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz',jpeg_archive)
    if hashlib.sha256(jpeg_archive.read_bytes()).hexdigest()!=jpeg_sha:raise RuntimeError('libjpeg-turbo archive checksum mismatch')
    # Restore the pinned unmodified sources, including all original license notices.
    with tarfile.open(jpeg_archive) as archive:archive.extractall(ROOT/'.build',filter='data')
    jpeg=ROOT/'.build/libjpeg-turbo-3.2.0'
    ndk=ndk_path()
    source=ROOT/'raw-vulkan/native'
    work=ROOT/'.build/raw-vulkan';work.mkdir(parents=True,exist_ok=True)
    jpeg_build=work/'jpeg-build'
    subprocess.run(['cmake','-S',str(jpeg),'-B',str(jpeg_build),'-G','Ninja',
        f'-DCMAKE_TOOLCHAIN_FILE={ndk.as_posix()}/build/cmake/android.toolchain.cmake','-DANDROID_ABI=arm64-v8a',
        '-DANDROID_PLATFORM=android-29',f'-DCMAKE_C_FLAGS={prefix_map_flags()}','-DCMAKE_BUILD_TYPE=Release','-DENABLE_SHARED=OFF','-DENABLE_STATIC=ON',
        '-DWITH_TURBOJPEG=OFF','-DWITH_TOOLS=OFF','-DWITH_TESTS=OFF','-DCMAKE_POSITION_INDEPENDENT_CODE=ON'],check=True)
    subprocess.run(['cmake','--build',str(jpeg_build),'--target','jpeg-static','--parallel','4'],check=True)
    subprocess.run([str(shader_tool('glslc')),'--target-env=vulkan1.1',str(source/'raw.comp'),'-o',str(work/'raw.spv')],check=True)
    spirv=(work/'raw.spv').read_bytes()
    (work/'raw_spv.h').write_text('#include <cstdint>\nstatic const uint32_t raw_spv[]={'+','.join(hex(x) for x in struct.unpack('<'+'I'*(len(spirv)//4),spirv))+'};\n')
    subprocess.run([str(shader_tool('glslc')),'--target-env=vulkan1.1','-DFC_RELAXED=1',str(source/'raw.comp'),'-o',str(work/'raw_relaxed.spv')],check=True)
    relaxed=(work/'raw_relaxed.spv').read_bytes()
    (work/'raw_relaxed_spv.h').write_text('#include <cstdint>\nstatic const uint32_t raw_relaxed_spv[]={'+','.join(hex(x) for x in struct.unpack('<'+'I'*(len(relaxed)//4),relaxed))+'};\n')
    subprocess.run([str(shader_tool('glslc')),'--target-env=vulkan1.1',str(ROOT/'gpu-runtime/meter.comp'),'-o',str(work/'meter.spv')],check=True)
    meter=(work/'meter.spv').read_bytes()
    (work/'meter_spv.h').write_text('#include <cstdint>\nstatic const uint32_t meter_spv[]={'+','.join(hex(x) for x in struct.unpack('<'+'I'*(len(meter)//4),meter))+'};\n')
    subprocess.run([str(shader_tool('glslc')),'--target-env=vulkan1.1',str(ROOT/'gpu-runtime/plane.comp'),'-o',str(work/'plane.spv')],check=True)
    plane=(work/'plane.spv').read_bytes()
    (work/'plane_spv.h').write_text('#include <cstdint>\nstatic const uint32_t plane_spv[]={'+','.join(hex(x) for x in struct.unpack('<'+'I'*(len(plane)//4),plane))+'};\n')
    subprocess.run([str(shader_tool('glslc')),'--target-env=vulkan1.1',str(ROOT/'gpu-runtime/diffusion_resample.comp'),'-o',str(work/'diffusion_resample.spv')],check=True)
    data=(work/'diffusion_resample.spv').read_bytes()
    (work/'diffusion_resample_spv.h').write_text('#include <cstdint>\nstatic const uint32_t diffusion_resample_spv[]={'+','.join(hex(x) for x in struct.unpack('<'+'I'*(len(data)//4),data))+'};\n')
    subprocess.run(['cmake','-S',str(source),'-B',str(work/'build'),'-G','Ninja',
        f'-DCMAKE_TOOLCHAIN_FILE={ndk.as_posix()}/build/cmake/android.toolchain.cmake','-DANDROID_ABI=arm64-v8a',
        '-DANDROID_PLATFORM=android-29',f'-DCMAKE_CXX_FLAGS={prefix_map_flags()}','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release',f'-DRAW_SHADER_DIR={work.as_posix()}',f'-DJPEG_SOURCE_DIR={jpeg.as_posix()}',f'-DJPEG_BUILD_DIR={jpeg_build.as_posix()}'],check=True)
    subprocess.run(['cmake','--build',str(work/'build'),'--parallel','4'],check=True)
    dest=work/'jniLibs/arm64-v8a/librawvulkan.so';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(work/'build/librawvulkan.so',dest)
    shutil.copy2(work/'build/gpu-runtime/libfilmcamera_gpu.so',dest.parent/'libfilmcamera_gpu.so')
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    manifest=dict(librarySha256=sha(dest),shaderSha256=sha(work/'raw.spv'),sources={p.relative_to(ROOT).as_posix():sha(p) for p in (ROOT/'raw-vulkan').rglob('*') if p.is_file() and 'build' not in p.relative_to(ROOT/'raw-vulkan').parts})
    manifest['runtimeSha256']=sha(dest.parent/'libfilmcamera_gpu.so')
    manifest['sources'].update({p.relative_to(ROOT).as_posix():sha(p) for p in (ROOT/'gpu-runtime').iterdir() if p.is_file()})
    (work/'manifest.json').write_text(json.dumps(manifest,indent=2))
    assets=work/'assets/raw-vulkan';assets.mkdir(parents=True,exist_ok=True)
    (assets/'engine.json').write_text(json.dumps(manifest,indent=2))
    manifest['losslessJpeg']=dict(version='3.2.0',archiveSha256=jpeg_sha)
    (work/'manifest.json').write_text(json.dumps(manifest,indent=2))
    (assets/'engine.json').write_text(json.dumps(manifest,indent=2))
    for name in ('LICENSE.md','README.ijg'):
        shutil.copy2(jpeg/name,assets/name)
    return manifest

if __name__=='__main__':build()
