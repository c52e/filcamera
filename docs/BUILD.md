# Building FILCamera

Use Python 3.12+, Java 17 or a compatible JBR, and the Android SDK. Install:

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.22.1"
```

Set `ANDROID_HOME` to the SDK and `JAVA_HOME` to the JDK. An optional
`ANDROID_NDK_HOME` must point to NDK 28.2.13676358. `tools/build.py` finds SDK CMake
and Ninja, or uses PATH. Shader compilers come from that NDK. The current targets
use Android API 29 and arm64-v8a; Kotlin targets Java 17.

`python tools/build.py` builds libjpeg-turbo, RAW/shared Vulkan, the film engine,
and Android modules. It verifies all model hashes before packaging. It uses Python standard-library tooling. The source archive for libjpeg-turbo
is fetched from its official GitHub release and verified before extraction.

All generated native outputs and packaging assets are in `.build/`, Gradle outputs
in module `build/` directories. These are ignored. `--native-only` prepares native
outputs before IDE use; `--skip-native` is for UI-only iteration after that.
The root Gradle build can then be opened directly in Android Studio. Python must
be on PATH, or set `FILCAMERA_PYTHON` / Gradle property `filcamera.python` to its
executable. Gradle refreshes dependency notices and checks native source integrity
before packaging; changed native inputs require a full Python build.

Modified shaders compile from source. Unchanged legacy shaders may use the
hash-pinned qualified arrays accompanied by their source in `app/native/engine`;
CMake verifies the source/binary pair. No Python code generates algorithms.

Before a binary release:

1. Run the complete build, public audit and relevant device tests.
2. Record the exact source revision and dependency inventory (`:app:dependencies`).
3. Sign through your own secure signing workflow; never add a keystore/password.
4. Provide complete corresponding source, build scripts and all license notices.
5. Verify the release APK's signer, packaged assets, permissions and version.

The included CI builds on Linux. Windows is also supported. macOS discovery is
implemented but has not been tested. No bit-for-bit reproducibility claim is made
across OS/toolchain revisions. Debug binaries are for development, not a secure
release-signing workflow.

## Signing a release

Release builds use `io.github.c52e.filcamera`; debug builds add `.debug`. Configure
these non-secret values in an untracked root `keystore.properties` file:

```properties
storeFile=/absolute/path/to/your-key.jks
keyAlias=release
```

Run `python tools/build.py --release` in your own terminal. Password prompts do not
echo input. Press Enter at the key-password prompt if both passwords are the same.
The output is `app/build/outputs/apk/release/app-release.apk`. After native sources
have been built, `--skip-native --release` can be used for a UI-only release build.
Missing signing credentials fail the release build; no debug signing fallback occurs.

Automation can supply `FILCAMERA_KEYSTORE`, `FILCAMERA_KEY_ALIAS`,
`FILCAMERA_STORE_PASSWORD` and `FILCAMERA_KEY_PASSWORD` through its secret environment.
Do not place passwords on the command line. The build uses `--no-daemon`; signing
credentials are not written by the Python script to configuration files.

The release key and the debug key are independent. Keep the older app installed
until you have exported any DNGs needed in the new package. A DNG import restores
its recorded recipe; it does not transfer an entire old library or app settings.

## Build-specific delivery

The standard `python tools/build.py` workflow resolves the selected runtime graph,
reads each module's POM (including inherited licenses), and preserves LICENSE,
NOTICE, COPYING and COPYRIGHT entries from AARs and nested JARs. Missing or newly
introduced non-Apache runtime licenses stop packaging for review. Native/data
licenses continue to be supplied by the pinned engine and model inventory.

The app includes the exact `runtime-dependencies.json` and combined notices for
its variant. Generated declarations are under `.build/runtime-notices/` and are
refreshed by Gradle for both command-line and Android Studio builds.

`artifacts/delivery/debug/` (or `release/`) contains the APK, corresponding project
source archive, combined notices, dependency inventory, and `build-manifest.json`.
The manifest records the Git commit (null before the first commit), dirty status,
source-file hashes, APK/archive hashes and engine identity. It does not claim a
byte-reproducible build. CI uploads the complete directory under its commit ID.

Publish these files together for each release. The source includes pinned native
source download/build scripts; Maven coordinates and artifact hashes identify
Android dependencies. This inventory covers runtime artifacts, not all host build
plugins or test tools, and is not a legal-compliance certification. Any future
dependency with separate attribution requirements needs a fresh review.

Before delivery, the APK's embedded source snapshot must match the current source
and archived files. Its actual native library bytes are checked against engine
identities, as are model data and native build inputs. Changing source after a
build requires rebuilding before packaging; an unrelated old APK cannot be paired
with the current source archive. This is an integrity check, not an adversarial
attestation or a claim of byte-reproducible builds.

## Source selection and safe delivery publication

A Git checkout exports files selected by Git, excluding ignored files (including
tracked files that match ignore rules) and the fixed local-configuration exclusions.
Source archives include `source-files.json`; without Git, tools use that explicit
inventory and do not discover extra local files. Add new source paths to that
inventory when developing from an extracted archive. Symlinks and paths outside
the project are rejected. Source snapshots exclude the generated inventory itself.

Delivery generation uses a locked staging directory. Only after every check passes
is the complete directory published. Windows directory replacement uses two renames
with rollback rather than claiming a single atomic replacement of nonempty folders.
A previous complete directory is retained as `.debug.previous` / `.release.previous`;
if publication is interrupted between renames, the next attempt restores it before
proceeding. Failed validation does not overwrite the current delivery. Consumers
should use the final variant directory after the build command succeeds.

On Windows, directory publication retries temporary access/sharing errors for a
bounded period. If publication still fails, the verified staging directory is
retained and its location is included in the error. This does not re-sign or change
the already-built APK. Validation failures still remove incomplete staging output.
