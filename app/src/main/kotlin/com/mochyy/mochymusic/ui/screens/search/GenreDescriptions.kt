// GenreDescriptions.kt

package com.mochyy.mochymusic.ui.screens.search

private val Descriptions = mapOf(
    "afrobeats" to "Nhịp điệu sôi động kết hợp âm hưởng đại chúng đương đại từ châu Phi.",
    "ambient" to "Âm nhạc không gian nhẹ nhàng, phù hợp để thư giãn và tập trung.",
    "anime" to "Những ca khúc nổi bật trong phim hoạt hình Nhật Bản.",
    "blues" to "Giai điệu giàu cảm xúc với tiếng đàn đặc trưng và nhịp điệu sâu lắng.",
    "classical" to "Các tác phẩm cổ điển, từ dàn nhạc giao hưởng đến độc tấu.",
    "country" to "Những ca khúc kể chuyện với âm hưởng đồng quê và nhạc cụ mộc.",
    "dance" to "Nhạc điện tử giàu năng lượng dành cho sàn nhảy.",
    "disco" to "Giai điệu sôi động, bắt tai với nhịp bass và âm hưởng vũ trường.",
    "edm" to "Nhạc điện tử với nhịp điệu mạnh mẽ và cao trào cuốn hút.",
    "electronic" to "Âm thanh điện tử đa dạng, từ thư thái đến sôi động.",
    "folk" to "Ca khúc mộc mạc, tập trung vào giọng hát và nhạc cụ truyền thống.",
    "funk" to "Nhịp bass nổi bật cùng tiết tấu giàu groove.",
    "gospel" to "Những ca khúc hợp xướng giàu cảm xúc và năng lượng.",
    "hip hop" to "Nhịp điệu và lời rap làm nên âm thanh đặc trưng của hip hop.",
    "indie" to "Âm nhạc độc lập với màu sắc sáng tạo và cá tính riêng.",
    "jazz" to "Giai điệu ngẫu hứng với hòa âm phong phú và nhạc cụ sống động.",
    "k-pop" to "Nhạc pop Hàn Quốc với giai điệu bắt tai và phần trình diễn ấn tượng.",
    "latin" to "Nhịp điệu Latin sôi động, từ salsa đến reggaeton.",
    "metal" to "Âm thanh mạnh mẽ với guitar distortion và trống dồn dập.",
    "pop" to "Những giai điệu phổ biến, dễ nhớ và luôn bắt kịp xu hướng.",
    "r&b" to "Giọng hát giàu cảm xúc trên nền nhịp điệu R&B mượt mà.",
    "reggae" to "Nhịp điệu thư thái đặc trưng của reggae và âm nhạc Jamaica.",
    "rock" to "Tiếng guitar điện mạnh mẽ cùng nhịp điệu đầy năng lượng.",
    "soul" to "Giọng hát truyền cảm hòa cùng âm hưởng soul sâu lắng.",
)

fun genreDescription(title: String): String {
    if (title.isBlank()) return ""
    val key = title.lowercase()
        .replace("&amp;", "&")
        .replace("-", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    Descriptions[key]?.let { return it }

    Descriptions.entries
        .firstOrNull { (candidate, _) -> key.startsWith(candidate) || candidate.startsWith(key) }
        ?.let { return it.value }

    return "Danh sách phát, album và nghệ sĩ nổi bật thuộc thể loại $title."
}
