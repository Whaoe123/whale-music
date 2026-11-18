# HANDOFF — Whale 交接文档

> 写给「接手这个项目的人或 AI」。读完这一份就能继续开发和发版，不需要任何历史会话。
> 生成时间：2026-11-18。若与代码冲突，**以代码为准**。

## 1. 这是什么

Whale 是一个 Android 在线音乐 App（Kotlin + Jetpack Compose + Hilt + Media3），
基于 [OHMusic](https://github.com/linkyplus-sys/OHMusic)（Apache-2.0）改造，包名 `com.whale.music`。

- 四页导航：正在播放 / 汽水推荐 / 发现 / 音乐库
- 在线音源：网易云音乐（登录、日推、搜索、歌单）、酷我（搜索/直链播放）、汽水推荐（字节公开接口）
- 抖音扫码登录（WebView 只露二维码方案）、网易云 / QQ 歌单链接导入
- 本地播放能力来自上游：MediaStore 扫描 + Media3 后台播放

## 2. 目录速查

```
app/src/main/java/com/whale/music/
├── MainActivity.kt            入口；四页 HorizontalPager + 迷你条 + 底栏 + 更新弹窗挂载点
├── ui/StartupViewModel.kt     冷启动自动播放（快照续播 → 日推 → 汽电台 → 精选）
├── update/                    ★ 应用内更新
│   ├── UpdateRepository.kt    拉 update.json、下载 APK、sha256 校验、跳安装器
│   └── UpdateViewModel.kt     状态机：Idle→Checking→Available→Downloading→Ready / Failed
├── ui/update/UpdateDialog.kt  更新弹窗
├── data/remote/               KwSource / QishuiSource / LyricSource / LinkImporter / RemoteMusicController
├── ncm/                       网易云 API 与会话（NcmApi / NcmSession）
├── player/                    MusicPlayerManager / MusicPlayerService（Media3）
└── ui/                        各页面 Compose UI
update.json                    ★ 服务端版本信息（发版工作流自动回写）
.github/workflows/android.yml  CI：单测 + assembleDebug + lint（push/PR 触发）
.github/workflows/release.yml  ★ 发版：打 tag → 签名打包 → 发 Release → 回写 update.json
docs/RELEASE.md                发版与应用内更新的完整说明
```

## 3. 应用内更新是怎么跑的

**服务端零后端**：只要两个静态文件。

1. App 启动 3.5 秒后请求 `UpdateRepository.UPDATE_JSON_URL`（默认指向本仓库 raw 的 `update.json`）
2. 比较 `versionCode`：**远端 > 本机**才提示（`versionName` 只用于展示，绝不能拿来比较）
3. 弹窗（`UpdateDialog`）→ 「立即更新」后台下载 APK 到 `cacheDir/updates/`
4. 下载完校验 `sha256`（不匹配直接删文件，绝不交给安装器）
5. `FileProvider`（authority `${applicationId}.fileprovider`，路径定义在 `res/xml/update_file_paths.xml`）
   → 系统安装器；Android 8+ 先跳「允许安装未知应用」
6. 「以后再说」记入 SharedPreferences `whale_update` 的 `dismissed_version_code`

**失败一律静默**：断网、JSON 坏、404 都不会弹错误、不影响启动。

`update.json` 结构：

```json
{
  "versionCode": 9,
  "versionName": "Whale 1.8",
  "url": "https://github.com/Whaoe123/whale-music/releases/download/v1.8/whale-music-1.8.apk",
  "sha256": "dbb7…",
  "changelog": "Whale 1.8"
}
```

## 4. 发版（以后只需要做这一步）

```bash
git pull
git tag v1.9 && git push origin v1.9      # 版本号自增，别重复
```

`release.yml` 会自动：`update.json` 的 versionCode +1 → 用 Secrets 里的 keystore 签名
`assembleRelease` → 创建 Release 附 APK → 回写 `update.json` 并提交到 main。

顺序是刻意的：**先上传 APK，最后才改 update.json**，避免用户提示更新却下载 404。

手动发版：Actions → Release → Run workflow（填版本号，不打 tag 也能发）。

## 5. ⚠️ 绝对不能丢的东西

| 东西 | 在哪 | 丢了会怎样 |
| --- | --- | --- |
| `whale.jks` 签名密钥 | 用户手机 `/sdcard/Download/DSH-Go/whale.jks`，**需另行备份** | 永远无法给老用户覆盖安装，更新通道作废，只能让用户卸载重装 |
| 密码 `Whale_20261118`、别名 `whale` | 用户自己记录 | 同上（keystore + 密码缺一不可） |
| 4 个仓库 Secrets | Settings → Secrets and actions | 发版工作流直接失败（会明确报错，不会发出半成品） |

**签名必须始终是同一个 keystore。** 换密钥 = 老用户装不上新版。
密钥文件已在 `.gitignore`（`*.jks`）里，**永远不要提交进仓库**。

## 6. 常见坑（都是实际踩过的）

- **versionCode 只增不减**：忘了 +1 或填倒了，老用户永远收不到那次更新。工作流已自动 +1，别手改。
- **raw.githubusercontent.com 有约 5 分钟 CDN 缓存**：刚发版，个别用户会延迟几分钟才看到提示。
  实测国内可通；若反馈收不到提示，把 `UPDATE_JSON_URL` 换成
  `https://cdn.jsdelivr.net/gh/Whaoe123/whale-music@main/update.json` 或自建 OSS。
- **CI lint 会卡编译**：`lintDebug` 是 error 级，缩进异常（Suspicious indentation）会直接失败。
- **网络**：本机到 GitHub 的 git 推送经常 TLS 中断，`git -c http.version=HTTP/1.1 push` 多试几次即可；
  或改用 GitHub API 建 ref。
- **Dependabot** 会自动开 actions 大版本升级 PR（checkout/setup-java 等 4→7），
  合并前先看 CI，可能改坏工作流。

## 7. 本地开发

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-parallel
./gradlew lintDebug --no-parallel
```

需要 JDK 17 + Android SDK 37；本机没有 Android SDK 时，**靠 GitHub CI 编译验证**（push 即触发）。
签名相关环境变量见 `docs/RELEASE.md`（本地不配也能出 debug 包）。

## 8. 移交时给新 AI 的提示词（直接复制）

```
项目：github.com/Whaoe123/whale-music（Android 音乐 App，Kotlin + Compose + Hilt + Media3）
先读仓库里的 HANDOFF.md、docs/RELEASE.md、README.md。
应用内更新已实现：App 读 update.json 比 versionCode → 弹窗 → 下载 → sha256 校验 → FileProvider 安装。
发版：打 tag v* 触发 .github/workflows/release.yml（自动签名打包、发 Release、回写 update.json）。
签名 Secrets 已配好，keystore 由用户本地保管，绝不能丢、绝不能提交进仓库。
```
