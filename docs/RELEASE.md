# 发版与应用内更新

Whale 的应用内更新不需要自己搭服务器：**仓库里两个静态文件就够了**。

```
update.json   版本信息（App 启动时拉取）
*.apk         放在 GitHub Release 里（App 下载）
```

## 用户端是怎么跑的

1. 启动 3.5 秒后请求 `update.json`（地址见 `UpdateRepository.UPDATE_JSON_URL`）
2. 拿远端 `versionCode` 和本机比，**只认 versionCode**（单调递增），更大才提示
3. 弹窗显示更新说明 → 点「立即更新」后台下载 APK
4. 下载完按 `sha256` 校验 → FileProvider 交给系统安装器
5. Android 8+ 第一次会要求允许「安装未知应用」，授权后再点安装即可
6. 点「以后再说」= 这个版本不再提示（记在本地 SharedPreferences）

检查失败（断网、JSON 坏了）一律**静默跳过**，不弹错、不影响启动。

## 你发一个新版本要做什么

```bash
# 1. 提交代码
git add -A && git commit -m "feat: xxx" && git push

# 2. 打 tag（版本号自己定，别重复）
git tag v1.8 && git push origin v1.8
```

然后 **Release 工作流**（`.github/workflows/release.yml`）自动完成：

- 读 `update.json` 的 versionCode +1，作为本次版本号
- 用你配好的 keystore 签名构建 `assembleRelease`
- 创建 GitHub Release 并附上 APK（含 sha256）
- 回写 `update.json` 并提交到 main → 用户端下次启动就能看到新版本

**顺序很重要**：先 Release 带 APK，最后才改 `update.json`（工作流已经按这个顺序写了）。

## 第一次用之前：配置签名 Secrets（必须）

发布包必须**始终用同一个 keystore 签名**，否则老用户装不上新版本。密钥丢了 = 更新通道作废。

生成一次（本机或任意有 JDK 的机器）：

```bash
keytool -genkeypair -v \
  -keystore whale.jks -alias whale \
  -keyalg RSA -keysize 2048 -validity 10000
# 记住密码和别名 whale
```

**把 whale.jks 备份到安全的地方（云盘/密码管理器），丢了无法找回。**

然后在 GitHub 仓库 → Settings → Secrets and variables → Actions 新建 4 个 secret：

| Secret | 值 |
| --- | --- |
| `OHMUSIC_KEYSTORE_BASE64` | `base64 -w0 whale.jks` 的输出 |
| `OHMUSIC_KEYSTORE_PASSWORD` | keystore 密码 |
| `OHMUSIC_KEY_ALIAS` | 别名（上面是 `whale`） |
| `OHMUSIC_KEY_PASSWORD` | 密钥密码（通常和 keystore 密码相同） |

没配好就打 tag，工作流会直接报错并提示，不会发出半成品。

## 常见问题

- **raw.githubusercontent.com 有约 5 分钟 CDN 缓存**：刚发版后，个别用户可能延迟几分钟才看到提示。
- **想换托管地址**：改 `UpdateRepository.UPDATE_JSON_URL` 即可（OSS / Gitee / Cloudflare 都行），App 端逻辑不用动。
- **`sha256` 为空**：跳过校验（只在本地调试时这么干，正式发版工作流一定会写入真实值）。
- **手动触发**：Actions → Release → Run workflow，填版本号（如 `1.8`），适合不用 tag 的场景。
