# RAW Vulkan

Original implementation of the repository's directional-difference RAW developer. This Android library depends on `imaging`, Vulkan and the permissively licensed libjpeg-turbo codec, without a film-engine or profile-data dependency. Its original code is now licensed GPL-3.0-only with the rest of FILCamera. FILCamera consumes it through the shared worker-owned GPU runtime.

Build `tools/build_raw_vulkan.py` with Python 3.12+, NDK r28c, CMake and Ninja. `tools/build.py` invokes it automatically. Generated SPIR-V, the shared library and a source/binary manifest live under `.build/raw-vulkan`. Only arm64-v8a is packaged by the current build script.

`VulkanRawDeveloper` implements `RawDeveloper`. Input semantics match the CPU implementation, including buffer position, byte strides, Bayer parity relative to the full sensor, active crop, black/white levels, gains and shading. Production uses `developGpuProPhoto()`: its read-only, worker-owned image handle stays in the shared Vulkan device until the caller closes it. `develop()` and `developProPhoto()` retain explicit host readback for reference tests. CAT16 ProPhoto conversion, center crop and orientation preserve negative and above-one values.

The resize path has five FP32 passes: CFA calibration, directional green, bounded color-difference interpolation, horizontal area resize, then vertical resize/color/ProPhoto/orientation. When every area weight is exactly one, the last three become one pass, giving three dispatches. Calibration and green still cover the complete active RAW neighborhood. CPU builds the original double-precision area weights; shaders preserve FP32 rounding. Both paths record all passes in one command buffer with compute barriers. The final pass flags nonfinite output on the GPU.

A call borrows the worker's shared Vulkan runtime and owns its pipeline, seven buffer bindings, command pool and fence. Native-resolution processing binds small placeholders for the two unused image intermediates. Only the completed output allocation transfers to the checked image registry; other RAW allocations are released before film processing. No CPU image fallback or mutation of camera/RAW input is permitted. Failed captures retain the independently saved DNG.

Limits: Vulkan 1.1, seven compute storage buffers, 256-thread workgroups, host-visible coherent memory, up to 60 MP active input and output, plus device buffer/allocation limits. Editing may request a smaller output; final resolution is never silently reduced. This is a full-frame pipeline, not a tiled RAW algorithm.

Diagnostics report actual dispatch/submission/wait counts, CPU wait time and command-buffer GPU timestamps when the compute queue supports them. `batchSubmitWaitMs` includes recording/submission/wait overhead and is not a hardware GPU measurement. Timestamps include the entire command buffer, including barriers. See `docs/TESTING.md` for qualification boundaries.

The strict reference retains its original linear max/RMS checks against `EdgeDirectedRawDeveloper`. The 0.18 relaxed arithmetic candidate is qualified in display space (RMS <= 1, p99 <= 3, max <= 16 in 8-bit code units), with separate spatial and grain checks; see `docs/TESTING.md`. RAW calibration and scene-linear storage remain FP32. Synthetic CFA/stride/crop/edge/impulse and real RAW inputs also cover repeatability, invalid input, overflow, interruption and recovery.

## DNG input

`DngRawFile` supports the app-authored `filmcamera-raw-v1` metadata stored in standard DNG ImageDescription. Calibration, crop, CFA, orientation, recipe and grain seed travel inside the DNG. It never reads the receiving device camera calibration. TIFF strips/tiles with uncompressed 16-bit samples or lossless 16-bit JPEG are decoded; other inputs fail explicitly. This is not a universal third-party DNG importer.

Lossless entropy decoding runs on the CPU with unmodified libjpeg-turbo 3.2.0 (official archive SHA-256 `6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e`). Raw calibration/demosaic/resize remain on Vulkan. Only the libjpeg API is linked, with no TurboJPEG API, command-line tools or PNG dependency. This software is based in part on the work of the Independent JPEG Group. The original LICENSE.md and README.ijg are packaged in `raw-vulkan/` assets and included in the license screen.

## JPEG output

The slower direct JPEG encoder candidate was removed in 0.18; libjpeg-turbo is retained for DNG decoding only. Android Bitmap remains the production JPG 95 encoder. `PhotoOutput` owns the pending file, writes orientation/sRGB/focal-length EXIF, then atomically renames in the same directory. Failure leaves the previous result intact; there is no runtime encoder fallback.
