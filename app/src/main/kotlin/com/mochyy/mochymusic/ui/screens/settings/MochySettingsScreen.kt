// MochySettingsScreen.kt

package com.mochyy.mochymusic.ui.screens.settings

import com.mochyy.mochymusic.ui.component.navigateToTab
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.scrobble.ScrobbleSite
import com.mochyy.mochymusic.ui.screens.devices.showDeviceIntegration
import com.mochyy.mochymusic.ui.screens.settings.importsync.YouTubeSyncRoute
import com.mochyy.mochymusic.ui.screens.settings.integrations.IntegrationsRoute
import com.mochyy.mochymusic.ui.screens.settings.integrations.discord.DiscordRoute
import com.mochyy.mochymusic.ui.screens.settings.integrations.scrobbleServiceRoute
import com.mochyy.mochymusic.constants.ProfilePicUriKey
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.SettingsHighlight
import com.mochyy.mochymusic.ui.component.glassRoot
import com.mochyy.mochymusic.ui.screens.search.SearchAvatar
import com.mochyy.mochymusic.ui.screens.search.SearchField
import com.mochyy.mochymusic.ui.screens.search.SearchGlassTopBar
import com.mochyy.mochymusic.ui.screens.search.rememberScrollCollapseProgress
import com.mochyy.mochymusic.ui.screens.search.searchTopBarHeight
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberPreference

private data class SettingsEntry(
    val title: String,
    val page: String,
    val route: String,
    val keywords: String = "",
    val titleRes: Int? = null,
    val pageRes: Int? = null,
) {
    val highlightKey: String get() = title
}

// search ids stay english so rows can find themselves, what's shown and matched follows the app's language
private val LocalizedSettingsTitles = mapOf(
    "Tùy chỉnh" to R.string.settings_customisation_category,
    "Làm mờ" to R.string.appearance_blur,
    "Làm mờ chuyển sắc" to R.string.appearance_progressive_blur,
    "Chất lượng làm mờ" to R.string.appearance_blur_quality,
    "Mức độ làm mờ" to R.string.appearance_blur_strength,
    "Hoạt ảnh" to R.string.appearance_animation,
    "Hoạt ảnh lời bài hát" to R.string.appearance_lyrics_animation,
    "Chế độ Larp" to R.string.larp_mode,
    "Ẩn thông báo cập nhật ứng dụng" to R.string.settings_updates_notice,
    "Chế độ ngoại tuyến" to R.string.settings_offline_mode,
    "Tiết kiệm dữ liệu" to R.string.settings_data_saver,
    "Proxy" to R.string.proxy,
    "Album từ bài hát đã thích" to R.string.settings_albums_from_likes,
    "Tiếp tục phát khi đóng ứng dụng" to R.string.playback_keep_playing,
    "Phát cùng ứng dụng khác" to R.string.playback_mix_other_apps,
    "Lưu trạng thái phát gần nhất" to R.string.playback_save_state,
    "Tự động lấy thông tin bài hát trên thiết bị" to R.string.settings_local_auto_metadata,
    "Giữ phát ngẫu nhiên" to R.string.settings_persistent_shuffle,
    "Không tự phát video YouTube" to R.string.settings_disable_video_autoplay,
    "Bản dịch từ YouTube" to R.string.settings_youtube_translations,
    "Đồng bộ lời với video nhạc" to R.string.settings_sync_lyrics_video,
    "Không bỏ qua khi phát lỗi" to R.string.settings_dont_skip_failure,
    "Bỏ qua khi hết thời gian chờ" to R.string.settings_skip_timeout,
    "Bỏ qua khoảng lặng" to R.string.playback_skip_silence,
    "Nhãn định dạng âm thanh" to R.string.appearance_format_badge,
    "Hiển thị tần số lấy mẫu" to R.string.appearance_sample_rate,
    "Luôn bật nền YouTube" to R.string.appearance_youtube_background,
    "Bật hình nền động" to R.string.appearance_canvas,
    "Hiệu ứng bloom chất lượng cao" to R.string.appearance_bloom,
    "Audio Pass" to R.string.audiopass_hub_title,
    "Ưu tiên USB DAC" to R.string.playback_usb_prefer,
    "Truyền âm thanh trực tiếp qua USB DAC" to R.string.playback_usb_passthrough,
    "Đầu ra độ phân giải cao" to R.string.playback_hires,
    "Bộ lấy mẫu lại Hi-Fi" to R.string.playback_hifi,
    "Audio Lab" to R.string.settings_audio_lab_category,
    "Modules" to R.string.settings_modules_category,
    "Bộ nhớ" to R.string.storage,
    "App behaviour" to R.string.settings_app_behaviour,
    "Ẩn mô tả trong phần cài đặt" to R.string.settings_hide_descriptions,
    "Độ bo góc bìa" to R.string.customise_cover_rounding,
    "Kiểu danh sách phát" to R.string.customise_playlist_styles,
    "Kiểu trình phát" to R.string.customise_player_styles,
    "Kiểu trang chủ" to R.string.customise_home_styles,
    "Làm mờ thành phần" to R.string.customise_element_blur,
    "Tốc độ hoạt ảnh" to R.string.customise_animation_speed,
    "Thanh trên cùng của trình phát" to R.string.customise_top_bar,
    "Thao tác vuốt" to R.string.customise_gesture,
    "Vuốt sang phải" to R.string.customise_swipe_right,
    "Vuốt sang trái" to R.string.customise_swipe_left,
    "Vuốt xuống bất kỳ đâu để đóng" to R.string.customise_swipe_down_anywhere,
    "Mở theo ngón tay" to R.string.customise_swipe_up_follows,
)
private val LocalizedSettingsPages = mapOf(
    "App behaviour" to R.string.settings_app_behaviour,
    "Tùy chỉnh" to R.string.settings_customisation_category,
    "Bộ nhớ" to R.string.storage,
    "Audio Lab" to R.string.settings_audio_lab_category,
    "Modules" to R.string.settings_modules_category,
    "Integrations" to R.string.integrations,
)

private val SettingsIndex = listOf(
    // Customisation
    SettingsEntry("Tùy chỉnh", "Tùy chỉnh", "customisation_settings", "customize cover rounding playlist player home styles gesture swipe blur animation lyrics"),
    SettingsEntry("Độ bo góc bìa", "Tùy chỉnh", "customisation_settings", "square rounded corner radius px thumbnail art"),
    SettingsEntry("Kiểu danh sách phát", "Tùy chỉnh", "customisation_settings", "flow classic minimal header layout"),
    SettingsEntry("Kiểu trình phát", "Tùy chỉnh", "customisation_settings", "flagship turntable ipod poster timeline lyrics studio customize player cover"),
    SettingsEntry("Kiểu trang chủ", "Tùy chỉnh", "customisation_settings", "mochyy listen now hop in hero layout grid"),
    SettingsEntry("Làm mờ", "Tùy chỉnh", "customisation_settings", "blur glass frosted performance lag"),
    SettingsEntry("Làm mờ chuyển sắc", "Tùy chỉnh", "customisation_settings", "gradient fade top bar hairline flat"),
    SettingsEntry("Chất lượng làm mờ", "Tùy chỉnh", "customisation_settings", "low high glass performance"),
    SettingsEntry("Mức độ làm mờ", "Tùy chỉnh", "customisation_settings", "radius px glass amount"),
    SettingsEntry("Làm mờ thành phần", "Tùy chỉnh", "customisation_settings", "lyrics line blur cover zoom title morph menu page wipe"),
    SettingsEntry("Hoạt ảnh", "Tùy chỉnh", "customisation_settings", "transition motion off reduce disable"),
    SettingsEntry("Tốc độ hoạt ảnh", "Tùy chỉnh", "customisation_settings", "faster slower duration scale motion"),
    SettingsEntry("Hoạt ảnh lời bài hát", "Tùy chỉnh", "customisation_settings", "lyrics style 1 2 boring plain shader glow wave agsl letters"),
    SettingsEntry("Thanh trên cùng của trình phát", "Tùy chỉnh", "customisation_settings", "status bar hide clock battery player top"),
    SettingsEntry("Thao tác vuốt", "Tùy chỉnh", "customisation_settings", "swipe slide song list left right queue library download play next playlist details"),
    SettingsEntry("Vuốt sang phải", "Tùy chỉnh", "customisation_settings", "slide song list queue library download play next playlist details action"),
    SettingsEntry("Vuốt sang trái", "Tùy chỉnh", "customisation_settings", "slide song list queue library download play next playlist details action"),
    SettingsEntry("Vuốt xuống bất kỳ đâu để đóng", "Tùy chỉnh", "customisation_settings", "player close dismiss swipe down pull gesture anywhere"),
    SettingsEntry("Mở theo ngón tay", "Tùy chỉnh", "customisation_settings", "player open swipe up mini player follow finger speed velocity gesture"),

    // App behaviour
    SettingsEntry("App behaviour", "App behaviour", "general_settings", "general playback player behaviour"),
    SettingsEntry("Chế độ Larp", "App behaviour", "general_settings", "larp 67 flac 768 khz wadax atlantis dac hi-res lossless bit-perfect flex"),
    SettingsEntry("Ngôn ngữ ứng dụng", "App behaviour", "general_settings", "language bahasa locale device system indonesia english translate", R.string.app_language),
    SettingsEntry("Ẩn thông báo cập nhật ứng dụng", "App behaviour", "general_settings", "update popup notification updater"),
    SettingsEntry("Chế độ ngoại tuyến", "App behaviour", "general_settings", "play downloaded cache offline"),
    SettingsEntry("Tiết kiệm dữ liệu", "App behaviour", "general_settings", "data saver mobile metered cellular bandwidth lower quality canvas video"),
    SettingsEntry("Proxy", "App behaviour", ProxySettingsRoute, "proxy socks http server vpn network region country blocked bot sign in"),
    SettingsEntry("Album từ bài hát đã thích", "App behaviour", "general_settings", "albums library liked songs old way apple music"),
    SettingsEntry("Tiếp tục phát khi đóng ứng dụng", "App behaviour", "general_settings", "background close stop playback"),
    SettingsEntry("Phát cùng ứng dụng khác", "App behaviour", "general_settings", "audio focus mix pause other app video game sound together"),
    SettingsEntry("Tạm dừng khi tắt tiếng", "App behaviour", "general_settings", "pause mute volume silent zero off automatic"),
    SettingsEntry("Lưu trạng thái phát gần nhất", "App behaviour", "general_settings", "persistent queue restore track state"),
    SettingsEntry("Tự động lấy thông tin bài hát trên thiết bị", "App behaviour", "general_settings", "match tags id3 covers lyrics"),
    SettingsEntry("Giữ phát ngẫu nhiên", "App behaviour", "general_settings", "shuffle persistent spotify random keep new playlist"),
    SettingsEntry("Không tự phát video YouTube", "App behaviour", "general_settings", "music video live autoplay cover"),
    SettingsEntry("Bản studio cho video âm nhạc", "App behaviour", "general_settings", "music video mv omv audio studio album version skit sound effects official"),
    SettingsEntry("Bản dịch từ YouTube", "App behaviour", "general_settings", "captions translate lyrics language"),
    SettingsEntry("Đồng bộ lời với video nhạc", "App behaviour", "general_settings", "lyrics video timing intro skit"),
    SettingsEntry("Không bỏ qua khi phát lỗi", "App behaviour", "general_settings", "fail stream error stay skip"),
    SettingsEntry("Bỏ qua khi hết thời gian chờ", "App behaviour", "general_settings", "skip timeout seconds failed stream"),
    SettingsEntry("Bỏ qua khoảng lặng", "App behaviour", "general_settings", "silence trim gap interval"),
    SettingsEntry("Nhãn định dạng âm thanh", "App behaviour", "general_settings", "format codec opus aac flac badge title quality"),
    SettingsEntry("Hiển thị tần số lấy mẫu", "App behaviour", "general_settings", "sample rate khz hz format badge quality"),
    SettingsEntry("Luôn bật nền YouTube", "App behaviour", "general_settings", "youtube video background canvas"),
    SettingsEntry("Bật hình nền động", "App behaviour", "general_settings", "canvas animation spotify looping visual"),
    SettingsEntry("Hiệu ứng bloom chất lượng cao", "App behaviour", "general_settings", "lyrics bloom quality extra soft pass glow"),
    SettingsEntry("Ẩn mô tả trong phần cài đặt", "App behaviour", "general_settings", "hide subtitle text rows titles only"),

    // Audio Lab
    SettingsEntry("Audio Lab", "Audio Lab", "audio_lab", "sound effects equalizer fx quality"),
    SettingsEntry("Chuyển tiếp", "Audio Lab", "crossmix_settings", "crossfade crossmix automix transition gapless blend"),
    SettingsEntry("Chuyển bài mượt", "Audio Lab", "crossmix_settings", "skip next previous back crossfade seamless preload transition fade blend"),
    SettingsEntry("Bộ chỉnh âm", "Audio Lab", "equalizer", "eq bass treble band parametric graph autoeq headphones correction preset"),
    SettingsEntry("AutoEQ", "Audio Lab", "equalizer", "headphones earbuds correction harman database model"),
    SettingsEntry("Chất lượng âm thanh", "Audio Lab", "audio_quality", "bitrate stream high low lossless hi-res flac monochrome"),
    SettingsEntry("Audio Pass", "Audio Lab", "audio_pass", "dac usb output headphones bluetooth codec bit perfect exclusive"),
    SettingsEntry("Ưu tiên USB DAC", "Audio Lab", "audio_pass", "usb dac headphones output external audio"),
    SettingsEntry("Truyền âm thanh trực tiếp qua USB DAC", "Audio Lab", "audio_pass", "bit perfect bitperfect passthrough sample rate dac usb resample"),
    SettingsEntry("Đầu ra độ phân giải cao", "Audio Lab", "audio_pass", "hi res 24 bit float precision lossless flac quality"),
    SettingsEntry("Bộ lấy mẫu lại Hi-Fi", "Audio Lab", "audio_pass", "resample sample rate 44.1 48 khz sinc src quality filter"),
    SettingsEntry("Âm vang", "Audio Lab", "audio_fx", "room hall echo space fx effect"),
    SettingsEntry("Độ lớn âm thanh", "Audio Lab", "audio_fx", "boost louder compressor limiter night mode fx"),
    SettingsEntry("Chuẩn hóa âm lượng", "Audio Lab", "audio_fx", "even out songs loudness volume gain balance replaygain"),
    SettingsEntry("Âm thanh không gian", "Audio Lab", "audio_fx", "crossfeed binaural hrtf virtual speakers 3d headphones"),
    SettingsEntry("Dolby Atmos", "Audio Lab", "audio_fx", "atmos spatial surround 3d immersive"),
    SettingsEntry("Tốc độ phát", "Audio Lab", "audio_fx", "tempo pitch slowed nightcore preserve pitch"),
    SettingsEntry("Âm lượng theo cấp số nhân", "Audio Lab", "audio_fx", "volume curve logarithmic limit"),
    SettingsEntry("Modules", "Modules", "modules", "apple music source module plugin extension stream subscription"),
    SettingsEntry("Apple Music", "Modules", "module/applemusic", "apple music itunes subscription stream aac"),
    SettingsEntry("Monochrome", "Modules", "module/monochrome", "monochrome lossless flac hi-res tidal"),
    SettingsEntry("Thứ tự nguồn phát", "Audio Lab", "audio_quality", "source order apple music monochrome youtube priority"),
    SettingsEntry("YouTube Music", "Audio Lab", "audio_quality", "youtube turn off disable provider only monochrome apple music"),

    // Integrations
    SettingsEntry("Discord", "Integrations", DiscordRoute, "discord rich presence rpc status activity listening playing profile behaviour pause clear"),
    SettingsEntry("Last.fm", "Integrations", scrobbleServiceRoute(ScrobbleSite.LASTFM), "lastfm scrobble scrobbling history stats account sign in"),
    SettingsEntry("Libre.fm", "Integrations", scrobbleServiceRoute(ScrobbleSite.LIBREFM), "librefm scrobble scrobbling history stats account sign in"),
    SettingsEntry("Now Playing", "Integrations", scrobbleServiceRoute(ScrobbleSite.LASTFM), "scrobble now playing status profile lastfm librefm"),
    SettingsEntry("Send likes", "Integrations", scrobbleServiceRoute(ScrobbleSite.LASTFM), "love loved songs lastfm librefm scrobble"),
    SettingsEntry("YouTube Music", "Integrations", YouTubeSyncRoute, "youtube ytm sync account sign in likes library playlists history"),

    // Storage
    SettingsEntry("Bộ nhớ", "Bộ nhớ", "storage_settings", "space disk size cache downloads clear free junk delete"),
    SettingsEntry("Giới hạn bộ nhớ đệm nhạc", "Bộ nhớ", "storage_settings", "cache size limit space song stream"),
    SettingsEntry("Quản lý nội dung tải xuống", "Bộ nhớ", "storage_settings", "downloads delete remove biggest offline space"),
    SettingsEntry("Xóa toàn bộ nội dung tải xuống", "Bộ nhớ", "storage_settings", "delete clear downloads offline space"),

    // Experimental
    SettingsEntry("Tích hợp thiết bị", "Nhập và đồng bộ", com.mochyy.mochymusic.ui.screens.devices.DeviceIntegrationRoute, "tv television android tv google tv phone tablet car head unit pair link sync stream downloads big screen remote control devices"),
    SettingsEntry("Nhập và đồng bộ", "Thử nghiệm", "experimental_settings", "import spotify apple music deezer tidal tunemymusic csv youtube sync export backup transfer"),
    SettingsEntry("Kiểu bìa đĩa lớn", "Thử nghiệm", "experimental_settings", "disc vinyl cover"),
    SettingsEntry("Rung theo nhạc", "Thử nghiệm", "experimental_settings", "vibration haptic"),
    SettingsEntry("Cipher", "Thử nghiệm", "experimental_settings", "player script signature refresh"),
    SettingsEntry("Chẩn đoán phát nhạc", "Thử nghiệm", "experimental_settings", "logs potoken stream client debug"),
    SettingsEntry("Chạy lại thiết lập ban đầu", "Thử nghiệm", "experimental_settings", "onboarding wizard setup"),
    SettingsEntry("Hiển thị nguồn phát bài hát", "Thử nghiệm", "experimental_settings", "source banner toast apple music monochrome youtube"),
    SettingsEntry("Buộc phát", "Thử nghiệm", "experimental_settings", "fallback skip error stream failed youtube"),
)

private data class RecommendedSetting(
    val titleRes: Int,
    val breadcrumbRes: Int,
    val iconRes: Int,
    // opens a page, the list's own way: over it on a phone, beside it on a wide screen
    val onClick: (open: (String) -> Unit) -> Unit,
)

private val RecommendedOptions = listOf(
    RecommendedSetting(
        titleRes = R.string.settings_rec_blur,
        breadcrumbRes = R.string.settings_crumb_customisation,
        iconRes = R.drawable.contrast,
        onClick = { open ->
            SettingsHighlight.pending = "Làm mờ"
            open("customisation_settings")
        }
    ),
    RecommendedSetting(
        titleRes = R.string.settings_rec_crossfade,
        breadcrumbRes = R.string.settings_crumb_audio_lab,
        iconRes = R.drawable.linear_scale,
        onClick = { open ->
            open("crossmix_settings")
        }
    ),
    RecommendedSetting(
        titleRes = R.string.settings_rec_equalizer,
        breadcrumbRes = R.string.settings_crumb_audio_lab,
        iconRes = R.drawable.equalizer,
        onClick = { open ->
            open("equalizer")
        }
    )
)

private val BubbleColor = Color(0xFF1C1C1E)
private val SelectedBubbleColor = Color(0xFF3A3A3E)

// the header holds the mochy mark with the big "Settings" under it, taller than search's title-only header
private val SettingsTitleBlockHeight = 88.dp
private val SettingsMarkSize = 32.dp
private val IconBgColor = Color(0xFF333338)

@Composable
fun MochySettingsScreen(navController: NavController, pane: SettingsPane? = null) {
    val focusManager = LocalFocusManager.current
    val sheets = com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState.current
    val devices = com.mochyy.mochymusic.ui.screens.devices.rememberDevicesLink()
    // on a wide screen the list stays put and its pages open beside it; device integration is a sheet over either
    val open: (String) -> Unit = { route ->
        when {
            route == com.mochyy.mochymusic.ui.screens.devices.DeviceIntegrationRoute -> sheets.showDeviceIntegration(devices)
            pane != null -> navController.openSettingsPage(route)
            else -> navController.navigate(route)
        }
    }
    val selected = pane?.selectedRoute
    val scrollState = rememberScrollState()
    val glassState = remember { GlassState() }

    val (profilePicUri) = rememberPreference(ProfilePicUriKey, "")

    var query by remember { mutableStateOf(TextFieldValue()) }
    var isSearchFocused by remember { mutableStateOf(false) }

    val isSearching = isSearchFocused || query.text.isNotBlank()

    BackHandler(enabled = isSearching) {
        query = TextFieldValue()
        isSearchFocused = false
        focusManager.clearFocus()
    }

    val recommendedItem = remember { RecommendedOptions.random() }

    val context = LocalContext.current
    val results = remember(query.text, context) {
        val q = query.text.trim()
        if (q.isBlank()) {
            emptyList()
        } else {
            SettingsIndex.filter {
                it.title.contains(q, ignoreCase = true) ||
                    (it.titleRes ?: LocalizedSettingsTitles[it.title])?.let { id -> context.getString(id).contains(q, ignoreCase = true) } == true ||
                    (it.pageRes ?: LocalizedSettingsPages[it.page])?.let { id -> context.getString(id).contains(q, ignoreCase = true) } == true ||
                    it.keywords.contains(q, ignoreCase = true) ||
                    it.page.contains(q, ignoreCase = true)
            }
        }
    }

    // Same latch-and-settle collapse as Library/Search: crossing the threshold always finishes
    // the morph, even after the user stops scrolling mid-gesture.
    val collapseProgress by rememberScrollCollapseProgress(scrollState)

    // Animated fade for content when search bar is tapped
    val contentAlpha by animateFloatAsState(
        targetValue = if (isSearching) 0f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "contentAlpha"
    )
    // flips only at the ends of the fade, so the page isn't recomposed on every frame of it
    val showContent by remember { derivedStateOf { contentAlpha > 0.001f } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassRoot(glassState, isActive = { !scrollState.isScrollInProgress && collapseProgress > 0f }),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(searchTopBarHeight(titleBlockHeight = SettingsTitleBlockHeight)))

            // Search results view when user types
            AnimatedVisibility(
                visible = query.text.isNotBlank(),
                enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(160, easing = FastOutSlowInEasing)) { 20 },
                exit = fadeOut(tween(120, easing = FastOutSlowInEasing))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (results.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_nothing_matches, query.text),
                            fontFamily = InterFontFamily,
                            fontSize = 15.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                        )
                    } else {
                        results.forEach { entry ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(BubbleColor)
                                    .clickable {
                                        SettingsHighlight.pending = entry.highlightKey
                                        open(entry.route)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(IconBgColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.settings),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(11.dp))
                                    Column {
                                        Text(
                                            text = (entry.titleRes ?: LocalizedSettingsTitles[entry.title])?.let { stringResource(it) } ?: entry.title,
                                            fontFamily = InterFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp,
                                            color = Color.White
                                        )
                                        Text(
                                            text = (entry.pageRes ?: LocalizedSettingsPages[entry.page])?.let { stringResource(it) } ?: entry.page,
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.55f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Normal Settings Content (fades smoothly to black when search is focused)
            if (showContent) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = contentAlpha }
                ) {
                    // Recommended Item Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(BubbleColor)
                            .clickable { recommendedItem.onClick(open) }
                            .padding(18.dp)
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.settings_recommended_item),
                                fontFamily = InterFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.55f)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(IconBgColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(recommendedItem.iconRes),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(recommendedItem.titleRes),
                                        fontFamily = InterFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = stringResource(recommendedItem.breadcrumbRes),
                                        fontFamily = InterFontFamily,
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(0.8.dp)
                            .background(Color.White.copy(alpha = 0.1f))
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Separate Bubble Pills for Categories (No outlines, individually bubble-wrapped)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // in the order of the design: sound first, then how the app acts and looks, then the rest
                        CategoryBubble(
                            title = stringResource(R.string.settings_audio_lab_category),
                            iconRes = R.drawable.equalizer,
                            selected = selected == "audio_lab",
                            onClick = { open("audio_lab") }
                        )

                        CategoryBubble(
                            title = stringResource(R.string.settings_app_behaviour),
                            iconRes = R.drawable.settings,
                            selected = selected == "general_settings",
                            onClick = { open("general_settings") }
                        )

                        CategoryBubble(
                            title = stringResource(R.string.settings_customisation_category),
                            iconRes = R.drawable.palette,
                            selected = selected == "customisation_settings",
                            onClick = { open("customisation_settings") }
                        )

                        CategoryBubble(
                            title = stringResource(R.string.storage),
                            iconRes = R.drawable.storage,
                            selected = selected == "storage_settings",
                            onClick = { open("storage_settings") }
                        )

                        CategoryBubble(
                            title = stringResource(R.string.integrations),
                            iconRes = R.drawable.integration,
                            selected = selected == IntegrationsRoute,
                            onClick = { open(IntegrationsRoute) }
                        )

                        CategoryBubble(
                            title = stringResource(R.string.devices_title),
                            iconRes = R.drawable.cast_connected,
                            selected = false,
                            onClick = { open(com.mochyy.mochymusic.ui.screens.devices.DeviceIntegrationRoute) }
                        )

                        CategoryBubble(
                            title = stringResource(R.string.settings_modules_category),
                            iconRes = R.drawable.library_add,
                            selected = selected == "modules",
                            onClick = { open("modules") }
                        )

                        // pages the modules that are on bring to settings, right under modules
                        com.mochyy.mochymusic.ui.screens.settings.modules.ModuleSettingsPages(open = open, selected = selected)
                    }
                }
            }

            val bottomInset = com.mochyy.mochymusic.LocalPlayerAwareWindowInsets.current.asPaddingValues()
            val bottomPadding = bottomInset.calculateBottomPadding()
            val bottomSpacerHeight = maxOf(340.dp, bottomPadding + 160.dp)

            // Headroom spacer for smooth scrolling past collapse threshold and bottom bar
            Spacer(modifier = Modifier.height(bottomSpacerHeight))
        }
        }

        // Top bar: Mochy mark + "Settings" title slide away on scroll (progressive glass blur
        // behind the gradient, matching Library/Search) while the search field glides up to sit
        // beside the profile avatar.
        SearchGlassTopBar(
            glassState = glassState,
            progressProvider = { collapseProgress },
            pureBlack = true,
            title = stringResource(R.string.settings),
            titleBlockHeight = SettingsTitleBlockHeight,
            titleContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mochy_mark),
                        contentDescription = "Mochy",
                        tint = Color.White,
                        modifier = Modifier.size(SettingsMarkSize),
                    )
                    Text(
                        text = stringResource(R.string.settings),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        lineHeight = 40.sp,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            },
            trailing = {
                // at rest it sits beside the mark like the design, and drops to the search field's middle as the header folds
                SearchAvatar(
                    modifier = Modifier.graphicsLayer {
                        val lift = (SettingsTitleBlockHeight / 2 - SettingsMarkSize / 2).toPx()
                        translationY = -(1f - collapseProgress) * lift
                    },
                    imageUrl = profilePicUri.ifBlank { null },
                    onClick = {
                        // back to the profile page these were opened from, not a second copy of it on top
                        if (!navController.popBackStack("settings", inclusive = false)) navController.navigateToTab("settings")
                    },
                )
            },
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                onSearch = {},
                placeholder = stringResource(R.string.settings_search_placeholder),
                focused = isSearchFocused,
                onFocusChanged = { isSearchFocused = it },
                trailing = if (query.text.isNotEmpty()) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.settings_clear_search),
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { query = TextFieldValue() },
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
internal fun CategoryBubble(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    // lit while its page is open beside the list on a wide screen
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) SelectedBubbleColor else BubbleColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(IconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = Color.White,
            )
        }
    }
}
