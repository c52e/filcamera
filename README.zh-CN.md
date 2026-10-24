# FILCamera

[English](README.md)

Android 原生 RAW 胶片模拟相机。使用 Camera2 拍摄、Vulkan 显影，保留 DNG 原片，
支持实时胶片预览、照片库和另存副本的暗房编辑。

- 根据设备实际公开能力选择后置 RAW 镜头，并按 35 mm 等效焦距裁切。
- 胶片、相纸、曝光、滤色、颗粒、光晕、镜头柔光和放印柔光。
- 基于镜头标定的 Kelvin 白平衡、点击对焦和测光。
- 支持英文、简体中文、繁体中文和日语，界面跟随系统语言；其他语言使用英文。
- 无账号、遥测、广告或网络权限，处理在本地完成。

本项目使用 Spektrafilm Android 派生引擎及 Andrea Volpato 的 spektrafilm
算法与数据，是独立项目，不代表上述作者或项目。

## 构建

安装 Python 3.12+、Java 17 或兼容 JBR、Android SDK 35 / build-tools 35.0.0、
NDK **28.2.13676358**、CMake 3.22.1+ 和 Ninja。设置 `ANDROID_HOME`、`JAVA_HOME`；
NDK 不在 SDK 内时另设 `ANDROID_NDK_HOME`。

```sh
python tools/build.py
python tools/audit_public.py
```

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`。构建脚本同时运行单元测试和 lint。
首次构建需要下载 Gradle/Maven 依赖和校验摘要固定的 libjpeg-turbo 源码。
仅修改 UI 后可用 `--skip-native`；修改 native 或模型数据后必须完整构建。

目前仅打包 arm64-v8a。正式包名为 `io.github.c52e.filcamera`，调试包使用
`io.github.c52e.filcamera.debug`，两者可与旧版并存。旧版 DNG 可导出后导入新版，
设置及历史 JPG 版本不会自动迁移。正式签名构建使用 `python tools/build.py --release`，
详见 [构建说明](docs/BUILD.md)。

项目：[c52e/FILCamera](https://github.com/c52e/FILCamera)。

## 限制

需要 Android 10+、Vulkan 1.1 和支持的 Bayer RAW 模式，有效像素不超过 6000 万。
历史版本主要在小米 15 Pro 上验证，其他设备需要实测。系统未公开的镜头无法使用。
预览基于 ISP 和 LUT，与 RAW 成片存在差异；空间效果只用于成片。
导入仅支持带本 App 显影记录的 DNG，暂不支持任意第三方 DNG。全尺寸处理可能需要数秒。

## 开源与隐私

本项目原创代码使用 GPLv3；胶片数据及衍生 LUT 保留 CC BY-SA 4.0，其他第三方材料
保留原许可，详见 [许可清单](THIRD_PARTY_NOTICES.md)。分发 APK 时应同时提供对应
完整源码和构建说明。上游署名与许可必须保留，产品改名不会移除其归属。

应用原片位于私有目录，卸载前应导出；系统相册副本独立保留。

[架构](docs/ARCHITECTURE.md) · [构建](docs/BUILD.md) · [测试](docs/TESTING.md) · [隐私](docs/PRIVACY.md)
