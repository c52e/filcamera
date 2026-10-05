# FILCamera film engine source

This directory is the maintained GPL-3.0-only engine fork, not generated output.
Upstream: Spektrafilm Android `15fb120996f03fc1e58a9a9d819e335f65d7a092`.
Algorithm/data reference: original Spektrafilm `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`.
See `licenses/` for unchanged license texts and `ORIGIN.json` for per-file upstream
and migration hashes. Migration baseline: camera commit `05e76c9` (0.16).

Local changes inherited from that baseline: CAT16 alignment, corrected film gamma
in print midgray development, qualified pointwise GPU stages, exact spatial kernels,
fixed-seed grain, shared Vulkan resources, GPU image/auto-exposure transport,
cancellation, allocation/transfer diagnostics and packed RGBA8 output. These are now
ordinary C++ sources; edit them here rather than in Python or `.tools`.

- `spektra.cpp`: orchestration and model initialization.
- `runtime/stages`: film exposure, development, print and scan adapters.
- `gpu/vulkan_compute.cpp`: spatial GPU operators; `.comp` files beside it.
- `adapter/resident.*`: pointwise GPU session; `adapter/full.comp`: fused color shader.
- `model`, `kernels`, `profiles`: upstream algorithms, CPU parameters and explicit references.
- `../bridge.cpp`, `../film_spatial.cpp`: Camera JNI boundary and spatial orchestration.
- Shared device/handles and RAW development remain in root `gpu-runtime` / `raw-vulkan`.

CMake directly compiles all of these sources and exports compile_commands.json.
`adapter/full.comp` is compiled and validated by the pinned NDK tools on change.
Unchanged upstream shaders retain their qualified precompiled arrays, some originally
built with an older NDK. `../PinnedShaders.cmake` binds each array to both its source
and binary hash. Editing a `.comp` automatically compiles that source into the build
directory; it never silently uses the original array after a source change. Run the
numerical suite before accepting shader changes. Binary includes are build artifacts,
not places to edit algorithms. No Python script rewrites product C++ or shader text.

References in inherited comments to `docs/research/`, `tests/` and `tools/gpu_probe/`
are paths in the pinned [upstream source tree](https://github.com/thetechgeekko/Spektrafilm-android/tree/15fb120996f03fc1e58a9a9d819e335f65d7a092),
not commands or test files supplied by FILCamera. Local checks are described in
[TESTING.md](../../../docs/TESTING.md).

0.19.9 aligns effective grain blur/dye-cloud defaults and scanner USM with pinned
Python `3bb2c2d`. Scanner USM is implemented in `adapter/full.comp` operations
10/11 with GPU-owned scratch resources in `adapter/resident.cpp`; it runs after
CAM16 gamut compression and before output clipping/encoding. LUT generation
disables it.
