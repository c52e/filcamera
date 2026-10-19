# FILCamera

[中文](README.zh-CN.md)

A native Android RAW camera with an on-device Vulkan film pipeline. Capture a DNG,
preview a film recipe, and develop another version later without losing the original.

- Camera2 RAW capture and supported rear-camera selection; calculated 35 mm equivalent framing.
- GPU RAW development, spectral film/paper rendering, grain, halation and diffusion.
- Device-generated preview LUTs, calibrated Kelvin white balance and tap-to-focus.
- Photo library, pinch zoom, sharing, DNG export/import and non-destructive darkroom.
- English and Chinese UI, selected by the system language. Other languages use English.
- Offline operation: no account, analytics, advertising or network permission.

The film engine is derived from **Spektrafilm Android** and **spektrafilm by Andrea
Volpato**. FILCamera is an independent project, not an official version of either.
See [credits and licenses](THIRD_PARTY_NOTICES.md).

## Build

Requirements: Python 3.12+, Java 17 (or Android Studio's compatible JBR), Android
SDK platform 35 / build-tools 35.0.0, **NDK 28.2.13676358**, CMake 3.22.1+ and Ninja.
Set `ANDROID_HOME` and `JAVA_HOME`. Optionally set `ANDROID_NDK_HOME` when that
exact NDK is outside the SDK. Initial builds download Gradle/Maven dependencies and the checksum-pinned
libjpeg-turbo source archive.

```sh
python tools/build.py
python tools/audit_public.py
```

The script builds native code, verifies and packages model assets, builds the debug
APK and test APK, and runs unit tests and lint. Output:
`app/build/outputs/apk/debug/app-debug.apk`. For subsequent UI-only changes use
`python tools/build.py --skip-native`. Do not use that flag after native/model changes.
On Windows, run these commands in PowerShell with Python on PATH.

Only `arm64-v8a` is packaged. A different developer's debug key cannot upgrade an installation signed by someone else: export
your originals before replacing such an installation. [Build details](docs/BUILD.md).

## Device support and limitations

Android 10+ with Vulkan 1.1 and a supported Camera2 Bayer RAW mode, at most 60 million
effective pixels. The earlier development builds were exercised on Xiaomi 15 Pro;
other devices need validation. HAL-exposed RAW lenses, physical-stream combinations
and manual controls vary by device. Unsupported cameras remain unavailable for RAW
capture; the app does not silently substitute JPEG capture.

Preview uses ISP input and a pointwise LUT. It is an approximation of RAW output;
grain, diffusion, halation and other spatial stages are final-image operations.
DNG import currently accepts the app's embedded development record, not arbitrary
third-party DNGs. Processing is serial and can take several seconds at full resolution.

## Project layout

| Directory | Responsibility |
| --- | --- |
| `app` | Compose UI, capture/edit orchestration, photo storage, film engine and model data |
| `camera` | Camera2, RAW metadata, lens discovery and camera controls |
| `raw-vulkan` | DNG decoding and GPU RAW development |
| `gpu-runtime` | Shared Vulkan device, buffers and spatial operations |
| `preview` | GLES viewfinder and preview geometry |
| `imaging` | Pure Kotlin calibration/math and CPU test references |
| `strings` | English/Chinese Android resources |
| `tools` | Portable build and publication audit |

The release application ID is `io.github.c52e.filcamera`; debug builds use
`io.github.c52e.filcamera.debug`. These install separately from each other and from
the earlier development app. Export a DNG from the older app and import it into
FILCamera to develop it; settings and historical JPG versions do not migrate automatically.

Project: [c52e/FILCamera](https://github.com/c52e/FILCamera).

## License

FILCamera's original code is **GPL-3.0-only**, see [LICENSE](LICENSE). Existing
third-party notices and per-file license grants remain intact. Film profiles and
derived LUTs are **CC BY-SA 4.0**, not relicensed as GPL. ICC profiles have their own
MIT / CC BY-SA 3.0 licenses. See [license inventory](THIRD_PARTY_NOTICES.md).

When distributing a binary, also provide its complete corresponding source and
build instructions under GPLv3. See [privacy](docs/PRIVACY.md) and
[contributing](CONTRIBUTING.md).
