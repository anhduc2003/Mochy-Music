# Rà soát Mochy Music — tính năng, độ ổn định, kế hoạch

> Ngày rà soát: 19/11/2026 · Phạm vi: `main` (8.4.2 build#1263) · Cách làm: đọc mã nguồn trực tiếp, mỗi kết luận đều có `file:dòng`.

## 1. Kết luận ngắn

Bộ tính năng của Mochy **đã đầy đủ và hoàn thiện hơn mức một app beta thường có**: không có màn hình giả, không có nút nào bấm vào là báo "sắp ra mắt" cho một tính năng đã quảng cáo, không có TODO/FIXME nào trong 1.083 tệp Kotlin. Những gì README và Roadmap hứa — phát nhạc, lời bài hát (10 nhà cung cấp), tải xuống, import từ Spotify/Apple/Tidal/YouTube Music, scrobble Last.fm + Libre.fm, equalizer + AutoEq, crossmix, Discord, widget, Android Auto, nhận diện nhạc, hệ thống module — đều có mã thật chạy phía sau.

Vì vậy vấn đề không nằm ở "thiếu tính năng". Ba thứ thực sự đang làm app **kém ổn định và kém mượt** là:

1. **Một lỗ đứt gãy migration có thể xoá sạch thư viện** khi người dùng nâng cấp từ bản cũ (P0, mục 3.1).
2. **Việc lưu trạng thái khi thoát không bao giờ chạy**, và tệp lưu bị ghi đè tại chỗ nên một lần bị hệ thống kill là mất cả hàng đợi lẫn vị trí (P1).
3. **Vòng lặp khung hình không bao giờ dừng** ở màn lời bài hát, khiến app không bao giờ nghỉ — kể cả khi đã tắt màn hình (P1).

PR đi kèm sửa 9 lỗi dưới đây và thêm test khoá lại lỗ P0. Mục 5 là kế hoạch cho phần còn lại.

## 2. Tình trạng tính năng

### Hoàn thiện (đã đọc mã, không phải chỉ đọc README)

| Nhóm | Trạng thái | Bằng chứng |
|---|---|---|
| Phát nhạc | Media3/ExoPlayer + nguồn tự phân giải đa nhà cung cấp, fallback khi decoder/nguồn lỗi, retry có giới hạn, tự chuyển bài khi offline | `playback/MusicService.kt` (5.564 dòng), `playback/custom/DynamicResolvingMediaSource.kt` |
| Crossmix / crossfade | Phân tích beat thật, ramp tempo theo phách, dịch giọng bán cung, nối liền không khoảng lặng | `crossmix/CrossmixPlanner.kt`, `CrossmixAnalysisRepository.kt`, `playback/CrossmixDirector.kt` |
| Lời bài hát | 10 provider, đồng bộ tự động, karaoke theo từng từ, nhiều style (Neon/Mask/Glow), lyric nổi, lyric trên status bar, lyric trên Android Auto | `lyrics/`, `providers/lrclib|betterlyrics|musixmatch|genius|…`, `floating/`, `car/CarLyrics.kt` |
| Thư viện | Room, quét nhạc cục bộ, playlist (bìa, mô tả, sắp xếp lại), lịch sử, thống kê + recap | `db/`, `playlistcover/`, `ui/screens/recap/` |
| Import / đồng bộ | CSV, Spotify, Apple Music, Tidal, YouTube Music, TuneMyMusic; xuất/nhập toàn bộ dữ liệu | `importer/` (kể cả `importer/account/`, `importer/transfer/`) |
| Scrobble | Last.fm + Libre.fm, hàng đợi bền (`AtomicFile`), worker có backoff, quy tắc 50%/4 phút | `scrobble/Scrobbler.kt`, `ScrobbleQueue.kt`, `ScrobbleTimer.kt` |
| Âm thanh | Equalizer tham số + AutoEq, reverb, replay gain, haptics, audio pass, speed/pitch riêng từng bài | `eq/`, `eq/autoeq/`, `eq/audio/`, `playback/SongTuning.kt` |
| Nhận diện nhạc | Ghi 10 s, tạo fingerprint, gọi ShazamKit thật | `recognition/MusicRecognitionService.kt`, `providers/shazamkit/` |
| Nền tảng | Widget (2 loại), Android Auto, TV ↔ điện thoại, hệ thống module/plugin, 72 ngôn ngữ, updater, crash log | `widget/`, `car/`, `link/`, `modules/`, `res/values-*` |
| Hiệu ứng | Blur có cache theo cấp GPU, AGSL shader, custom background, icon riêng | `ui/component/BlurEffectCache.kt`, `GlassKit.kt`, `AgslShader.kt` |

### Chưa trọn vẹn

| Việc | Bằng chứng |
|---|---|
| Nút "tự động lấy metadata cho nhạc cục bộ" không có tác dụng — không có nơi nào đọc khoá này ngoài chính màn cài đặt | `ui/screens/settings/AppBehaviourSettingsScreen.kt:227,240`, khoá `LocalSongAutoMetadataKey` chỉ xuất hiện 2 lần trong toàn bộ mã |
| Build tự biên dịch (không có `LASTFM_API_KEY`) vẫn hiện mục Last.fm, bấm vào chỉ nhận lỗi thô của dịch vụ — trong khi README hứa "chỉ tính năng tương ứng bị tắt" | `ui/screens/settings/integrations/IntegrationsScreen.kt:40`, `app/build.gradle.kts:37-40` |
| Khoảng 194 chuỗi tiếng Việt viết thẳng trong Kotlin, bỏ qua hệ thống i18n — người dùng ngôn ngữ khác vẫn thấy tiếng Việt | ví dụ `ui/screens/ComingSoonScreen.kt:83-96`, `ui/screens/playlist/AddMusicSheet.kt`, `importer/LinkImporter.kt` |
| Crossfade + reverb vẫn chưa hoà: hai đường reverb độc lập không bao giờ gặp nhau | `eq/audio/ReverbAudioProcessor.kt` vs `crossmix/CrossmixStyles.kt:29-32` |
| `Roadmap.md` lỗi thời: nhiều mục ghi "chưa xong" nhưng đã xong (Last.fm, tìm kiếm trong thư mục tải về, playlist mô tả…) | `Roadmap.md` so với `scrobble/`, `ui/screens/settings/storage/ManageDownloadsSheet.kt:220` |

### Không có tính năng nào chỉ được quảng cáo mà không có mã

Đã đối chiếu từng mục của Roadmap 8.1.x và từng gạch đầu dòng của README với một tệp cụ thể triển khai nó: không mục nào trống. Ngoại lệ duy nhất là lời hứa "cài bản mới đè lên bản cũ, thư viện vẫn giữ nguyên" — chính là thứ lỗ P0 bên dưới phá vỡ.

## 3. Lỗi tìm được

Mức độ: **P0** mất dữ liệu · **P1** người dùng thấy rõ / hao pin / ANR · **P2** nhỏ nhưng thật.
Cột "PR" đánh dấu lỗi đã sửa trong lần này.

### 3.1 — P0 · Nâng cấp từ bản cũ có thể xoá sạch thư viện ✔ PR

`db/MusicDatabase.kt:109` đặt `MusicDatabaseVersion = 43`, nhưng danh sách `AllMigrations` (`:189`) chỉ có `1→2, 21→24, 22→24, 24→25, 35→36, 36→37, …, 42→43`. Không có bước nào đi qua khoảng **25 → 35**.

Hệ quả tính được chính xác: chỉ schema **35–42** đi được tới 43. Mọi máy đang ở schema ≤ 34 — kể cả **24 và 25**, là hai mốc mà danh sách có migration *đi tới* — không có đường tới 43, và `fallbackToDestructiveMigration(dropAllTables = true)` (`:161`, `di/AppModule.kt:53`) sẽ **xoá toàn bộ bảng** thay vì báo lỗi. Người dùng mất nhạc, playlist, lượt thích, lịch sử nghe, không một hộp thoại nào.

Đây không phải suy đoán: logic đó đã có sẵn trong repo (`importer/transfer/TransferImport.kt` dùng `hasMigrationPath` để từ chối bản export cũ), chỉ là đường mở database thật thì không kiểm tra. Chính chú thích ở `MusicDatabase.kt:181-188` cũng mô tả tai nạn này đã từng xảy ra một lần.

**PR làm gì:** thêm `db/PreUpgradeBackup.kt` — trước khi Room mở, đọc `user_version` của tệp; nếu không có đường migration tới 43 thì sao tệp (kèm `-wal`/`-shm`) sang một bản `.bak` rồi mới cho mở. Thư viện không còn bị xoá *không thể cứu*. Thêm `MigrationPathTest` khoá đúng hiện trạng để lỗ không thể lớn thêm mà không ai biết.

**Việc còn lại (P0 thật sự):** viết `MIGRATION_25_35` đưa schema 25 lên 35. Việc này cần biết chính xác 26–34 đã thêm gì; người nắm lịch sử schema là tác giả gốc. Nếu không truy được, cách an toàn là một migration "catch-up" tạo đúng schema hiện tại rồi để 36→43 tự no-op (chúng đều đã có `IF NOT EXISTS` / kiểm tra `PRAGMA table_info`), kèm test chạy `MigrationTestHelper` trên một máy ảo Android thật.

### 3.2 — P1 · Lần lưu hàng đợi cuối cùng không bao giờ chạy ✔ PR

`playback/MusicService.kt` `onDestroy` huỷ scope (`scope.coroutineContext[Job]?.cancel()`) **trước** khi gọi `saveQueueToDisk()`, mà hàm này lại ghi bằng `scope.launch(Dispatchers.IO)`. Một `launch` trên cha đã huỷ thì khởi tạo ở trạng thái cancelled: thân hàm không chạy, không lỗi, không log. Toàn bộ khối lưu ở `onDestroy` là mã chết.

Hệ quả: vuốt tắt app giữa bài thì lần mở sau quay về mốc của lần lưu định kỳ 10 giây trước, và mọi thay đổi hàng đợi trong khoảng đó mất.

**PR:** lưu đồng bộ (chờ ghi xong) ngay đầu `onDestroy`, trước khi huỷ job.

### 3.3 — P1 · Tệp hàng đợi ghi đè tại chỗ, hỏng một tệp là xoá cả bốn ✔ PR

Bốn tệp trạng thái được ghi bằng `outputStream()` — cắt tệp cũ về 0 byte rồi mới ghi đối tượng vào. Trong khi đó đường đọc gặp `EOFException` sẽ gọi `clearPersistedQueueFiles()` và xoá **cả bốn** tệp: hàng đợi, automix, dấu bài thêm tay, và vị trí đang phát.

Hai lỗi cộng lại: app bị hệ thống kill đúng lúc đang ghi (rất dễ xảy ra vì ghi mỗi 10 giây) → lần mở sau mất sạch phiên nghe, không chỉ mất vị trí.

**PR:** ghi ra `<tên>.tmp`, `fd.sync()`, rồi `rename` đè lên tệp thật; đường đọc chỉ xoá đúng tệp hỏng.

### 3.4 — P1 · Service tự bind một `MediaController` và không bao giờ nhả ✔ PR

`playback/MusicService.kt:900-905`: dựng `MediaController` trỏ vào chính service, lấy kết quả ra rồi bỏ đi — không giữ tham chiếu, `onDestroy` không `release()`. Một controller còn sống là một client còn bind: `stopSelf()` không huỷ được service còn client, nên media session, notification, hai vòng lặp cache và ~25 collector trong `onCreate` vẫn nằm trong bộ nhớ sau khi ngừng phát.

**PR:** giữ controller trong một field và `release()` ở `onDestroy`.

### 3.5 — P1 · Mỗi dòng lyric giữ một callback khung hình vĩnh viễn ✔ PR

`ui/player/LyricsGlowLine.kt:2050` chạy `while (true) { withFrameNanos { … } }`. Tham số để tắt vòng này (`animationRunning`) tồn tại và đã được dùng đúng ở overlay lời bài hát trên status bar, nhưng chưa ai truyền vào từ màn lời bài hát chính. Vì vậy **mỗi dòng đang hiển thị** (khoảng 8–10 dòng) giữ một yêu cầu khung hình, và `positionProvider()` được gọi mỗi dòng mỗi khung.

Hệ quả: mở màn lời bài hát rồi tạm dừng nhạc, hoặc tắt màn hình, app **vẫn vẽ ở tần số quét màn hình** — không bao giờ nghỉ. Đây là hao pin thật, không phải lý thuyết.

**PR:** khi player đang dừng *hoặc* app không còn ở trên màn hình (`PlayerConnection.isUiVisible`), chuyển sang nhịp 250 ms/lần — vẫn bám kịp khi người dùng tua, nhưng chỉ 4 lần/giây thay vì 60–120.

### 3.6 — P2 · Scope của service là `Job` thường, một collector lỗi kéo sập tất cả ✔ PR

`playback/MusicService.kt:409`. Không phải `SupervisorJob`, và các collector chạy bằng `scope.launch { collect { … } }` không có `CoroutineExceptionHandler`. Một ngoại lệ từ bất kỳ collector nào (đường ghi lyric, làm mới thẻ bài hát…) huỷ **mọi** job anh em: queue runway, retry, sleep timer, lưu trạng thái — rồi ném tiếp ra handler mặc định và giết tiến trình.

**PR:** đổi sang `SupervisorJob()`.

### 3.7 — P2 · Xoá cache stream chạy trên main thread ✔ PR

`onPlayerError` (thread chính) gọi thẳng `performAggressiveCacheClear(mediaId)` → `playerCache.removeResource()` (xoá tệp, đồng bộ theo lock của cache), và `dropModuleCache()` quét toàn bộ khoá cache rồi xoá. Ba handler retry cũng làm vậy trong `scope.launch` với dispatcher `Main`.

Tác giả đã biết đúng cái nguy này ở đường đổi chất lượng âm thanh — `MusicService.kt:993-999` có ghi chú "dropping them under it blocked the main thread" và đã chuyển sang `withContext(Dispatchers.IO)`. Đường xử lý lỗi thì chưa. Lỗi stream hay xảy ra liên tiếp, nên đây là nguy cơ ANR đúng lúc app đang phải chạy toast, chuyển bài và crossfade.

**PR:** `suspend` hoá hai hàm, đẩy việc xoá sang `Dispatchers.IO`, đường `onPlayerError` gọi qua `scope.launch`.

### 3.8 — P2 · Đếm ngược "bỏ bài lỗi" không bị huỷ khi người dùng tua ✔ PR

`failStream()` đặt `streamFailure` + một job đếm ngược; job này chỉ bị huỷ khi `onMediaItemTransition` hoặc `playbackState != IDLE`. Khi bài lỗi đang `IDLE` mà người dùng kéo seek, trạng thái vẫn `IDLE` nên không có gì huỷ, và vài giây sau `skipAfterFailure()` nhảy sang bài khác **rồi tự phát** — người dùng thấy như app tự đổi bài.

**PR:** `PlayerConnection.seekTo()` (đường duy nhất người dùng chạm vào) báo cho service huỷ đếm ngược.

### 3.9 — P2 · `SELECT COUNT(*)` trên bảng `event` chỉ để biết "có hay không" ✔ PR

`MainActivity.kt:919` và `HomeViewModel.kt:925` cần một câu trả lời có/không, nhưng `DatabaseDao.eventCount()` chạy `SELECT COUNT(*) FROM event` — quét cả bảng, và bảng này dài ra theo mỗi bài nghe. Flow này còn được thu ở cấp Activity nên Room quét lại mỗi lần ghi sự kiện, kể cả khi tắt màn hình.

**PR:** `SELECT EXISTS(SELECT 1 FROM event)` — dừng ở dòng đầu tiên.

### 3.10 — P2 · Hai cache stream dùng collection không thread-safe ✔ PR

`songUrlCache` là `HashMap` còn `bypassCacheForQualityChange` là `mutableSetOf`, nhưng cả hai được đọc/ghi từ lambda `ResolvingDataSource` — tức thread loading của Media3 — song song với việc xoá từ main. `HashMap` bị ghi trong lúc resize có thể làm mất hoặc trả sai phần tử, nghĩa là URL vừa phân giải xong có thể biến mất (phát lại URL hết hạn → 403) — đúng loại lỗi khó tái hiện.

**PR:** `ConcurrentHashMap` / `newKeySet()`, giống các cache bên cạnh.

### 3.11 — Danh sách lỗi chưa sửa (nằm trong kế hoạch, mục 5)

| Mức | Lỗi | Vị trí |
|---|---|---|
| P1 | Hàng gợi ý được chèn vào giữa danh sách Home **sau khi** người dùng đã cuộn, không có placeholder giữ chỗ nên nội dung nhảy | `ui/screens/HomeScreen.kt:1169-1176`, `viewmodels/HomeViewModel.kt:719-731` |
| P1 | Lời bài hát nổi hỏi quyền overlay mỗi giây một lần, mãi mãi, khi người dùng từ chối quyền | `floating/FloatingLyrics.kt:97-106` |
| P2 | Ba lưới "Nghe nhiều / Đừng quên / Lịch sử" nhảy về đầu mỗi lần tự làm mới | `ui/screens/HomeScreen.kt:1219-1229` |
| P2 | `HeroCarousel` thu trạng thái phát ở cấp pager khiến cả 3 trang dựng lại mỗi lần đổi bài | `ui/component/HeroCarousel.kt:657-658` |
| P2 | Thêm bài vào playlist dùng `songCount` thay vì `MAX(position)+1` nên bài mới chen vào giữa sau khi có bài bị xoá | `db/DatabaseDao.kt:1244-1256` |
| P2 | Cache phân tích crossmix không bao giờ dọn — vài nghìn bài là vài chục MB | `crossmix/CrossmixAnalysisRepository.kt:36` |
| P2 | `CrashHandler` tự `killProcess` nên không hiện hộp thoại "ứng dụng bị dừng" và crash không bao giờ được ghi nhận | `utils/CrashHandler.kt:29-33` |
| P2 | `try/catch` ở `App.kt` bọc một hàm chỉ *xếp hàng* vào executor nên không bắt được gì | `App.kt:92-98`, `db/MusicDatabase.kt:79` |
| P2 | Cache bản dịch lyric khoá theo `String.hashCode()` — hai bài khác nhau trùng hash sẽ hiện lời của nhau — và không giới hạn kích thước | `lyrics/LyricsTranslationHelper.kt:56` |
| P2 | `rememberPreference` có thể chặn main thread đọc DataStore ở lần dựng đầu tiên khi cache chưa ấm | `utils/DataStore.kt:64-70` |
| P2 | ~194 chuỗi tiếng Việt hard-code, bỏ qua i18n | nhiều tệp |
| P1 | **Cổng test đang đỏ sẵn**: chạy `:app:testUniversalFossDebugUnitTest` trên `main` chưa sửa gì thì `LanguageResourcesTest` fail — nó so danh sách trong `values/app_language_tags.xml` (53 tag) với cây tài nguyên thật (90 thư mục `values-*` có chuỗi). `:app:lintUniversalFossDebug` cũng fail sẵn (xem mục 4). Cả hai đều có từ trước lần sửa này | `app/src/test/kotlin/com/mochyy/mochymusic/i18n/LanguageResourcesTest.kt:44` |

## 4. Vì sao lỗi tìm được ở 3.2–3.10 không hiện trong CI

CI hiện chạy `assemble` + unit test + lint. Ba loại lỗi trên đều **không** thuộc ba phạm vi đó: chúng chỉ hiện khi app bị kill (3.2, 3.3), khi service sống lâu (3.4, 3.6), khi tắt màn hình (3.5), hay khi mạng lỗi thật (3.7, 3.10). Unit test không chạm tới vì phần lớn mã playback phụ thuộc `ExoPlayer`.

Đề xuất bổ sung dần (mục 5, đợt 3): macrobenchmark + baseline profile cho startup và cuộn Home, và một test `MigrationTestHelper` chạy trên thiết bị cho toàn bộ chuỗi migration. Đây là hai loại test bắt được đúng những lỗi vừa nêu.

Riêng cổng test hiện tại thì đang **đỏ sẵn** (đo bằng cách bỏ hết thay đổi rồi chạy lại đúng hai lệnh của CI trên máy này): `LanguageResourcesTest` (`:44`) fail ngay trên `main` chưa sửa gì, và `lintUniversalFossDebug` báo 19 lỗi có sẵn — trong đó `CrashActivity` được khai trong manifest nhưng **không tồn tại lớp nào tên đó**, và khoảng 14 lỗi `NewApi` gọi API 26–33 không có chặn phiên bản (ứng dụng hỗ trợ từ API 24, nên đây là nguy cơ crash thật trên máy Android 7–11 chứ không phải cảnh báo hình thức). Nguyên nhân test i18n: cây tài nguyên có 90 thư mục `values-*` có chuỗi (bản fork thêm nhiều thư mục theo vùng, ví dụ vừa `values-de` vừa `values-de-rDE`) trong khi `values/app_language_tags.xml` chỉ liệt kê 53 tag. Cần một quyết định của người bảo trì trước khi sửa, vì hai hướng cho ra hai sản phẩm khác nhau:

- **Bổ sung danh sách** cho đủ 90 tag → picker hiện cả "Tiếng Đức" và "Tiếng Đức (Đức)", thừa nhưng đúng với cây tài nguyên.
- **Gộp theo ngôn ngữ** trong test (chuẩn hoá `de-rDE` về `de`) → picker giữ 53 ngôn ngữ, và Android tự chọn biến thể vùng phù hợp máy.

Trong cả hai hướng, đều phải sửa *hoặc* dữ liệu *hoặc* định nghĩa của test, nên việc này không tự quyết trong PR này.

## 5. Kế hoạch

### Đợt 1 — ổn định, đã làm trong PR này ✔

Sửa 3.1–3.10, thêm `MigrationPathTest`. Rủi ro thấp, chỉ tác động đường lỗi và đường thoát.

### Đợt 2 — mượt và pin (1–2 tuần)

1. **Lấp lỗ migration 25 → 35** (P0 còn lại). Cần xác nhận schema 26–34; sau đó `PreUpgradeBackup` trở thành lưới an toàn thay vì lá chắn duy nhất.
2. **Home không nhảy**: dựng hàng placeholder giữ chỗ cho các vị trí gợi ý trước khi dữ liệu về (`HomeScreen.kt:1169`), và giữ vị trí cuộn qua mỗi lần refresh (`:1219`).
3. **HeroCarousel**: đưa `collectAsState` xuống từng trang.
4. **Lời bài hát nổi**: thay vòng `while (!canDrawOverlays) delay(1000)` bằng kiểm tra một lần + kiểm lại khi quay về app; nếu vẫn phải chờ thì backoff và bỏ sau ~30 giây, kèm thông báo chỉ đường vào cài đặt.
5. **Cache**: giới hạn dung lượng `crossmix-analysis`; đổi khoá cache bản dịch lyric sang SHA-256 và chặn kích thước.
6. **Đo lại**: macrobenchmark startup + cuộn Home trước/sau, để có số thật thay vì cảm nhận.

### Đợt 3 — hoàn thiện tính năng (2–4 tuần)

1. **i18n**: chuyển ~194 chuỗi tiếng Việt hard-code sang `strings.xml` (chỉ cần `values/` tiếng Anh + `values-vi-rVN`; các ngôn ngữ khác tự lùi về tiếng Anh). Ưu tiên màn hình người dùng thấy nhiều: `ComingSoonScreen`, `AddMusicSheet`, `UpdatePromptSheet`, `LinkImporter`, `ImportNotices`.
2. **Ẩn Last.fm khi build không có khoá** — đúng như README đã hứa.
3. **Nối dây hoặc bỏ** nút "tự động lấy metadata cho nhạc cục bộ" (hiện không có consumer).
4. **Crossfade + reverb**: cho crossmix dùng chung đường reverb với Audio FX thay vì hai đường độc lập.
5. **`CrashHandler`**: gọi lại handler hệ thống sau khi ghi log, để người dùng thấy hộp thoại và crash vào được thống kê của Google Play.
6. **`Roadmap.md`**: viết lại theo đúng thực tế đã kiểm ở mục 2.

### Không nên làm

- Dồn `collectAsState()` → `collectAsStateWithLifecycle()` toàn bộ 507 chỗ: các destination trong `NavHost` rời composition khi bị điều hướng đi, nên phần lớn là tương đương. Chỉ đáng đổi ở những chỗ thu từ cấp Activity/Scaffold, và cái duy nhất đáng đổi đã nằm trong đợt 1 (3.9).
- Thêm abstraction/refactor `MusicService.kt` (5.564 dòng) chỉ vì nó dài. File dài nhưng mạch lạc; cắt nhỏ một lớp đang chạy ổn là rủi ro không đổi lấy được gì.

## 6. Kiểm chứng PR này

Chạy được trên máy không có thiết bị Android:

```bash
./gradlew :app:compileUniversalFossDebugKotlin   # biên dịch
./gradlew :app:testUniversalFossDebugUnitTest    # unit test, gồm MigrationPathTest
./gradlew :app:lintUniversalFossDebug            # lint như CI
```

Ba nhóm việc dưới đây **cần một máy thật**, máy ảo không mô phỏng được:

1. Vuốt tắt app giữa bài, mở lại → hàng đợi và vị trí đúng chỗ đã tắt (3.2, 3.3).
2. Nâng cấp từ một bản có schema cũ lên bản này → kiểm tra tệp `mochy.db.from*.bak` xuất hiện trong `databases/` (3.1).
3. Mở màn lời bài hát, tạm dừng nhạc, tắt màn hình 10 phút → xem pin trong *Battery usage* giảm so với bản trước (3.5).

## 7. Cài đè thay vì gỡ rồi cài lại

### Vì sao phải gỡ

App đã xin cài đè đúng cách từ trước (`ACTION_VIEW` + `application/vnd.android.package-archive`). Android **luôn** cài đè và giữ nguyên `files/`, database, cài đặt — chỉ khi **cùng package và cùng khoá ký**. Việc gỡ chỉ xảy ra vì một trong hai bị lệch.

Trong repo này, khoá ký lệch mỗi lần build:

- `.github/workflows/release-apk.yml:49` và `build_pr.yml:32` chạy `keytool -genkeypair` mỗi lần workflow chạy, ghi vào `~/.android/debug.keystore`. Mỗi lần là một cặp khoá **mới**.
- `app/build.gradle.kts` cấu hình `debug` (và `fast`, `profileable`, và release khi thiếu secret) trỏ vào `~/.android/debug.keystore` đó.
- Kết quả: APK của lần build này và lần sau có chữ ký khác nhau. Android coi là hai ứng dụng khác nhau → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → buộc phải gỡ, và gỡ là mất toàn bộ thư viện. Chính `release-apk.yml:151` cũng đã ghi đúng điều này trong phần mô tả bản phát hành.

### Đã làm

Triển khai ở PR riêng (`feat/install-over`), để PR rà soát này chỉ còn phần sửa lỗi.

**Trong app** (`core/updater/UpdateInstaller.kt`, `UpdateInstallReceiver.kt`):

1. Đọc APK tải về bằng `getPackageArchiveInfo` và so với bản đang cài: tên package, **chữ ký (SHA-256)**, và version code — trước khi mở bất cứ hộp thoại nào.
2. Cài bằng `PackageInstaller` với `SessionParams.MODE_FULL_INSTALL` — đúng chế độ cài đè, thay mã và **giữ nguyên dữ liệu**. Có `setRequireUserAction(USER_ACTION_REQUIRED)` nên vẫn hiện hộp thoại xác nhận của hệ thống.
3. Nhận kết quả qua `UpdateInstallReceiver`, nên lần đầu tiên mọi lý do từ chối đều hiện ra: khoá khác, tệp hỏng, hết dung lượng, bị chặn. Trước đây chỉ có "App not installed".
4. Ba chỗ mời cài (sheet cập nhật, thông báo tải xong, `installApk`) đều đi qua đúng một đường này; câu báo lỗi giờ nói đúng nguyên nhân thay vì luôn đổ cho quyền "cài ứng dụng không rõ nguồn gốc".
5. Bấm thông báo tải xong vẫn chạy được khi app đang đóng: mọi lỗi được đăng thành thông báo, không chỉ toast.

**Trong build** (để cài đè thật sự chạy):

6. `app/build.gradle.kts` dùng `app/keystore/persistent-debug.keystore` cho `debug`, `fast`, `profileable` và release khi không có secret; thiếu tệp thì lùi về khoá debug của máy như cũ.
7. Hai workflow giải mã secret `MOCHY_KEYSTORE_B64` vào đúng tệp đó. Không có secret thì vẫn build được nhưng in cảnh báo rằng APK này không cài đè được lên bản khác.
8. `scripts/persistent-signing-key.sh` tạo khoá một lần và in ra base64 để dán vào secret.

### Việc cần bạn làm một lần

```bash
./scripts/persistent-signing-key.sh --print
```

Dán chuỗi base64 vào **Settings → Secrets and variables → Actions → New repository secret**, tên `MOCHY_KEYSTORE_B64`. Từ đó mọi APK của repo dùng chung một chữ ký.

Lưu ý: bản đang cài trên máy bạn hiện mang khoá cũ ngẫu nhiên, nên **lần đầu vẫn phải gỡ** — sau đó thì mọi bản sau cài đè được. Nên xuất bản sao lưu (Cài đặt → Nhập/Xuất) trước lần gỡ đó; giữ tệp khoá ở nơi an toàn, mất khoá là mất khả năng cài đè cho tất cả các bản sau.
