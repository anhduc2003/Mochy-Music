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
`"playerStyle": "MODULE"` — ba module `dem-sai-gon`, `muc`, `sen` đều có player riêng để làm mẫu.

## Module cần zip: kính, thanh điều hướng, font, shader

`glass`, `bar`, `font` và `code` **chỉ chạy từ zip**, không chạy từ link: shader phải là một tệp trên máy.
Vì thế module loại này có thêm trường `zip` trong catalogue, và app tải thẳng địa chỉ đó:

```json
{
  "name": "Liquid Glass",
  "url": "https://raw.githubusercontent.com/anhduc2003/Mochy-Music/main/modules/store/liquid-glass",
  "zip": "https://raw.githubusercontent.com/anhduc2003/Mochy-Music/main/modules/store/liquid-glass.mochyymodule.zip",
  "adds": ["look"]
}
```

Có `zip` thì app tải zip về, đọc `manifest.json` trong đó rồi cài như một zip chọn từ máy; không có `zip`
thì app tải `<url>/manifest.json` như trước. `url` vẫn phải trỏ tới thư mục nguồn để xem trước.

### Build lại zip sau khi sửa

```sh
cd modules/store/liquid-glass
rm -f ../liquid-glass.mochyymodule.zip
zip -q -X -r ../liquid-glass.mochyymodule.zip . -x '.*'
```

Zip phải có `manifest.json` ở gốc (không bọc trong thư mục con), và nên commit cả thư mục nguồn lẫn zip để
lần sau còn sửa được. `StoreManifestsTest` kiểm tra zip có tồn tại và có manifest, nên quên build lại là test đỏ.

## Module đang có

| id | Tên | Nội dung |
|---|---|---|
| `dem-sai-gon` | Đêm Sài Gòn | Đen tuyệt đối, hồng neon, player riêng với bìa đĩa xoay |
| `muc` | Mực | Tối giản một màu, bìa vuông, không hiệu ứng |
| `sen` | Sen | Hồng sen dịu, nền theo bìa, blur tăng dần ở viền |
| `liquid-glass` | Liquid Glass | Kính lỏng cho thanh điều hướng và thanh phát nhỏ, shader AGSL, 4 kiểu kính (zip) |

Mỗi module có `icon.png` (512×512) và `banner.webp` (1200×600) trong thư mục của nó; catalogue trỏ tới bản
raw của hai tệp đó nên hàng trong cửa hàng và trang chi tiết của module có ảnh riêng.
