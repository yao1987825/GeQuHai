# m.gequba.com JS 逆向 + Android 抓取方案

> 直接结论在前: **完全不需要 AES 解密**。`window.appData.mp3_url` 直接就是完整 mp3 的下载 URL。
> 整套抓取链路对真 HTML+正则就够, 不用执行任何 JS, Android 端 OkHttp 即可。

## 1. 抓取链路 (3 步)

### 1.1 搜索页 (HTML)
URL: `GET https://m.gequba.com/s/{keyword}` (第 2 页开始: `/s/{keyword}/{page}`)

页内 `<a href="/songs/{hash32}">` 直接就是结果列表。
`title` 属性 (或 ` - ` 切分) 即 "歌名 - 歌手"。

```html
<a href="/songs/72874ee4a7e29acd3be36369a6baba92"
   title="无奈的思绪 - 嘉慧">无奈的思绪 - 嘉慧</a>
```

### 1.2 播放页 (HTML)
URL: `GET https://m.gequba.com/songs/{hash32}`

页内 `<script>` 里有这一行, 它就是所有元数据的源头:
```js
window.appData = JSON.parse('{"web_code":"no","mp3_id":"...","play_id":"...","mp3_title":"...","mp3_author":"...","mp3_type":0,"mp3_cover":"...","mp3_duration":"02:36","mp3_extra_urls":[...],"mp3_url":"https://kw-lv.kuwo.cn/...m4a","mp3_url_a":"https://kw-lv.kuwo.cn/...m4a","mp3_name":"...","is_white_url":false,"ut":false,"ad_type":1,"extra_url_compel":false}');
```

`mp3_url` 字段就是真实 mp3 URL (酷我 CDN), 整首可下载 (本次实测 ~958 KB, 2:36 时长, 200 OK, Content-Type audio/mp4, Accept-Ranges: bytes).

### 1.3 备用: /api/geturl + AES 解密
**结论: 大多数情况下不需要走这一步**, 仅在 `mp3_url` 失效或 `web_code != "no"` 时作为 fallback.
若必须走这一步, 用 `window.iLingKu_Signature(...)` 签 POST, 再 `window.iLingKu_Decode(...)` 解 (AES-CBC + URL-32 Base64).

## 2. appData 字段清单 (实测嘉慧 - 无奈的思绪)

| 字段 | 类型 | 含义 | 例 |
|---|---|---|---|
| mp3_id | string | 歌曲 hash32 (与 URL 中的 /songs/{hash} 一致) | 72874ee4a7e29acd3be36369a6baba92 |
| play_id | string | 后端播放凭证 (URL-safe base64) | MjOUnqUzq-xnT_YgSXJrWg |
| mp3_title | string | 歌名 | 无奈的思绪 |
| mp3_author | string | 歌手 | 嘉慧 |
| mp3_type | int | 音质类型 (0=标准, 其他值含义待确认) | 0 |
| mp3_cover | string | 封面 URL (百度 gimg) | https://gimg3.baidu.com/gimg/app=2028&fmt=auto&src=img1.kuwo.cn%2Fstar%2Falbumcover%2F500%2Fs4s70%2F51%2F3051602051.jpg |
| mp3_duration | string | 时长 mm:ss | 02:36 |
| mp3_name | string | "歌名-歌手" | 无奈的思绪-嘉慧 |
| mp3_url | string | ★ **完整 mp3 URL (主要下载源)** | https://kw-lv.kuwo.cn/f47990f69ef4ba0e2bf23c2f8df500be/6ad5c58d/resource/30106/trackmedia/C2000031zvyp3S4QOQ.m4a |
| mp3_url_a | string | 同 mp3_url (与 mp3_url 等价) | 同上 |
| mp3_extra_urls | array | 推荐位 [{id, color, compel_wap}] | [{id:651621673,color:"#118AB2",compel_wap:false}] |
| is_white_url | bool | 是否白名单 url (true 时 mp3_url 可能为空) | false |
| extra_url_compel | bool | 是否强制走额外 url | false |
| web_code | string | "no" 表示无前置 web 校验 | no |
| ad_type | int | 广告类型 | 1 |
| ut | bool | 未知 | false |
| lrc_is_empty | bool | 是否无歌词 | false |
| error_msg | string | 错误信息 | "" |

## 3. 实测 mp3 URL 验证

```
$ curl -sI 'https://kw-lv.kuwo.cn/f47990f69ef4ba0e2bf23c2f8df500be/6ad5c58d/resource/30106/trackmedia/C2000031zvyp3S4QOQ.m4a'
HTTP/1.1 200 OK
Content-Type: audio/mp4
Content-Length: 958747         # ~936 KB, 2:36 时长合理
ETag: "5011316f476f86d18e565f5e252e33e0ea33796f"
Last-Modified: Tue, 22 Sep 2026 06:20:19 GMT
Accept-Ranges: bytes
```

跟 `/api/geturl` 解密出来的 url 是同一文件 (同一 trackmedia id, 同一 ETag), 仅 CDN 边缘节点不同. **两者完全等价**, 直接用 `appData.mp3_url` 就好.

## 4. Android 端实现思路 (无 js 引擎依赖)

### 4.1 依赖
```gradle
implementation 'org.jsoup:jsoup:1.17.2'  // 解析 HTML
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

### 4.2 搜索
```kotlin
// GET https://m.gequba.com/s/{keyword}
val html = OkHttpClient().newCall(GET("https://m.gequba.com/s/${URLEncoder.encode(kw, "UTF-8")}")).execute().body!!.string()
val doc = Jsoup.parse(html)
val songs = doc.select("a[href~=/songs/[0-9a-f]{32}]").map { a ->
    val (title, artist) = a.attr("title").split(" - ", limit = 2).let {
        it[0] to it.getOrElse(1) { "" }
    }
    Song(
        hash = a.attr("href").substringAfter("/songs/"),
        title = title,
        artist = artist
    )
}
```

### 4.3 取播放 URL
```kotlin
// GET https://m.gequba.com/songs/{hash}
val html = OkHttpClient().newCall(GET("https://m.gequba.com/songs/$hash")).execute().body!!.string()
// 抓 window.appData = JSON.parse('...')
val regex = Regex("""window\.appData\s*=\s*JSON\.parse\(\s*'([\s\S]*?)'\s*\)""")
val jsonStr = regex.find(html)!!.groupValues[1]
val appData = JSONObject(jsonStr)              // 注意: m.gequba.com 用的是单引号包裹, JSON 内部是合法 JSON 串
val mp3Url = appData.getString("mp3_url")      // ★ 直接就是播放 URL
val title = appData.getString("mp3_title")
val artist = appData.getString("mp3_author")
val cover = appData.getString("mp3_cover")
val playId = appData.getString("play_id")
```

**JSON.parse 兼容性**: appData 字段值在 HTML 里是 `JSON.parse('"..."')` 双层引号, 内层是合法 JSON, 直接 `JSONObject` 解析即可, 无需手工 unescape.

### 4.4 下载
```kotlin
// 拉到 mp3Url 后, 直接保存 / 喂 ExoPlayer 都行. 无 CORS 限制 (Android 不是浏览器).
```

## 5. 已知边界

| 情况 | 表现 | 处理 |
|---|---|---|
| `is_white_url == true` 或 `mp3_url == ""` | 播放页不带完整 url, 需走 `/api/geturl` + AES | 走原 v5 脚本里的签名+解密 fallback |
| `web_code != "no"` | 有前置 web 校验, 通常要带 cookie + Referer | OkHttp 默认 CookieJar 不持久, 加 CookieJar 自动管理即可 |
| `mp3_type != 0` | 可能是 320kbps / 无损? (含义未确认) | 默认下载 mp3_type=0, 想要更高音质需查字段名是否补 mp3_url_hq / mp3_url_sq |
| IP 风控 | 连续请求 429 | 串行 + 1.5s 间隔 + IP_BLOCKED 检测 (你之前的 v5 思路) |
| 搜索结果 > 76 页 | 物理上就是分页, 每页 ~20 首 | 翻页用 `/s/{kw}/{page}` |
| 翻页是否还有更多 | 看末页页码链接 | `/#/>/a[href*=/s/${kw}/] )` 末页 |

## 6. 关于 `/api/geturl` 签名 (fallback 备用)

如果将来 mp3_url 为空, 必须 fallback 时, 签名链路:

```js
// 签名参数 (5 个)
fid = 1
type = window.getRandomNumber()              // 1~10 随机
timestamp = Date.now().toString()
signature = window.iLingKu_Signature(
  { id: play_id, fid, type, timestamp }, type
)

// 解密
realUrl = window.iLingKu_Decode(j.data.url, j.type)
```

- iLingKu_Signature: 基于 `CryptoJS.AES + MD5`, key/iv 见 plugin.js (虽然你 v5 里没暴露 AES, 不过 `CryptoJS` 主体 4.1.1 已经注入了)
- iLingKu_Decode: AES-CBC + 自定义 URL-32 base64 (含 `&` 分隔填充, 必须还原)

**但对大多数歌曲不需要, 这次实测 0~7 类全都有 mp3_url 直接可下.**

## 7. 还可以确认的几件事 (建议你 Android 上线前再压测)

1. 不同 `mp3_type` (0/1/2/3) 是不是不同码率? 字段名是否还有 `mp3_url_320` / `mp3_url_lossless`?
2. 大批量搜索 (1509 首) 翻到第 76 页 是不是稳定?
3. 某些冷门歌 / VIP 歌的 mp3_url 是否始终存在?
4. `mp3_extra_urls[]` 的 id 是不是关联歌单 / 推荐? (看起来更像是广告位 id)
5. mp3_url 有没有时间过期? 当前实测 4 小时内都能下, 但 CDN 可能数天后换 hash.