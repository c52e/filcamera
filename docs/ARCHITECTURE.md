# Architecture

`CameraActivity` presents Compose camera, library, photo viewer and darkroom pages.
`CameraViewModel` exposes state. `CameraCoordinator` owns Camera2 preview/capture
coordination, and `EditSession` owns cancellable editing. `PhotoRepository` indexes
private DNG/JPG/JSON records and publishes encoded copies to MediaStore.

One `FilmWorker` serializes Vulkan LUT, RAW and film work. Native contexts and image
handles belong to it. Cancellation occurs at safe submitted-work boundaries.

Capture: Camera2 RAW → full calibration/demosaic → framing, ProPhoto and orientation
→ film exposure/spatial effects/development → print/paper → scanner/output → RGBA8
readback → JPG 95. Intermediate floating-point images remain GPU-resident. A cropped
input still spans a simulated 36 mm long-edge film frame.

Darkroom previews retain one GPU float32 CMY negative after film development,
including fixed-seed grain. The cache uses the immutable GPU input image ID,
dimensions, film profile/exposure/development, film spatial switches, grain seed
and numerical execution settings. Paper profile, print exposure/compensation,
contrast, filters and print diffusion reuse it and rerun printing plus scanning.
Film-side edits replace it. A device copy restores the negative into writable
scratch before printing, so spatial ping-pong cannot mutate the cached plane.
No intermediate image crosses to the CPU. The 3 MP preview adds at most 36 MB
of pixel storage (plus device alignment); cache admission failure continues
with a complete render. LUT baking, capture and final saving disable and release
the cache. Closing/cancelling/failing a native render also discards it.

Preview: Camera2 ISP → GLES, framing/metering and a device-generated recipe LUT.
LUT updates do not restart the camera. The existing table remains until a validated
replacement is uploaded; without one, the view uses ISP output.

The original DNG, frozen recipe/seed/framing/white-balance metadata and final image
are the persistent truth. Darkroom renders a smaller temporary preview; Save copy
recomputes the final resolution and publishes a separate version. App deletion does
not delete independently saved system gallery copies.

`strings` owns Android localization. Its application-context resources follow the
system locale on UI and worker threads. English is the fallback; Simplified Chinese
(`zh-Hans`), Traditional Chinese (`zh-Hant`) and Japanese (`ja`) have complete
resources, including diagnostic UI. Android 13+ system app-language settings use
`locales_config.xml`; there is no in-app language selector. Enum display labels resolve on access;
recipe keys, stages, file formats and capture identifiers are not translated.
Low-level native and numerical diagnostic text remains English.
