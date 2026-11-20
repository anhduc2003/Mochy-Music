# Rà soát Mochy Music — trạng thái, bản sửa lần này, kế hoạch

> Ngày rà soát: 19/11/2026 · Phạm vi: `main` (8.4.2 build#1263) cộng các thay đổi của lần này
> Cách làm: đọc mã nguồn, biên dịch và chạy unit test trên một máy **không có thiết bị Android**.
> Mỗi kết luận đều kèm `tệp:dòng` để đối chiếu.

## 1. Kết luận ngắn

Bộ tính năng của Mochy đã rất đầy đủ so với một app beta: phát nhạc, lời bài hát đa nguồn, tải
xuống, import từ nhiều dịch vụ, scrobble, equalizer, crossmix, widget, Android Auto, nhận diện nhạc
và cả hệ thống module. Không có màn hình giả, không có nút "sắp ra mắt" nào đi tới được từ giao diện.

Ba thứ đang thực sự làm app kém ổn định, và đều đã sửa trong lần này:

1. **Một lỗ đứt gãy migration có thể xoá sạch thư viện** khi nâng cấp (`db/MusicDatabase.kt:216`).
   Danh sách migration không có bước nào đi qua khoảng 25 → 35, mà database lại mở bằng
   `fallbackToDestructiveMigration`, nên mọi máy đang ở schema 26–34 bị **xoá toàn bộ bảng** thay
   vì báo lỗi. Nay file đó được **dựng lại theo đúng schema hiện tại, giữ nguyên dữ liệu**.
2. **Màn hình sự cố được khai trong manifest nhưng lớp không tồn tại**: `AndroidManifest.xml:92`
   trỏ tới `.ui.screens.CrashActivity`, chuỗi `crash_title`/`crash_share_logs`/… đã có đủ trong
   `res/values/mochy_strings.xml`, nhưng không có tệp Kotlin nào. Đây cũng là một lỗi lint, tức là
   cổng lint của repo đang đỏ sẵn. Nay màn hình đó đã có.
3. **Cổng test của repo đang đỏ sẵn**: `LanguageResourcesTest` so danh sách ngôn ngữ trong
   `values/app_language_tags.xml` với cây tài nguyên thật và luôn thất bại trên `main` chưa sửa gì
   (đã dựng lại để chứng minh, xem mục 5). Nay đã xanh.

## 2. Đã sửa trong lần này

### 2.1 — Thư viện không còn bị xoá khi nâng cấp từ bản cũ (P0)

- `db/SchemaRepair.kt` (mới): trước khi Room mở file, nếu phiên bản trong file **không có đường đi**
  tới `MusicDatabaseVersion` thì file được dựng lại cho khớp schema hiện tại.
  Mô tả schema hiện tại **không chép tay**: Room được cho tạo một database rỗng
  (`db/SchemaRepair.kt:57`), rồi đọc `sqlite_master` của chính nó — nên bản mô tả này không thể lệch
  khỏi các entity. Mỗi bảng được tạo lại dưới tên tạm, chép các cột mà cả hai phiên bản đều có, xoá
  bảng cũ và đổi tên; cột mới bắt buộc có giá trị thì được điền giá trị rỗng đúng kiểu. Cuối cùng
  `room_master_table` nhận `identity_hash` của schema hiện tại, nếu không Room sẽ từ chối file với
  lỗi "đổi schema mà quên tăng version".
- `db/PreUpgradeBackup.kt` (mới): luôn sao file ra `<tên>.from<version>.bak` trước khi dựng lại, và
  **đưa bản sao trở lại** nếu việc dựng lại ném lỗi giữa đường (`db/SchemaRepair.kt:49`), để thất
  bại để lại đúng trạng thái cũ. Chỉ giữ một bản sao cho mỗi database.
- Gọi ở cả hai chỗ mở database: `db/MusicDatabase.kt:157` và `di/AppModule.kt:51` (chỗ thứ hai là
  đường thật của app, và cũng là TV profile nên tên file khác nhau).
- `hasMigrationPath` được chuyển từ `importer/transfer/TransferImport.kt` về cạnh `AllMigrations`
  (`db/MusicDatabase.kt:216`) — database và đường import giờ đọc **một** danh sách.

### 2.2 — Màn hình báo lỗi đã có (P1)

- `ui/screens/CrashActivity.kt` (mới): hiện báo cáo, cho sao chép và chia sẻ, chạy trong tiến trình
  `:crash` mà manifest đã khai, nên nó sống lâu hơn tiến trình vừa chết.
- `utils/CrashHandler.kt`: ghi log, mở màn hình đó, rồi mới kết thúc tiến trình; nếu không mở được
  thì trả lỗi lại cho handler mặc định của hệ thống để người dùng vẫn thấy hộp thoại quen thuộc.
- `App.kt:78`: tiến trình `:crash` **không** chạy phần khởi tạo còn lại (database, player, PoToken).
  Trước đây màn hình lỗi sẽ kéo theo đúng những thứ vừa gây ra lỗi.

### 2.3 — Bài mới thêm vào playlist nằm đúng cuối danh sách (P2, thấy rõ)

`db/DatabaseDao.kt:1258` tính vị trí thêm mới từ `playlist.songCount`. Sau khi xoá một bài, các vị
trí có lỗ hổng, nên `songCount` trỏ vào chỗ một bài khác đang giữ và bài mới **chen vào giữa**.
Nay vị trí lấy từ `MAX(position) + 1` (`db/DatabaseDao.kt:1242`), và hàm trả về vị trí đầu tiên để
nút **Hoàn tác** (`ui/menu/AddToPlaylistDialog.kt`) xoá đúng những dòng vừa thêm.

### 2.4 — Những chỗ hao pin và hao bộ nhớ

| Việc | Trước | Sau |
|---|---|---|
| Lời bài hát nổi xin quyền | `while (!canDrawOverlays) delay(1000)` chạy mãi cho hết bài (`floating/FloatingLyrics.kt:115`) | chờ tối đa 30 s rồi thôi, bài sau hoặc lần quay lại app sẽ thử lại |
| Cache phân tích crossmix | không bao giờ dọn (`crossmix/CrossmixAnalysisRepository.kt:193`) | giữ tối đa 48 MB, xoá cũ nhất trước |
| Cache bản dịch lời | khoá bằng `String.hashCode()` — 32 bit nên hai bài khác nhau có thể trùng khoá và hiện lời của nhau; không giới hạn số bài (`lyrics/LyricsTranslationHelper.kt:71`) | khoá bằng SHA-256, giữ 64 bài gần nhất |
| Cuộn trên Home | mỗi lần thư viện đổi là ba lưới nhảy về đầu (`ui/screens/HomeScreen.kt:1222`) | chỉ về đầu khi kệ hàng từ trống thành có (`ui/screens/HomeScreen.kt:2079`) |

### 2.5 — Cổng test i18n

- `res/values/app_language_tags.xml`: thêm `af`, `da`, `no` — ba ngôn ngữ **có** bản dịch trong cây
  tài nguyên nhưng không có mục nào trong danh sách chọn, nên người dùng ba ngôn ngữ đó không chọn
  được tiếng của mình.
- `test/…/i18n/LanguageResourcesTest.kt`: so theo **ngôn ngữ**, không theo tag đầy đủ. Bản cũ so
  từng tag một, mà cây tài nguyên có cả `values-de` lẫn `values-de-rDE`, buộc danh sách chọn phải
  có hai mục "Tiếng Đức" và "Tiếng Đức (Đức)". So theo ngôn ngữ vẫn bắt được đúng thứ cần bắt — thêm
  thư mục ngôn ngữ mà quên thêm mục chọn thì test đỏ — mà không đòi một danh sách vô nghĩa.

### 2.6 — Hai chỗ gọi API cao hơn `minSdk` (P1, crash thật trên máy cũ)

Lint báo 12 lỗi `NewApi`; phần lớn là báo nhầm vì chỗ chặn phiên bản nằm ở tầng trên, nhưng hai chỗ
là crash thật:

- `ui/player/styles/ExpressiveText.kt:1114` — `Shader.TileMode.DECAL` chỉ có từ Android 12. Tên field
  được phân giải **trước khi** `BlurEffectCache.get` kịp trả `null` cho máy cũ, nên vẽ một dòng lời
  có blur trên Android 11 trở xuống ném `NoSuchFieldError` ngay trong lượt vẽ. Nay chọn tile mode
  sau khi kiểm phiên bản.
- `playback/audio/BluetoothCodecs.kt:141` — `AudioDeviceInfo#getAddress` chỉ có từ Android 9, mà
  Android 8 là mức hỗ trợ chính thức. Nay bỏ qua việc so địa chỉ trên máy cũ (nó chỉ để phân biệt
  hai tai nghe), dùng thiết bị đầu tiên như nhánh dự phòng sẵn có.

### 2.7 — Cài đè bản mới mà không mất thư viện (tính năng đã land)

Lỗ hổng 2.1 chỉ là một nửa của chuyện "cập nhật mà không mất dữ liệu". Nửa còn lại là chữ ký: APK chỉ
cài đè được lên bản cũ khi **cùng package và cùng khoá ký**, mà mỗi lần CI chạy lại sinh một khoá mới,
nên mỗi bản phát hành là một ứng dụng khác với Android và cách duy nhất để đi tiếp là gỡ — mất sạch.

Giờ đã có:

- `core/updater/UpdateInstaller.kt`: đọc APK tải về (tên package, dấu vân tay SHA-256 của chứng chỉ
  ký, versionCode) **trước khi** đưa cho hệ thống, và nói rõ vì sao không cài đè được thay vì để lại
  một câu "App not installed". Việc cài đi qua `PackageInstaller` với `MODE_FULL_INSTALL`, đúng chế
  độ thay mã và giữ `files/`, database, cài đặt.
- `core/updater/UpdateInstallReceiver.kt`: nhận câu trả lời của hệ thống, kể cả khi nó tới lúc Mochy
  đang bị thay — đúng lúc ấy app không còn màn hình nào để hiện thông báo.
- `app/build.gradle.kts` + hai workflow: mọi biến thể ký bằng một khoá duy nhất ở
  `app/keystore/persistent-debug.keystore` khi tệp đó tồn tại; workflow giải mã secret
  `MOCHY_KEYSTORE_B64` vào đó, thiếu secret thì vẫn build được nhưng phần mô tả bản phát hành nói rõ
  là bản này **không** cài đè được. `scripts/persistent-signing-key.sh` tạo khoá một lần.

> **Việc cần làm một lần, phía bạn:** chạy `./scripts/persistent-signing-key.sh --print`, dán chuỗi
> base64 vào Settings → Secrets and variables → Actions, tên `MOCHY_KEYSTORE_B64`. Bản đang cài trên
> máy bạn mang khoá cũ ngẫu nhiên nên **lần đầu vẫn phải gỡ**; từ đó về sau mọi bản cài đè được.
> Nhớ xuất bản sao lưu (Cài đặt → Nhập/Xuất) trước lần gỡ đó, và giữ tệp khoá cẩn thận.

### 2.8 — Bốn chỗ `NewApi` còn lại

- `ui/screens/settings/modules/ModulePageShaders.kt`: hàm biên dịch shader dùng `RuntimeShader`
  (API 33). Chỗ gọi **đã** chặn dưới 13, nhưng lint không nhìn qua được ranh giới hàm, nên hàm được
  đánh dấu `@RequiresApi(33)` để lint theo đúng chỗ chặn đó.
- `playback/audio/pass/PassRouterSink.kt`: `setOffloadMode`/`setOffloadDelayPadding` là API 29.
  media3 chỉ gọi khi máy có offload; chú thích nằm trên interface mà lớp này cài đặt.
- `ui/player/PlayerHandle.kt`: `PixelCopy` là API 26. Dưới 26 giờ trả `false` — đúng thứ chỗ gọi vốn
  đã hiểu là "lượt này không lấy được mẫu".
- `floating/StatusWindow.kt`: `displayCutout` được tra trong nhánh kiểm API 28 nhưng **bốn cạnh** của
  nó lại được đọc ngoài nhánh đó; nay đọc gọn trong cùng nhánh.

## 3. Tình trạng tính năng

Đã kiểm từng nhóm dưới đây xem có mã thật đứng sau không (số tệp, số dòng, đường dẫn nêu ở cột
cuối), và đối chiếu với các mục của README cùng `Roadmap.md`. **Không có tính năng nào chỉ được
quảng cáo mà không có mã.** Chỗ duy nhất hứa mà chưa làm được là một công tắc cài đặt, ghi ở dưới.

| Nhóm | Trạng thái | Bằng chứng |
|---|---|---|
| Phát nhạc | Xong | `playback/MusicService.kt` (5.564 dòng), nguồn tự phân giải + fallback + retry |
| Crossmix / crossfade | Xong | `crossmix/` (7 tệp): phân tích beat thật, ramp tempo, dịch giọng |
| Lời bài hát | Xong | `lyrics/` + 16 module trong `providers/` (lrclib, betterlyrics, kugou, musixmatch, genius, …) |
| Thư viện | Xong | `db/` (23 bảng, 3 view), playlist có bìa/mô tả/sắp xếp lại, thống kê + recap |
| Import / đồng bộ | Xong | `importer/` (39 tệp): CSV, Spotify, Apple Music, Tidal, YouTube Music, xuất/nhập toàn bộ |
| Scrobble | Xong | `scrobble/` Last.fm + Libre.fm, hàng đợi bền, worker có backoff |
| Âm thanh | Xong | `eq/` (equalizer tham số, AutoEq, reverb), replay gain, audio pass riêng |
| Nhận diện nhạc | Xong | `recognition/` + `providers/shazamkit/` |
| Nền tảng | Xong | `widget/`, `car/`, `link/` (TV ↔ điện thoại), `modules/` (5 module), 82 locale |
| Hiệu ứng | Xong | `ui/component/BlurEffectCache.kt`, AGSL shader, nền và icon riêng |

Một chỗ **chưa trọn vẹn** vẫn để nguyên, có lý do ở mục 5:

- Công tắc "Tự động lấy siêu dữ liệu cho bài hát trên thiết bị"
  (`ui/screens/settings/AppBehaviourSettingsScreen.kt:227`) được lưu và hiển thị nhưng **không nơi
  nào đọc**. Muốn nối dây thật thì phải có một đường đối chiếu bài cục bộ với metadata trực tuyến —
  hiện chưa có (`grep` cho `localSongToOnline`/`matchLocal` không ra gì). Không tự bịa ra một bản
  đối chiếu nửa vời rồi gọi là xong.

## 4. Lỗi còn lại, theo mức

Chưa sửa trong lần này, xếp theo mức độ và mức rủi ro khi sửa:

| Mức | Lỗi | Vị trí |
|---|---|---|
| P1 | `rememberPreference` có thể chặn main thread ở lần dựng đầu vì `runBlocking` đọc DataStore | `utils/DataStore.kt:65` |
| P2 | `HeroCarousel` thu trạng thái phát ở cấp pager nên cả ba trang dựng lại mỗi lần đổi bài | `ui/component/HeroCarousel.kt:657` |
| P2 | `CrashHandler` từng bỏ qua hộp thoại của hệ thống — nay đã trả lại khi không mở được màn hình lỗi | `utils/CrashHandler.kt` |
| P2 | `try/catch` ở `App.kt` bọc một lời gọi chỉ *xếp hàng* vào executor nên không bắt được gì | `App.kt:104` |
| P2 | Khoảng 188 chuỗi tiếng Việt viết thẳng trong Kotlin, bỏ qua i18n | nhiều tệp |
| P2 | 81 khoá preference được khai nhưng **không nơi nào đọc** (di sản của các tính năng đã bỏ) | `constants/PreferenceKeys.kt` |
| P2 | Build không có `LASTFM_API_KEY` vẫn hiện mục Last.fm, bấm vào chỉ nhận lỗi thô của dịch vụ | `ui/screens/settings/integrations/IntegrationsScreen.kt:40` |
| P2 | `Crossfade` + reverb vẫn là hai đường độc lập, không gặp nhau | `eq/audio/ReverbAudioProcessor.kt` vs `crossmix/CrossmixStyles.kt` |
| P2 | Nhánh `fix/stability-audit` còn giữ các bản sửa chưa vào `main`: lưu hàng đợi đúng lúc `onDestroy`, ghi tệp trạng thái nguyên tử, `SupervisorJob` cho scope của service, vòng khung hình lyric dừng khi tạm dừng. Nên tách phần sửa khỏi `lint-baseline.xml` 30.000 dòng trong nhánh đó rồi land | `playback/MusicService.kt`, `utils/AtomicObjectFile.kt`, `ui/player/LyricsGlowLine.kt` |
| — | `Roadmap.md` lỗi thời: nhiều mục ghi "chưa xong" nhưng đã xong | `Roadmap.md` |

### 4.1 — Cổng lint của repo

`:app:lintUniversalFossDebug` đo trên `main` trước đợt sửa này: **715 lỗi**. Nhóm `NewApi` (12 lỗi)
đã sửa hết ở mục 2.6 và 2.8; các nhóm còn lại:

| Số | Loại | Có nguy hiểm không |
|---|---|---|
| 413 | `ExtraTranslation` — chuỗi trong `values-*` không còn trong `values/` | không, chỉ là chuỗi chết |
| 123 + 19 | `MissingQuantity` / `ImpliedQuantity` — dạng số nhiều thiếu | không, tự lùi về bản khác |
| 12 | `NewApi` — gọi API cao hơn `minSdk` 24 | **có**, đã sửa hết (mục 2.6, 2.8) |
| 111 | `LocalContextGetResourceValueCall` — hỏi resource qua `LocalContext` trong composition | nhẹ, nhưng sửa 111 chỗ mà không xem được giao diện là rủi ro thật |
| 7 | `StringFormatMatches` — định dạng chuỗi lệch giữa các ngôn ngữ | không, `%s` nhận mọi kiểu |
| 6 + 6 + 2 + 1 + 1 | quy tắc Compose thật: `StateFlow.value` trong composition, flow operator, `launch` trong composition, cast `Context`→`Activity`, `getBackStackEntry` không `remember` | nhẹ nhưng đúng là lỗi; sửa phải xem trên máy |
| còn lại | `RestrictedApi`, `ByteOrderMark`, … | nhẹ |

Vì cổng này đỏ sẵn, **mọi pull request đều không thể xanh** — đó chính là lý do `feat/install-over`
và `fix/stability-audit` nằm chờ. `feat/install-over` đã được đưa vào `main` (mục 2.7) sau khi nhóm
`NewApi` và cổng test được sửa.

Thứ tự đề nghị cho phần còn lại: (1) ~16 lỗi quy tắc Compose, làm cùng một thiết bị thật vì chúng
đụng vào vòng đời và recomposition; (2) 111 `LocalContextGetResourceValueCall` — đổi sang
`stringResource`/`LocalResources.current`, cơ học nhưng nhiều chỗ, nên làm theo từng màn hình và xem
lại giao diện; (3) ~555 lỗi dịch thuật (`ExtraTranslation` chỉ là chuỗi chết, `MissingQuantity` cần
người biết từng ngôn ngữ). Baseline chỉ nên dùng nếu muốn cổng xanh **trước** khi dọn xong, và khi đó
phải là tạm thời, có ngày hết hạn.

## 5. Kế hoạch

### Đợt 1 — đã xong trong lần này ✔

Mục 2.1–2.5. Rủi ro thấp: chỉ chạm đường lỗi, đường thoát và dữ liệu bị bỏ quên.

### Đợt 2 — mượt và pin (1–2 tuần)

0. **Dọn cổng lint** (mục 4.1) để mọi pull request chạy được test và lint như thiết kế. `NewApi` xong;
   còn 111 `LocalContextGetResourceValueCall` + ~16 lỗi quy tắc Compose + ~555 lỗi dịch thuật. Nhóm
   Compose đụng vào vòng đời và recomposition của TV/player nên phải làm cùng một thiết bị thật.
1. **DataStore**: bỏ `runBlocking` ở lần đọc đầu, để màn hình đầu tiên không phải chờ đọc đĩa
   (`utils/DataStore.kt:65`). Đo bằng macrobenchmark khởi động nguội trước/sau.
2. **HeroCarousel**: đưa `collectAsState` xuống từng trang.
3. **Đo lại thật**: thêm macrobenchmark + baseline profile cho khởi động và cuộn Home. Từ giờ mọi
   thay đổi "cho mượt" phải có số trước và sau, không dựa vào cảm giác.
4. **Dọn 81 khoá preference chết** — hoặc nối dây, hoặc xoá. Danh sách đầy đủ lấy được bằng cách so
   `PreferenceKeys.kt` với số lần xuất hiện của từng tên trong toàn repo.

### Đợt 3 — hoàn thiện tính năng (2–4 tuần)

1. **"Tự động lấy siêu dữ liệu cho bài hát trên thiết bị"**: viết một bước đối chiếu bài cục bộ
   (tên tệp → tên bài + nghệ sĩ → tìm trên nguồn trực tuyến → ghi lại thumbnail/album/năm), chạy
   sau khi quét nhạc cục bộ, **rồi** mới nối công tắc vào. Cần thử trên máy thật vì phụ thuộc mạng.
2. **Ẩn Last.fm khi build không có khoá** — đúng như README đã hứa ("chỉ tính năng tương ứng bị
   tắt"). Cần sửa cả bốn chỗ có lối vào: `IntegrationsScreen`, `MochySettingsScreen` (3 mục tìm
   kiếm), `SettingsRecommendations`.
3. **i18n**: chuyển ~188 chuỗi tiếng Việt hard-code sang `strings.xml` (chỉ cần `values/` +
   `values-vi-rVN`; các ngôn ngữ khác tự lùi về tiếng Anh).
4. **Crossfade + reverb**: cho crossmix dùng chung đường reverb với Audio FX.
5. **`Roadmap.md`**: viết lại theo đúng thực tế đã kiểm ở mục 3.

### Không nên làm

- Dồn `collectAsState()` → `collectAsStateWithLifecycle()` cho toàn bộ ~500 chỗ: các destination
  trong `NavHost` rời composition khi bị điều hướng đi, nên phần lớn là tương đương.
- Cắt nhỏ `MusicService.kt` (5.564 dòng) chỉ vì nó dài. File dài nhưng mạch lạc; tách một lớp đang
  chạy ổn là rủi ro không đổi lấy được gì.

## 6. Đã kiểm chứng thế nào

Trên máy không có thiết bị Android (SDK 37, NDK 27, JDK 21):

```bash
./gradlew :app:compileUniversalFossDebugKotlin       # biên dịch — xanh
./gradlew :app:testUniversalFossDebugUnitTest        # 226 test, 0 lỗi
./gradlew :app:lintUniversalFossDebug                 # lint như CI
```

Ba nhóm việc cần **máy thật** — máy ảo không mô phỏng được:

1. Cài một bản có schema cũ (26–34) rồi nâng cấp lên bản này → mở app phải **còn nguyên thư viện**,
   và chỉ khi đó mới còn tệp `mochy.db.from<version>.bak` trong `databases/`.
2. Làm app crash (Cài đặt → công cụ dev, hoặc cắt mạng đúng lúc phát) → màn hình báo lỗi phải hiện,
   nút Chia sẻ phải mở được hộp chọn ứng dụng.
3. Thêm bài vào một playlist đã từng xoá bài → bài mới phải nằm ở **cuối**, và Hoàn tác phải xoá
   đúng những bài đó.
