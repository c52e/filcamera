"""Build FILCamera with public source/model assets only (Python 3.12+, stdlib)."""
import argparse
import getpass
import sys
import json
import os
from pathlib import Path
import shutil
import subprocess

from build_support import ROOT, configure_tools, ndk_path, sha, prefix_map_flags
import build_raw_vulkan


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def prepare():
    configure_tools()
    raw = build_raw_vulkan.build()
    source = ROOT / "app/native"
    native = ROOT / ".build/film-native"
    subprocess.run(["cmake", "-S", str(source), "-B", str(native), "-G", "Ninja",
                    f"-DCMAKE_TOOLCHAIN_FILE={ndk_path().as_posix()}/build/cmake/android.toolchain.cmake",
                    "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-29",
                    "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
                    f"-DCMAKE_CXX_FLAGS={prefix_map_flags()}"], check=True)
    subprocess.run(["cmake", "--build", str(native), "--target", "filcamera_film", "--parallel", "4"], check=True)
    package = ROOT / ".build/package"
    lib = package / "jniLibs/arm64-v8a/libfilcamera_film.so"
    lib.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(native / "libfilcamera_film.so", lib)
    model = ROOT / "app/model-data"
    inventory = json.loads((model / "manifest.json").read_text())["assets"]
    assets = package / "assets"
    # Remove stale packaged data, never source data or photographs.
    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(parents=True)
    for name, expected in inventory.items():
        file = (model / "assets" / name).resolve()
        if not file.is_relative_to((model / "assets").resolve()) or sha(file) != expected:
            raise RuntimeError(f"Model asset checksum mismatch: {name}")
        destination = assets / "lab/assets" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, destination)
    sources = {p.relative_to(ROOT).as_posix(): sha(p) for p in source.rglob("*") if p.is_file()}
    identity = dict(schema=1, product="FILCamera", librarySha256=sha(lib),
                    buildTools={name: sha(ROOT / name) for name in ["tools/build.py", "tools/build_raw_vulkan.py", "tools/build_support.py"]},
                    runtimeSha256=raw["runtimeSha256"], assets=inventory,
                    engineManifestSha256=sha(source / "engine/ORIGIN.json"),
                    recipeAdapterSha256=sha(ROOT / "app/src/main/java/io/github/c52e/filcamera/FilmStocks.kt"),
                    spatialAdapters=sources, lutFormat="srgb-log32-domain4-rfast-rgba16f-65-v1",
                    precisionPolicy="018-fma32-grain16-storage-if-supported-shared-scratch-v1",
                    lutPrecision="float32-fma-encoded-rgba16f",
                    workgroups="pointwise256-or-capability64-raw16x8", storageProbes=False)
    write_json(assets / "preview/engine.json", identity)
    write_json(assets / "lab/manifest.json", dict(schema=1, assets=inventory, inputs=[]))
    prepare_notices()
    shutil.copy2(model / "CHANGELOG.txt", assets / "CHANGELOG.txt")
    write_json(ROOT / ".build/provenance.json", dict(product="FILCamera", raw=raw, film=identity, privateFixtures=False))


def prepare_notices():
    source = ROOT / "app/native"
    model = ROOT / "app/model-data"
    assets = ROOT / ".build/package/assets"
    notices = [ROOT / "THIRD_PARTY_NOTICES.md", ROOT / "LICENSE",
               source / "engine/licenses/SPEKTRAFILM_LICENSE.txt",
               model / "assets/icc/ellelstone/LICENSE-CC-BY-SA-3.0",
               model / "assets/icc/saucecontrol/LICENSE-MIT",
               ROOT / "licenses/Apache-2.0.txt", ROOT / "licenses/DNG-SDK.txt",
               ROOT / ".build/libjpeg-turbo-3.2.0/LICENSE.md", ROOT / ".build/libjpeg-turbo-3.2.0/README.ijg"]
    (assets / "third-party-notices.txt").write_text("\n\n".join(p.read_text(encoding="utf-8") for p in notices), encoding="utf-8")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-only", action="store_true", help="Prepare native libraries and verified assets only")
    parser.add_argument("--skip-native", action="store_true", help="Reuse local native outputs for UI-only iteration")
    parser.add_argument("--release", action="store_true", help="Build a signed release APK; prompt for credentials locally")
    args = parser.parse_args()
    if args.release:
        if args.native_only:
            parser.error("--release cannot be combined with --native-only")
        if not os.environ.get("FILCAMERA_STORE_PASSWORD"):
            if not sys.stdin.isatty():
                parser.error("Run --release in an interactive terminal, or supply FILCAMERA_STORE_PASSWORD securely")
            os.environ["FILCAMERA_STORE_PASSWORD"] = getpass.getpass("Keystore password: ")
        if not os.environ.get("FILCAMERA_KEY_PASSWORD"):
            if sys.stdin.isatty():
                password = getpass.getpass("Key password (Enter to use keystore password): ")
                os.environ["FILCAMERA_KEY_PASSWORD"] = password or os.environ["FILCAMERA_STORE_PASSWORD"]
            else:
                os.environ["FILCAMERA_KEY_PASSWORD"] = os.environ["FILCAMERA_STORE_PASSWORD"]

    if not args.skip_native:
        prepare()
    if not args.native_only:
        prepare_notices()
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
        variant = "release" if args.release else "debug"
        os.environ["FILCAMERA_PYTHON"] = sys.executable
        tasks = [":app:assembleRelease", ":app:lintRelease"] if args.release else [
            ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:lintDebug",
            ":app:testDebugUnitTest", ":camera:testDebugUnitTest", ":preview:testDebugUnitTest", ":imaging:test"]
        # Keep credentials out of command-line arguments and reusable Gradle daemons.
        subprocess.run(command + ["--no-daemon"] + tasks, cwd=ROOT, check=True)
        variant = "release" if args.release else "debug"
        print(f"APK: app/build/outputs/apk/{variant}/app-{variant}.apk")
        subprocess.run([sys.executable, str(ROOT / "tools/package_delivery.py"), "--variant", variant], check=True)



if __name__ == "__main__":
    main()
