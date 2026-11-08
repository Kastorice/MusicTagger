# Online metadata sources specification (core module, `com.musictag.core.net`)

Owner: net-sources agent. Pure JVM (java.net.HttpURLConnection), no Android, no
third-party JSON library. Unit tests use injected fake transports and recorded
JSON fixtures, so they never need the network.

## Contract (Lead-owned, do not modify) — `MusicSource.kt`

```kotlin
enum class MusicSourceId(val displayName: String) { NETEASE("网易云音乐"), QQ("QQ 音乐") }

data class RemoteSong(
    val source: MusicSourceId, val id: String, val title: String, val artists: String,
    val album: String? = null, val albumId: String? = null, val coverUrl: String? = null,
    val durationMs: Long? = null,
) { val subtitle: String }

data class LyricsResult(val lrc: String? = null, val translation: String? = null) { val isEmpty: Boolean }

data class ArtworkPayload(val bytes: ByteArray, val mimeType: String) { fun toArtwork(description: String = ""): Artwork }

interface MusicSource {
    val id: MusicSourceId
    fun search(keyword: String, limit: Int = 20): List<RemoteSong>   // blocking
    fun resolve(song: RemoteSong): RemoteSong = song
    fun fetchLyrics(song: RemoteSong): LyricsResult
    fun fetchArtwork(song: RemoteSong): ArtworkPayload?
}
class NetException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

All network calls are blocking; the app calls them on `Dispatchers.IO`.
Failures surface as `NetException` with a short Chinese message. Empty results
are valid (not an exception). Never leak raw exceptions.

## Files you own (create/rewrite)

- `core/src/main/kotlin/com/musictag/core/net/Http.kt` — transport abstraction + real implementation.
- `core/src/main/kotlin/com/musictag/core/net/MiniJson.kt` — minimal JSON parser.
- `core/src/main/kotlin/com/musictag/core/net/Lrc.kt` — replace the Lead stub (already has the API shape).
- `core/src/main/kotlin/com/musictag/core/net/MusicSources.kt` — replace the Lead stub.
- `core/src/main/kotlin/com/musictag/core/net/netease/NeteaseSource.kt`
- `core/src/main/kotlin/com/musictag/core/net/qq/QqMusicSource.kt`
- `core/src/test/kotlin/com/musictag/core/net/**` — tests, fakes, fixtures loader.
- `core/src/test/resources/net/*.json` — trimmed recorded responses.

Do not modify `MusicSource.kt` or anything outside `core/src/main/kotlin/com/musictag/core/net/`.
Build: `powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Tasks ":core:test","--tests","com.musictag.core.net.*"`.

## Http.kt

```kotlin
class HttpResponse(val code: Int, val body: String, val contentType: String? = null)

interface HttpTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResponse
    fun download(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 8 * 1024 * 1024): ByteArray
}

/** Real implementation over HttpURLConnection. */
object Http : HttpTransport
```

Requirements:
- Redirects followed, gzip accepted, UTF-8 bodies, connect+read timeout 20 s.
- `download` enforces `maxBytes`, sniffs JPEG/PNG/WebP magic for `ArtworkPayload`.
- Non-2xx → `NetException("请求失败（HTTP <code>）")`; `IOException` → `NetException("网络连接失败，请检查网络设置")`.
- URL must be http(s); upgrade cover URLs from `http://` to `https://`.
- Request headers should include a desktop browser User-Agent, and per-service
  `Referer`/`Cookie` (`https://music.163.com/` + `appver=2.0.2`; `https://y.qq.com/`).
- Keep public helpers so the UI can show errors; message must be user friendly.

## MiniJson.kt

`object MiniJson { fun parse(text: String): Any? }` returning
`Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean`, `null`.
Must handle escapes (`\" \\ \/ \b \f \n \r \t \uXXXX` including surrogate
pairs), nested objects/arrays, numbers, unicode. Add lenient access helpers used
by sources, e.g.:
`fun Any?.asMap(): Map<String, Any?>`, `fun Any?.asList()`, `fun Map<String,Any?>.str(key)`, `.long(key)`, `.obj(key)`, `.arr(key)`.
Throw `NetException("响应格式异常")` on invalid JSON.

## NetEase (`NeteaseSource`)

Constructor: `class NeteaseSource(private val http: HttpTransport = Http, private val apiBase: String? = null) : MusicSource`

Public endpoints (verified reachable from this machine; use https):
- Search: `POST https://music.163.com/api/cloudsearch/pc`, form body
  `s=<keyword>&type=1&limit=<limit>&offset=0`, headers UA/Referer/Cookie.
  Response: `result.songs[]` with `id` (number), `name`, `ar[].name`,
  `al.name`, `al.picUrl`, `dt` (duration ms). Also tolerate legacy
  `artists[]/album` shapes. Empty/missing `result` → empty list.
- Lyrics: `GET https://music.163.com/api/song/lyric?os=pc&id=<id>&lv=-1&kv=-1&tv=-1`
  → `lrc.lyric` (LRC text, may include `[by:...]`), `tlyric.lyric` (translation,
  may be absent/empty). Return `LyricsResult(lrc, translation)`.
- Cover: `al.picUrl` from search when present, otherwise
  `GET https://music.163.com/api/song/detail?ids=[<id>]` → `songs[0].album.picUrl`.
  Upgrade to https and download bytes. No cover → null.
- Custom base (`apiBase != null`, self-hosted NeteaseCloudMusicApi):
  - search `GET {base}/search?keywords=<kw>&limit=<limit>&type=1`
  - lyrics `GET {base}/lyric?id=<id>`
  - cover `GET {base}/song/detail?ids=<id>`
  Parse the same shapes (`result.songs[].ar/al`, `lrc.lyric`, `songs[0].album.picUrl`).
  Join base/endpoint robustly (trim trailing slash). apiBase may be http.
- `resolve` returns the song unchanged (search already includes album/cover).

## QQ Music (`QqMusicSource`)

Constructor: `class QqMusicSource(private val http: HttpTransport = Http) : MusicSource`
Headers: UA + `Referer: https://y.qq.com/`.

- Search (this is the endpoint that works, the classic client_search_cp returns
  500 from this network):
  `GET https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=<urlencoded keyword>&format=json&g_tk=5381`
  → `data.song.itemlist[]` with `mid`, `name`, `singer` (single string, may
  contain `/`). Return `RemoteSong(source=QQ, id=mid, title=name, artists=singer)`;
  album/cover stay null until resolved.
- Resolve:
  `GET https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=<mid>&tpl=yqq_song_detail&format=json`
  → `data[0]`: `title`, `singer[].name`, `album.name`, `album.mid`,
  `interval` (seconds). Build `coverUrl = https://y.gtimg.cn/music/photo_new/T002R500x500M000<albumMid>.jpg`
  and `durationMs = interval * 1000`. If data is empty → NetException("未找到歌曲详情").
- Lyrics:
  `GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>&format=json&nobase64=1&g_tk=5381`
  → `lyric` (plain LRC; HTML entities like `&#10;`/`&amp;` may appear — decode
  the common ones and `<br>`), optional `trans`. No lyric → `LyricsResult(null)`.
- Artwork: resolve first if `albumId == null`; download `coverUrl`.
  No album mid → null.
- Search results lack album info: the UI calls `resolve()` on selection, so this
  is fine.

## Lrc.kt

Keep the existing public API and implement it properly:
- `isLrc(text)`: has at least one `[mm:ss]`, `[mm:ss.SSS]` or `[mm:ss:SS]` tag.
- `plainText(text)`: strips time tags and `[ti:] [ar:] [al:] [by:] [offset:]`
  metadata lines, trims, drops empties; returns plain lyrics for previews.
- `mergeTranslation(original, translation)`: for each original line, if the
  translation has a line with the same time tag, append `\n<translated>` under
  it; keep original lines that have no translation; returns original when
  translation is blank/null. Result must remain valid LRC.

## MusicSources.kt

```kotlin
object MusicSources {
    fun create(neteaseApiBase: String? = null): List<MusicSource> =
        listOf(NeteaseSource(Http, neteaseApiBase), QqMusicSource(Http))
    fun byId(id: MusicSourceId, neteaseApiBase: String? = null): MusicSource? = ...
}
```

## Fixtures & tests

Record real responses now (network is available) and store trimmed copies in
`core/src/test/resources/net/`:
- `netease_search.json` (2–3 songs, enough fields), `netease_lyric.json`,
  `netease_detail.json`, `qq_smartbox.json`, `qq_detail.json`, `qq_lyric.json`.
Use `Invoke-WebRequest` from PowerShell to capture them, keep only the fields
needed to stay small, and add a short comment in the test about the capture
date. **Do not** store full copyrighted lyrics files: truncate to the first
~6 lines.

Tests (`FakeHttpTransport` returning canned responses per URL) must cover:
- NetEase search parsing (title/artist/album/cover/duration), lyric parsing
  incl. translation, empty search, HTTP 500 → NetException, malformed JSON.
- QQ search → hits; resolve → album/cover/duration; lyrics; entity decoding.
- Custom NetEase API base URL construction (assert requested URLs).
- `MiniJson` edge cases (unicode escapes, nested arrays, numbers, booleans,
  malformed input).
- `Lrc` helpers incl. merge.
- `Http` real implementation is not exercised against the network in unit
  tests; optionally provide a live check ignored by default.

Add a tiny `ArtworkPayload` sanity test: JPEG/PNG sniffing from downloaded bytes
(via fake download).

## Verification

Run `tools/build.ps1 -Tasks ":core:test","--tests","com.musictag.core.net.*"` and
report green. Also do one manual live check of each endpoint from PowerShell and
report the exact URLs/status codes in your final report (the app's UI depends on
these endpoints working).
