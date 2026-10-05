# Tests and qualification

`python tools/build.py` runs JVM tests for calibration, orientation, focal selection,
pan/zoom geometry, exposure, camera metering and CPU imaging references, plus Android
lint. Only synthetic white-balance oracle rows are included; the original SDK was
used offline, not embedded as a runtime dependency.

`app/src/androidTest` covers locale selection, recipe round trips, GPU LUT generation,
viewfinder controls, capture and darkroom copy saving. Run with an authorized connected device:

```sh
sh ./gradlew :app:connectedDebugAndroidTest
```

The capture/edit acceptance test requires camera permission and adds a new photo and
a developed copy. It restores the application language and orientation after testing.

For native changes, compare pre-JPEG encoded sRGB to a qualified reference: RMS ≤1,
p99 ≤3 and max ≤16 in 8-bit code units. Test spatial energy/radius, flat fields and
fixed-seed grain statistics as well; a color-only test does not qualify spatial
changes. A build/lint pass is not evidence of camera HAL compatibility.

For UI/localization changes, check English, Chinese and another system locale
(English fallback), portrait/landscape, font scaling, permission denial, camera
restart, gallery browsing and darkroom cancel/save. Keep system orientation lock
separate from sensor-derived photo orientation.

Test each available RAW lens on a new device, including physical result pairing,
manual white-balance confirmation, capture, DNG import/export and edit. Processing
failures must retain originals and prior results. Avoid a Cartesian product of all
films/papers/effects: use representative fixed recipes and expand only on failures.

Optional device checks:

- `DngImportTest`, enabled with instrumentation argument `dngImport=true`,
  reads `cache/import-input.dng` in the debug app. It imports and develops the
  input, verifies that the original is unchanged and removes its own output.
- `ReleaseSmokeTest`, enabled with `releaseSmoke=true`, controls the separately
  installed signed release through accessibility. Grant that app camera permission
  first. The test creates a real photo and verifies its library entry; it keeps
  the photo. These optional tests are skipped in ordinary instrumentation runs.
