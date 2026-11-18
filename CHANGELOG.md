# Changelog

All notable changes to OHMusic will be documented here. The project follows
semantic versioning after its first public release.

## Unreleased

## 1.16 - 2026-11-18

- 修复「导入歌单只导入前 6 首」：网易云 `/api/v6/playlist/detail` 匿名时 `tracks` 只返回前 6~10 首，
  现在带基础 Cookie 取全量 `tracks`，并用 `trackIds` + 分批 `song/detail` 补齐兜底，
  按歌单原顺序输出。实测 300 首歌单可完整导入 300 首。
- 修复「网易云登录后没同步收藏的歌」：新增 `NcmLikedSync`，登录成功后把
  「我喜欢的音乐」全量写进曲库的一张专用歌单（每次覆盖，不重复堆积）；
  冷启动自动补同步、曲库「+」菜单新增「同步网易云收藏」手动入口。
- 修复登录拉红心被风控：旧参数 `n=100000&s=8` 实测返回 -462，改为 `n=1000` 并带重试；
  `song/detail` 批量从 200 降到 100，规避限流。

## 1.15 - 2026-11-16

- 底部导航收窄为 232dp 居中胶囊（原为 90% 屏宽），图标降至 25/21dp，整体更紧凑。
- 网易云页全面对齐汽水页：顶栏 22sp 标题 + 11sp 副标题 + 44dp 图标按钮、账号胶囊、
  搜索框（胶囊 / 13.5sp / 取消字）、歌单卡 108dp、歌曲行 2dp 行距、列表底部留白 170dp、
  消息条样式，以及歌单详情的返回按钮与标题层级。

## 1.14 - 2026-11-16

- 首页改为全屏播放器，四页结构：播放器 / Whale-汽水 / Whale-网易云 / 曲库。
- 冷启动按登录态分流：已登录播网易云每日推荐，未登录播匿名热歌榜。
- 新增自建歌单：可新建、重命名、删除，任意列表长按即可加入歌单。
- 歌单导入改为全量写入曲库（修复只导入前几首、且没有真正入库的问题）。
- 网易云页列表与汽水页统一：序号、爱心、时长；下载按钮只在搜索结果出现。

## 1.0.1 - 2026-07-13

- Prevented page-switch flashes by prewarming retained screens and showing
  default artwork from the first loading frame.

## 1.0.0 - 2026-07-13

- Prepared the project for public open-source development.
- Standardized the product name as OHMusic.
- Limited local scans to songs longer than two minutes.
- Improved startup behavior, default artwork and adaptive launcher icons.
- Migrated playback to Media3 `MediaLibraryService` with five notification
  actions and multi-vendor system media controls.
- Removed third-party online cover fetching to keep playback offline and private.
