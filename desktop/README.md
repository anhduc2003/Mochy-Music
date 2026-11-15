# Mochy for PC

Bản Mochy chạy trên máy tính, viết bằng Kotlin + Compose Desktop.

## Vì sao là module riêng

`:app` là một **Android application**: toàn bộ UI (149.000 trong tổng 221.000 dòng) viết trên
Compose for Android, ExoPlayer, Room, Hilt và `Context`. Không dòng nào trong số đó biên dịch
được ngoài Android. Vì vậy không thể "thêm target desktop" vào `:app` — phải là module mới.

Phần **dùng chung được** là những gì vốn không cần Android ngay từ đầu: phân tích nhạc, planner
crossmix, bộ phân tích lời bài hát (123 file / 12.000 dòng thuần Kotlin). Những file đó được
chuyển sang theo từng package, không sửa `:app`.

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
| Danh sách thư viện, tìm bài đang phát | ✅ |
| Phát / tạm dừng / bài trước / bài sau | ✅ |
| Thanh tiến trình + tua | ✅ |
| Hàng đợi, shuffle, repeat | ✅ (trong engine, UI chưa có nút) |
| Tự chuyển bài khi hết | ✅ |

## Giới hạn đã biết

**Định dạng nhạc.** `javax.sound.sampled` của JDK chỉ giải mã được những gì có `ServiceProvider`
cài sẵn — thực tế là **WAV, AIFF, AU**. MP3, FLAC, OGG, M4A **chưa phát được** cho tới khi thay
engine. Đây là giới hạn thật, không phải lỗi cấu hình: file vẫn hiện trong thư viện nhưng báo lỗi
khi bấm phát. Bước tiếp theo là thay `DesktopPlayer` bằng VLCJ hoặc mpv — phần còn lại của app viết
theo hợp đồng của engine nên không phải sửa khi thay.

**Chưa port:** lời bài hát đồng bộ, crossmix, tìm kiếm online, tải nhạc YouTube, scrobble,
Discord Rich Presence, thư viện/playlist lưu lâu dài.

**UI.** Đây là bản tối giản có chủ đích: Material 3 mặc định. Player trên Android là 128.000 dòng
tự vẽ (mâm đĩa than, ảnh bìa biến hình, lời bài hát phát sáng) — port phần đó là giai đoạn riêng,
không phải điều kiện để có bản PC dùng được.

## Kiểm thử

```bash
./gradlew :desktop:test
```

Có 4 test: đọc tag, tách tên file, báo lỗi khi thiếu tệp, và hợp đồng phát nhạc (vị trí tiến,
tạm dừng giữ nguyên chỗ, hết bài báo đúng một lần).

Test phát nhạc **tự bỏ qua** trên máy không có thiết bị âm thanh (CI, server). Muốn chạy thật:

```bash
WAV_PATH=/duong/dan/den/file.wav ./gradlew :desktop:test
```
