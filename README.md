# 歌曲海 (GeQuHai)

一个 Android 客户端，搜索 / 在线播放 / 下载歌曲，数据来源逆向自 [m.gequba.com](https://m.gequba.com)。

> 本项目仅供学习 Android 客户端开发与 HTTP 抓取技术，请勿用于商业用途，下载的版权归对应音乐平台所有。

## 功能

- 🔍 **搜索**：输入歌名 / 歌手，返回歌曲列表
- ▶️ **播放**：调用 ExoPlayer (Media3) 在线流式播放，封面 / 标题 / 进度条
- ⬇️ **下载**：把整首 mp3 / m4a 保存到 `Android/data/com.mcode.gequhai/files/Music/`
- 🪟 **后台播放**：锁屏 / 通知栏可继续控制（Media3 MediaSessionService）

## 截图占位

> 后续补

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose + Material3 |
| 异步 | Kotlin Coroutines + StateFlow |
| 网络 | OkHttp 4 |
| 解析 | Jsoup (服务端渲染的 HTML) |
| 播放 | AndroidX Media3 ExoPlayer + MediaSessionService |
| 图片 | Coil |
| 持久化 | DataStore Preferences |
| 构建 | Gradle 8.5 + AGP 8.3.2 + JDK 17 |
| minSdk / target | 24 / 34 |

## 抓取链路（无 JS 引擎依赖）

逆向结论：**`window.appData.mp3_url` 字段直接就是完整 mp3 URL，无需走 `/api/geturl` 签名 + AES 解密链路**。

```
搜索页 (HTML)
https://m.gequba.com/s/{keyword}[/{page}]
  ↓ 正则 /songs/([0-9a-f]{32})
搜索结果 (hash + title + artist)

播放页 (HTML)
https://m.gequba.com/songs/{hash}
  ↓ 正则 window\.appData\s*=\s*JSON\.parse\(\s*'([\s\S]*?)'\s*\)
SongMeta (mp3_id / play_id / mp3_url / mp3_url_a / mp3_title / mp3_author / mp3_cover / mp3_duration / mp3_type)

播放 / 下载
  mp3_url 直接喂给 ExoPlayer 或 OkHttp 下载
```

完整逆向报告见仓库根目录 [`analysis-report.md`](../analysis-report.md)。

## 本地构建

需要 JDK 17 + Android SDK 34 + Build-Tools 34。

```bash
git clone https://github.com/<your-name>/GeQuHai.git
cd GeQuHai
./gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions 自动构建

`.github/workflows/build.yml` 在 `push` / `pull_request` 到 `main` 时自动跑：

1. 拉取 JDK 17
2. 安装 Android SDK 34
3. 执行 `./gradlew assembleDebug` 和 `assembleRelease`
4. 把生成的 APK 上传到 workflow run 的 Artifacts（保留 30 天）

下载方式：
1. 进 GitHub 仓库 → 选 workflow run → 滚到页面底部 "Artifacts" → 下载 `GeQuHai-APK`

## 项目结构

```
GeQuHai/
├── .github/workflows/build.yml     # CI: 构建 APK
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/mcode/gequhai/
│   │   │   ├── MainActivity.kt
│   │   │   ├── GeQuHaiApp.kt
│   │   │   ├── data/
│   │   │   │   ├── GequApi.kt     # 搜索 + 解析 (Jsoup)
│   │   │   │   ├── Downloader.kt  # 流式下载
│   │   │   │   └── Song.kt        # 数据模型
│   │   │   ├── player/
│   │   │   │   ├── PlaybackService.kt        # Media3 MediaSessionService
│   │   │   │   └── PlaybackController.kt     # MediaController 封装
│   │   │   └── ui/
│   │   │       ├── GeQuHaiScaffold.kt
│   │   │       ├── SearchScreen.kt
│   │   │       ├── NowPlayingScreen.kt
│   │   │       ├── DownloadsScreen.kt
│   │   │       └── Theme.kt
│   │   └── res/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── .gitignore
└── README.md
```

## License

MIT（按贡献者放宽到非商用学习用途，详见 LICENSE 文件）。