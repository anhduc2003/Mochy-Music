# Mochy for PC

Bản Mochy chạy trên máy tính, viết bằng Kotlin + Compose Desktop.

## Vì sao là module riêng

`:app` là một **Android application**: toàn bộ UI (149.000 trong tổng 221.000 dòng) viết trên
Compose for Android, ExoPlayer, Room, Hilt và `Context`. Không dòng nào trong số đó biên dịch
được ngoài Android. Vì vậy không thể "thêm target desktop" vào `:app` — phải là module mới.

Phần **dùng chung được** là những gì vốn không cần Android ngay từ đầu: phân tích nhạc, planner
crossmix, bộ phân tích lời bài hát (123 file / 12.000 dòng thuần Kotlin). Những file đó được
chuyển sang theo từng package, `:app` không bị sửa.

## Chạy thử

```bash
./gradlew :desktop:run
```

Đóng gói cho hệ điều hành hiện tại:

```bash
./gradlew :desktop:packageDistributionForCurrentOS
```

Yêu cầu JDK 21.

## Đã có gì

| Tính năng | Trạng thái |
|---|---|
| Mở thư mục / nhiều tệp | ✅ |
| Đọc tag (title, artist, album, thời lượng) | ✅ |
| Thư viện lưu trên đĩa (SQLite) | ✅ |
| Phát / tạm dừng / bài trước / bài sau | ✅ |
| Thanh tiến trình + tua | ✅ |
| Hàng đợi, shuffle, repeat | ✅ (engine) |
| Tự chuyển bài khi hết | ✅ |
| Định dạng: WAV, AIFF, AU | ✅ |
| Định dạng: MP3, OGG/Opus, FLAC | ✅ (qua SPI provider) |
| Lời bài hát đồng bộ từ tag trong tệp | ✅ |
| **Crossmix** (planner + phân tích nhịp + khớp giọng) | ✅ port nguyên |
| Tìm kiếm online (YouTube Music) | ✅ metadata |
| **Phát nhạc online** | ❌ bị YouTube chặn |
| Tải nhạc YouTube về máy | ❌ |
| Scrobble Last.fm / Discord | ❌ |

## Giới hạn đã biết — nói rõ, không che

### Phát nhạc online: YouTube chặn

Tôi đã thử **live với tài khoản không đăng nhập** trên 5 client InnerTube:

| Client | Kết quả |
|---|---|
| `WEB_REMIX` | `UNPLAYABLE`, 0 stream |
| `ANDROID_VR` | `LOGIN_REQUIRED`, 0 stream |
| `ANDROID_MUSIC` | `LOGIN_REQUIRED`, 0 stream |
| `WEB` | `UNPLAYABLE`, 0 stream |
| `TVHTML5_SIMPLY_EMBEDDED_PLAYER` | `ERROR`, 0 stream |

YouTube giờ yêu cầu session đã đăng nhập, hoặc cặp visitor-data + signature, mới trả URL luồng phát.
**Tìm kiếm vẫn hoạt động** (14 kết quả thật với tên bài, videoId, thời lượng), nhưng bấm phát sẽ
báo lỗi. Muốn phát được phải port pipeline giải mã signature — bản Android đã có (qua
NewPipeExtractor), nhưng NewPipeExtractor là thư viện Android và bản JitPack của nó không dùng
được từ module JVM thuần.

### Tải nhạc YouTube: chưa có

Cùng lý do trên: cần resolve được URL trước khi tải.

### Scrobble / Discord: chưa port

Phần này nhỏ (456 + 1.869 dòng) và độc lập, làm được khi cần.

### UI

Bản tối giản có chủ đích: Material 3 mặc định. Player trên Android là 128.000 dòng tự vẽ (mâm đĩa
than, ảnh bìa biến hình, lời bài hát phát sáng) — port phần đó là giai đoạn riêng.

## Crossmix đã port

`CrossmixAnalysis`, `CrossmixBars`, `CrossmixStyles`, `CrossmixPlanner` (khoảng 108.000 ký tự)
chuyển nguyên văn — chúng chỉ import `kotlin.math`. Chỉ hai chỗ phải thay:

- `constants.CrossmixLength` / `CrossmixTempoMatch` → khai báo lại trong package
- `LyricsUtils.parseLyrics` (82.000 ký tự, kéo theo kuromoji + pinyin + Room) → `LyricsStamp`,
  chỉ đọc tem `[mm:ss.xx]` vì planner chỉ cần biết dòng nào đang hát

Bản PC dùng chung thuật toán với điện thoại, gồm cả 4 sửa lỗi gần đây (vùng chết tempo, khớp
giọng, căn nhịp theo kết quả thật, `alwaysMix` mặc định tắt).

## Kiểm thử

```bash
./gradlew :desktop:test
```

**11 test**, tất cả đều đạt:

- `DesktopPlayerTest` (4): đọc tag, tách tên file, báo lỗi khi thiếu tệp, hợp đồng phát nhạc
- `CrossmixPlannerTest` (6): phủ kín BPM, trần blend, khớp giọng, Camelot, tem lời bài hát
- `InnerTubeTest` (1): tìm kiếm thật, kiểm tra kết quả dùng được

Test cần network hoặc audio device sẽ **tự bỏ qua** khi máy không có, và in lý do — không giả
vờ đạt.
