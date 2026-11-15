# Mochy for PC

Bản Mochy chạy trên máy tính, viết bằng Kotlin + Compose Desktop. Đóng gói thành `Mochy.exe`
(hoặc `.dmg` / `.deb` tuỳ hệ điều hành).

## Chạy

```bash
./gradlew :desktop:run                              # chạy trực tiếp
./gradlew :desktop:createReleaseDistributable       # app image (portable, không cần WiX/dpkg)
./gradlew :desktop:packageReleaseDistributionForCurrentOS  # installer (.msi/.dmg/.deb)
```

Yêu cầu JDK 21.

### Bản phát hành

Workflow `Build PC Release` dựng cho cả ba hệ điều hành và phát hành lên GitHub Releases:

```
Actions → Build PC Release → Run workflow → nhập tag (ví dụ vpc-1.0.0)
```

Mỗi hệ điều hành có hai dạng, vì installer gốc cần công cụ riêng trên runner:

| | Bản cài | Bản portable |
|---|---|---|
| Windows | `.msi` (cần WiX) | `.zip` |
| macOS | `.dmg` | `.tar.gz` |
| Linux | `.deb` (cần dpkg) | `.tar.gz` |

Bản **portable luôn dựng được** — nó là app-image của jpackage, không cần WiX, Xcode tooling hay
dpkg. Installer được thử sau và bỏ qua nếu runner thiếu công cụ, nên một nền tảng lỗi không làm
mất hai nền tảng còn lại.

## Vì sao là module riêng

`:app` là một **Android application**: UI (149.000 trong tổng 221.000 dòng) viết trên Compose for
Android, ExoPlayer, Room, Hilt và `Context`. Không dòng nào biên dịch được ngoài Android. Phần
dùng chung được là những gì vốn không cần Android (123 file / 12.000 dòng thuần Kotlin), chuyển
sang theo từng package — `:app` không bị sửa.

## Tính năng

| | Trạng thái |
|---|---|
| Mở thư mục / nhiều tệp | ✅ |
| Đọc tag (title, artist, album, thời lượng, ảnh bìa) | ✅ |
| Thư viện lưu trên đĩa (SQLite) | ✅ |
| Phát / tạm dừng / trước / sau / tua / âm lượng | ✅ |
| Shuffle, repeat (off/all/one) | ✅ |
| Tự chuyển bài khi hết | ✅ |
| **WAV, AIFF, AU, MP3, OGG/Opus, FLAC** | ✅ |
| **Lời bài hát đồng bộ** (từ tag trong tệp) | ✅ |
| **Crossmix** (phân tích nhịp + khớp giọng + planner) | ✅ dùng chung thuật toán với điện thoại |
| **Tìm kiếm YouTube Music** | ✅ đã kiểm chứng 14 kết quả thật |
| **Phát nhạc online** | ✅ đã kiểm chứng lấy được stream thật |
| **Last.fm scrobble** | ✅ có code, cần API key |
| **Discord Rich Presence** | ✅ có code, cần 1 lần bấm chạy Discord |
| Nền gradient theo màu bìa + cover art | ✅ |
| Tải nhạc về máy | ❌ |

## Phát online: cách vượt chặn

Lần đầu tôi thử gọi thẳng player endpoint và **cả 5 client đều bị chặn**:

| Client | Kết quả |
|---|---|
| `WEB_REMIX`, `WEB`, `ANDROID_TESTSUITE` | `UNPLAYABLE` ("Cần tải lại trang này") |
| `ANDROID_MUSIC`, `ANDROID_VR`, `ANDROID_CREATOR`, `VISIONOS` | `LOGIN_REQUIRED` |

YouTube giờ đòi **proof-of-origin token**. Cách hoạt động được (đã kiểm chứng):

1. `GET` trang watch với user-agent trình duyệt → nhận cookie (`VISITOR_INFO1_LIVE`,
   `__Secure-YNID`, `YSC`…) **và** giá trị `STS` — signature timestamp mà JS player hiện tại dùng
2. `POST` player endpoint với client **VISIONOS**, kèm cookie đó, visitor id, và
   `signatureTimestamp` trong `playbackContext`

Client VISIONOS không bị hỏi PoToken, và trả `streamingData` có `url` trực tiếp — không cần
`signatureCipher`, nên không phải chạy JS giải mã.

**Chi tiết quan trọng:** `STS` cũ chính là thứ biến phản hồi tốt thành `UNPLAYABLE`. Đó là lỗi
của bản đầu tiên tôi viết.

Kết quả kiểm chứng: `itag=251`, `audio/webm; codecs="opus"`, 164 kbps, URL googlevideo có `sig=`.

Phần này **dễ hỏng theo thời gian** — YouTube đổi yêu cầu client thường xuyên. Khi hỏng, triệu
chứng là `streamUrl` trả `null` và UI báo rõ, không im lặng.

## Crossmix port nguyên văn

`CrossmixAnalysis`, `CrossmixBars`, `CrossmixStyles`, `CrossmixPlanner` (~108.000 ký tự) chép
nguyên — chúng chỉ import `kotlin.math`. Hai chỗ phải thay:

- `CrossmixLength` / `CrossmixTempoMatch` → khai báo lại trong package
- `LyricsUtils.parseLyrics` (82.000 ký tự, kéo theo kuromoji + pinyin + Room) → `LyricsStamp`,
  chỉ đọc tem `[mm:ss.xx]` vì planner chỉ cần biết dòng nào đang hát

Bản PC dùng chung thuật toán với điện thoại, gồm cả 4 sửa lỗi gần đây.

## Tích hợp: cần cấu hình

Cả hai đều **tắt mặc định** và không cản trở việc phát nhạc khi thiếu.

**Last.fm** — cần API key/secret của ứng dụng (đăng ký tại last.fm/api). Đặt qua biến môi trường:

```bash
LASTFM_API_KEY=... LASTFM_SECRET=... ./gradlew :desktop:run
```

Scrobble tính theo đúng luật Last.fm: nửa độ dài bài hoặc 4 phút, tuỳ cái nào tới trước.

**Discord** — đặt `DISCORD_CLIENT_ID` rồi chạy. Dùng IPC socket (`discord-ipc-0`), không cần
OAuth. Nếu Discord không chạy thì presence đơn giản là tắt, không báo lỗi.

## Kiểm thử

```bash
./gradlew :desktop:test
```

**12 test, tất cả đạt:**

| Bộ test | Nội dung |
|---|---|
| `DesktopPlayerTest` (4) | đọc tag, tách tên file, báo lỗi thiếu tệp, hợp đồng phát nhạc |
| `CrossmixPlannerTest` (6) | phủ kín BPM, trần blend, khớp giọng, Camelot, tem lời |
| `InnerTubeTest` (1) | tìm kiếm thật, 14 kết quả |
| `YouTubeSessionTest` (1) | lấy stream thật, itag 251 |

Test cần network hoặc audio device sẽ **tự bỏ qua** và in lý do khi máy không có — không giả vờ
đạt. Máy build này không có sound card nên test phát nhạc thực chất bị bỏ qua.
