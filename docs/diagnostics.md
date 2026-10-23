# Local problem reports

FILCamera requires no telemetry server. An error displays a stable code, failure stage,
random event ID, app version and source fingerprint. Users can send a screenshot, or
open **Settings → Problem diagnosis** to review/copy the report or share a ZIP through
Android's share sheet. Nothing is uploaded automatically.

## What is retained

- The most recent 120 breadcrumbs (at most 2,000 characters per event), with wall time,
  monotonic time and a random process-session ID.
- Five error snapshots, including the breadcrumb history at failure, exception cause
  chains (up to 16,000 characters), device/Android/app build details and caller-supplied
  state. Error snapshots survive retries and app restarts.
- The current and previous process session. Java uncaught exceptions are saved
  synchronously before forwarding to Android's original crash handler. Android 11+
  process-exit reasons are recorded on the next launch. Native crash stacks and ANR
  traces are not captured by this implementation.
- Up to ten exported ZIPs in cache, with exports older than seven days removed on the
  next export. Internal reports are under `noBackupFilesDir/diagnostics`; exports use
  a narrowly scoped FileProvider path and temporary URI read permission.

Images, EXIF, location, document contents, device serials and credentials are not
collected. Paths and content/file/HTTP URIs in exception text are redacted. This is
defense in depth, not a reason to log sensitive inputs: callers must only pass app
state, camera capabilities/IDs and generated operation IDs. Users can inspect the
report before sharing it.

## Reading reports

Match the screenshot's `#event` to an `error-*.txt` entry. `summary.txt` contains the
latest error, or session history when no error exists. `session.txt` includes activity
after a failure (such as fallback and retry); the error file preserves the failure
time snapshot. `previous-session.txt` helps diagnose unexpected process exits.

The source fingerprint in `BuildConfig.DIAGNOSTIC_BUILD` hashes Kotlin sources,
Android XML and Gradle scripts in stable path order. It distinguishes local source
changes with the same version name; it is not a Git commit or a complete native/model
asset identity. Preserve APKs/source revisions and mapping files for published builds.
The project currently does not enable R8 minification.

## Stable codes

| Code | Failure site |
| --- | --- |
| CAM-010 | No supported rear RAW route discovered |
| CAM-012 | Read camera characteristics |
| CAM-013 | Validate/configure RAW and preview outputs |
| CAM-014 | Request camera open |
| CAM-020 | Create/configure capture session |
| CAM-021 | Submit preview request |
| CAM-022 | Camera startup timeout |
| CAM-030 / CAM-031 | Device disconnected / Camera2 device error |
| CAM-040 | Capture/read/pair a RAW frame |
| CAM-099 | Other camera control failure |
| GPU-001…004 | Preview surface, LUT upload, memory, LUT build |
| DEV-001 | Develop and retain captured photo |
| EDIT-001…003 | Open editor, render, memory |
| IO-001 / IO-002 | Import / export DNG |
| SAVE-001…003 | Save edited copy, publish gallery, low storage |
| LIB-001…003 | Scan library, delete item, delete batch |
| APP-001 / APP-002 / APP-010 | Uncaught exception, recipe restore, other operation |

Add breadcrumbs at action boundaries, not per preview frame. Include the same attempt
or operation ID in asynchronous callbacks. Call `LocalDiagnostics.failure` before
cleanup and pass the original exception rather than just its message. The returned
text carries screenshot metadata. Keep existing codes stable when translating UI.

## Verification

`DiagnosticsWorkflowTest` checks bounded retention, redaction, exception causes, build
identity, a real Camera2 query of a deliberately nonexistent ID, visible error details,
clipboard output and the ZIP/FileProvider share intent. The share chooser is intercepted
by the test; it does not send a report to another app or person. It also checks that
the normal camera is usable after diagnosis. Tests use the debug application ID and do
not delete existing media.

