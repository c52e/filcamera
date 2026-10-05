# FILCamera 0.21.0 validation

The release package is `io.github.c52e.filcamera`; debug builds add `.debug`.
Validation on Xiaomi 15 Pro / Android 16 on 2026-10-04 covered native/Android
builds, 54 JVM tests, Android lint, English/Chinese resource lookup and fallback,
GPU LUT repeatability, model inventory integrity and corrupt-cache rejection.

A DNG from the earlier application was imported and rendered through the RAW and
film GPU pipeline. The original was unchanged and intermediate floating-point
readback was zero. A signed release camera capture and library check also passed;
one observed capture reported 4.1 seconds. These observations are smoke tests,
not throughput benchmarks or qualification of every device/lens/effect combination.

Earlier application tests also covered portrait/landscape controls, darkroom
adjustment, saving a copy and duplicate album-publication protection. They are
historical evidence; a new build needs relevant regression tests before release.

The source includes optional `CameraWorkflowTest`, `DngImportTest` and
`ReleaseSmokeTest` checks; see [TESTING.md](TESTING.md). Later build-system and
naming changes do not automatically inherit the signed release test result.

## Repository and build integrity checks

The subsequent internal naming cleanup passed native/Android builds, JVM tests and
lint. Eight Python packaging tests cover inherited licenses, nested notices,
unknown/missing license rejection, source mismatch and native integrity failure.
Five device tests passed on the debug package, including GPU LUTs and DNG import
through the renamed JNI bridge. The signed release has not yet been rebuilt for
this cleanup. No new capture-performance or image-quality claim is made.
