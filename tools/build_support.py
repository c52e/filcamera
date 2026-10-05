"""Portable, pinned Android tool discovery. No developer machine paths."""
import hashlib
import os
from pathlib import Path
import platform
import shutil

ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = "28.2.13676358"


def sdk_path():
    value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not value:
        raise RuntimeError("Set ANDROID_HOME to your Android SDK directory")
    return Path(value).resolve()


def ndk_path():
    override = os.environ.get("ANDROID_NDK_HOME")
    path = (Path(override) if override else sdk_path() / "ndk" / NDK_VERSION).resolve()
    properties = path / "source.properties"
    revision = None
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "Pkg.Revision":
                revision = value.strip()
                break
    if revision != NDK_VERSION:
        source = "ANDROID_NDK_HOME" if override else "SDK ndk directory"
        raise RuntimeError(
            f"Expected NDK {NDK_VERSION}; selected {path} via {source}, "
            f"found revision {revision or 'missing'}. "
            f"Install that version and set ANDROID_NDK_HOME to its directory."
        )
    return path


def shader_tool(name):
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
    return ndk_path() / "shader-tools" / host / (name + (".exe" if os.name == "nt" else ""))


def configure_tools():
    sdk = sdk_path()
    # The SDK's CMake package includes Ninja. PATH tools can also be used.
    candidates = sorted((sdk / "cmake").glob("*/bin"), reverse=True)
    if candidates:
        os.environ["PATH"] = str(candidates[0]) + os.pathsep + os.environ.get("PATH", "")
    for name in ("cmake", "ninja", "java"):
        if not shutil.which(name) and not (name == "java" and os.environ.get("JAVA_HOME")):
            raise RuntimeError(f"Install {name} and add it to PATH (Java can use JAVA_HOME)")
    ndk_path()


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prefix_map_flags():
    # Keep __FILE__, assertion strings and debug paths free of developer home paths.
    return f"-ffile-prefix-map={ROOT.as_posix()}=. -ffile-prefix-map={ndk_path().as_posix()}=/android-ndk"
