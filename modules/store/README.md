# Module giao diện của bản Mochy Việt

Thư mục này là **cửa hàng module** của fork: app đọc [`../catalog.json`](../catalog.json) và hiện danh sách
này ở **Settings → Modules → Add a module**. Không cần build lại app để thêm module mới.

## Thêm một module

1. Tạo thư mục `modules/store/<id>/` và đặt `manifest.json` vào đó.
2. Thêm một mục vào [`../catalog.json`](../catalog.json) với `url` là đường dẫn raw của thư mục:

   ```
   https://raw.githubusercontent.com/anhduc2003/Mochy-Music/main/modules/store/<id>
   ```

   App tự tải `<url>/manifest.json` từ đó. `adds` ghi `["look"]` cho module giao diện.

## Manifest của một module giao diện

Chỉ cần `id`, `name` và một khối `look`. `look` là danh sách **cài đặt có sẵn của app** mà module muốn đặt
giá trị — mọi giá trị viết dạng chuỗi. Khi bật, app đặt các giá trị đó; khi tắt, app trả lại giá trị cũ của
người dùng. Vì chỉ là mô tả, nó không chạy code nên không thể làm hỏng app hay chậm máy.

```json
{
  "id": "ten-module",
  "name": "Tên hiện trong app",
  "version": "1.0.0",
  "description": "Một dòng mô tả.",
  "author": "Bạn",
  "look": {
    "accentColor": "#FF6FB5",
    "pureBlack": "true",
    "thumbnailCornerRadius": "24",
    "playerStyle": "POSTER",
    "lyricsMotionStyle": "MOTION"
  }
}
```

Các khoá `look` dùng được: `accentColor`, `dynamicTheme`, `pureBlack`, `thumbnailCornerRadius`,
`playerStyle`, `homeStyle`, `playlistStyle`, `lyricsMotionStyle`, `flagshipBackground`, `playerBackground`,
`ipodTheme`, `ipodWheelColor`, `bottomBar`, `blurQuality`, `blurStrengthPx`, `enableProgressiveBlur`,
`elementBlur`. Giá trị hợp lệ của từng khoá nằm trong wiki của repo (`docs/wiki/index.html`, mục
**Looks**). Giá trị sai bị bỏ qua, không gây lỗi.

Muốn tự vẽ màn hình đang phát thì thêm khối `player` (xem mục **Your own player** trong wiki) và đặt
`"playerStyle": "MODULE"` — module `dem-sai-gon` là ví dụ đầy đủ.

## Module đang có

| id | Tên | Nội dung |
|---|---|---|
| `dem-sai-gon` | Đêm Sài Gòn | Đen tuyệt đối, hồng neon, player riêng với bìa đĩa xoay |
| `muc` | Mực | Tối giản một màu, bìa vuông, không hiệu ứng |
| `sen` | Sen | Hồng sen dịu, nền theo bìa, blur tăng dần ở viền |
