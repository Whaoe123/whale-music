# Whale

> 本仓库为 **Whale**（原 OHMusic）改造版：应用名 Whale，包名 / applicationId / namespace 均为 `com.whale.music`。
> 在原有本地播放基础上增加了四页滑动导航：**正在播放 / 汽水推荐 / 发现 / 音乐库**。

## 关于「汽水推荐 网易云」与启动自动播放

推荐内容来自汽水音乐（字节跳动）的公开接口，音频由酷我音乐提供：

- 推荐歌单 / 歌单曲目：`beta-luna.douyin.com/luna/discover/mix`、`/luna/playlist/detail`（匿名可用）
- 点歌时按「歌名 + 歌手 + 时长」在酷我打分匹配同一首歌，再用酷我直链播放
- 因此可以用上汽水的推荐算法，同时保留完整的播放 / 下载 / 离线 / 歌词 / 锁屏能力

汽水对会员曲是服务端卡口的（只下发 40~60 秒试听），所以这里**不解析汽水音频**。

**冷启动自动播放**（汽水页右上角开关，默认开）：优先从「上次听到的那首 + 进度」接着放，
没有记录或解析失败则播汽水推荐第一张歌单的第一首。若别的应用正在播放（播放器已有队列）不会打断。

## 登录

抖音的扫码接口（`passport/web/get_qrcode` 等）要求其 JS SDK 计算的设备指纹签名（`a_bogus`），
原生直连一律被风控（实测 `error_code: 4031`），本仓库**不实现也不破解签名**。

登录采用「只露二维码」的 WebView 方案：加载抖音页面后注入脚本，
自动点开登录弹窗、从 canvas / img 中识别出方形二维码（data:image 优先），
隐藏页面其余元素只留二维码铺满视口 —— 用户看到的只是一张二维码卡片，看不到网页本身。
Cookie 中的 `sessionid` 出现即判定登录成功，并存入本地以便下次启动保持登录态。

简体中文 | [English](README_EN.md)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen.svg)](#系统要求)

Whale 是一款使用 Kotlin 和 Jetpack Compose 开发的 Android 音乐播放器，基于 [OHMusic](https://github.com/linkyplus-sys/OHMusic) 改造。它在本地播放能力之上接入了网易云音乐、酷我音乐与汽水推荐，支持账号登录、在线搜索、歌单导入与歌词。

## 主要特点

- 网易云音乐扫码 / 网页登录，同步每日推荐与收藏
- 发现页在线搜索（酷我 / 网易云），汽水推荐冷启动自动续播
- 粘贴网易云、QQ 音乐歌单链接一键导入
- 扫描 Android MediaStore 中超过 2 分钟的本地歌曲
- 使用 Media3 `MediaLibraryService` 提供可靠的后台播放
- 支持通知栏、锁屏、耳机和蓝牙设备的系统媒体控制
- 支持顺序播放、单曲循环和随机播放
- 支持收藏歌曲，并自动恢复播放队列和进度
- 优先读取歌曲内嵌封面，无封面时显示内置默认封面
- 适配 Android 12+ 动态主题图标和主流厂商媒体通知样式

## 系统要求

- Android 10（API 29）或更高版本
- 需要授予“音乐和音频”访问权限

Whale 会在扫描时过滤时长小于或等于 2 分钟的音频，避免把提示音、铃声和较短的录音加入音乐库。

## 安装与使用

正式版本发布后，可在本仓库的 [Releases](../../releases) 页面下载 APK。当前也可以按照下方开发说明自行构建。

安装后：

1. 授予“音乐和音频”读取权限。
2. 首次启动会扫描本地歌曲；在线内容在“汽水推荐 / 发现”页。
3. 点击音乐库或搜索结果中的歌曲开始播放。
4. 底部导航在“正在播放 / 汽水推荐 / 发现 / 音乐库”之间切换。

通知栏和状态栏播放器的最终外观由手机厂商系统决定。Whale 使用 Android 官方 Media3 接口，不模拟或破解厂商的灵动胶囊、原子通知等私有能力。

## 隐私说明

Whale 只在获得 Android 授权后读取本地音频文件及其元数据。歌曲信息、收藏状态、播放队列和进度均保存在设备本地。

联网仅用于在线音源与账号：搜索、推荐、歌词、歌单导入会请求对应平台的公开接口；网易云 / 抖音登录在 WebView 中完成，登录态保存在本地。应用不包含广告、第三方统计分析 SDK。详细说明见 [PRIVACY.md](PRIVACY.md)。

## 开发环境

- Android Studio
- JDK 17 或更高版本
- Android SDK 37

如果 Android SDK 未被自动识别，请在项目根目录创建不会被 Git 跟踪的 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

### Windows

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-parallel
.\gradlew.bat lintDebug --no-parallel
```

### Linux / macOS

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-parallel
./gradlew lintDebug --no-parallel
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Debug 版本与正式版本共用应用 ID `com.whale.music`，同名覆盖安装。

## 正式构建

未配置签名时，可以验证并生成未签名 Release 产物：

```powershell
.\gradlew.bat lintRelease --no-parallel
.\gradlew.bat assembleRelease bundleRelease --no-parallel
```

正式签名通过以下环境变量配置，禁止将密钥或密码提交到仓库：

- `OHMUSIC_KEYSTORE_FILE`
- `OHMUSIC_KEYSTORE_PASSWORD`
- `OHMUSIC_KEY_ALIAS`
- `OHMUSIC_KEY_PASSWORD`

完整发布约定见 [CONTRIBUTING.md](CONTRIBUTING.md#release-signing)。

## 项目结构

```text
Compose UI -> ViewModel -> MusicPlayerManager -> MediaController
                                          \-> MediaLibraryService -> ExoPlayer
Compose UI -> ViewModel -> MusicRepository -> Room / MediaStore
```

更多信息：

- [架构说明](docs/ARCHITECTURE.md)
- [设备兼容性](docs/DEVICE_COMPATIBILITY.md)
- [隐私政策](PRIVACY.md)
- [更新记录](CHANGELOG.md)

## 参与贡献

欢迎提交 Issue 和 Pull Request。参与前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，安全问题请按照 [SECURITY.md](SECURITY.md) 中的方式报告。

## 开源许可

Whale 基于 [OHMusic](https://github.com/linkyplus-sys/OHMusic) 修改，使用 [Apache License 2.0](LICENSE) 开源，修改之处已在本仓库说明。第三方组件及其许可信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
