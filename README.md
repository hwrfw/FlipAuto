# FlipAuto

**三星 Galaxy Z Flip5 外屏增强工具**：为外屏提供快捷栏、通知中心、控制中心、多任务与外屏自动化。

[![Release](https://img.shields.io/github/v/release/hwrfw/FlipAuto?label=release&color=3b82f6)](https://github.com/hwrfw/FlipAuto/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/hwrfw/FlipAuto/total?label=downloads)](https://github.com/hwrfw/FlipAuto/releases)
[![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)](#兼容性)
[![License](https://img.shields.io/github/license/hwrfw/FlipAuto?label=license&color=green)](LICENSE)

无需 Root。除 NFC、移动热点、免打扰等系统开关需要 Shizuku 授权外，其余功能在未安装 Shizuku 时均可正常使用。

## 下载

| 文件 | 大小 | 系统 |
| --- | --- | --- |
| [FlipAuto-6.1-release.apk](https://github.com/hwrfw/FlipAuto/releases/latest) | 20 MB | Android 13（One UI 5.1）及以上 |

## 四步上手

### 1. 装一个第三方启动器

外屏需要第三方启动器作为桌面载体，推荐微软启动器（Microsoft Launcher），其他支持横屏的启动器也可以。

### 2. 打开 FlipAuto，跟着引导走完

按提示依次开启**无障碍服务**、**悬浮窗**、**通知使用权**，三项缺一不可。引导最后一页会让你选择「外屏默认应用」，选刚装好的启动器——这一步就是锁定桌面，同时决定 Home 键和开屏自动启动的目标。

### 3. 删掉多余的外屏卡片

在 Good Lock 里把外屏卡片删到只剩常用的几个。外屏卡片会让系统在你打开对应应用时弹出「在外屏打开」的提示，删掉不需要的卡片，这类提示基本就没了。

### 4. 按习惯调布局

外屏底部双白条上滑：左侧通知中心，右侧控制中心；白条可见时长按可切换快捷栏显隐。快捷栏按钮、顺序、位置都在设置里改。

> 缺口位置由系统上报的显示参数决定，首次安装时自动记录，不需要手动设置；换机或导入其他设备的配置后，在设置页顶部点「重新定位缺口」重测即可。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 设备 | 三星 Galaxy Z Flip5 |
| 系统 | Android 13（One UI 5.1）及以上 |
| Root | 不需要 |
| Shizuku | 可选，仅影响部分系统开关 |
| 网络 | 不需要，应用不联网 |

## 从源码构建

准备 JDK 17、Android SDK Platform 37.0、Build Tools 36.0.0 和 Platform Tools，通过 `ANDROID_HOME` 或 Android Studio 配置 SDK 路径。项目使用 Gradle Wrapper 9.3.1、Android Gradle Plugin 9.1.1 和 Kotlin 2.4.10。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试安装包输出到 `Cache/build-output.nosync/app/outputs/apk/debug/app-debug.apk`。发布版签名需自行配置 keystore。

## 关于

FlipAuto 自 2026 年 7 月起持续开发，核心是外屏自动化：锁定第三方桌面、开屏恢复上次应用、小窗悬浮球、免 Shizuku 旋转。2026 年 9 月整合了开源项目「外屏助手」的界面实现，合并为现在这个版本。

## 授权与致谢

FlipAuto 免费使用，源码以 MIT 许可证公开。

外屏桌面、通知中心、控制中心与任务页的界面实现来自开源项目[「外屏助手」](https://github.com/xiaomaoju/zflip5screenhelper)（作者 xiaomaoju）；外屏自动化部分由 FlipAuto 独立实现。

本项目采用 [MIT 许可证](LICENSE)。签名密钥、SDK、依赖目录和构建缓存不随仓库分发。
