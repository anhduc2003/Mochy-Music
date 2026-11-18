package com.mochyy.mochymusic.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.constants.AppBackgroundBlurKey
import com.mochyy.mochymusic.constants.AppBackgroundDimKey
import com.mochyy.mochymusic.constants.AppBackgroundGradientIndexKey
import com.mochyy.mochymusic.constants.AppBackgroundStyle
import com.mochyy.mochymusic.constants.AppBackgroundStyleKey
import com.mochyy.mochymusic.constants.AppCustomBackgroundUriKey
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference
import java.io.File
import java.io.FileOutputStream

val LocalHasCustomAppBackground = compositionLocalOf { false }

val AppGradientPresets: List<Pair<Color, Color>> = listOf(
    Color(0xFF0F2027) to Color(0xFF2C5364),
    Color(0xFF1A0B2E) to Color(0xFF4B1654),
    Color(0xFF0A192F) to Color(0xFF1E3A5F),
    Color(0xFF260B17) to Color(0xFF54122E),
    Color(0xFF09221A) to Color(0xFF16523E),
    Color(0xFF24160B) to Color(0xFF523015),
)

@Composable
fun rememberHasCustomAppBackground(): Boolean {
    val (style) = rememberEnumPreference(AppBackgroundStyleKey, AppBackgroundStyle.DEFAULT)
    val (uri) = rememberPreference(AppCustomBackgroundUriKey, "")
    return when (style) {
        AppBackgroundStyle.DEFAULT -> false
        AppBackgroundStyle.CUSTOM_IMAGE -> uri.isNotBlank()
        AppBackgroundStyle.ARTWORK_AMBIENT -> true
        AppBackgroundStyle.CUSTOM_GRADIENT -> true
    }
}

@Composable
fun AppCustomBackground(
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
) {
    val (style) = rememberEnumPreference(AppBackgroundStyleKey, AppBackgroundStyle.DEFAULT)
    val (customUri) = rememberPreference(AppCustomBackgroundUriKey, "")
    val (blurDp) = rememberPreference(AppBackgroundBlurKey, 12f)
    val (dimAlpha) = rememberPreference(AppBackgroundDimKey, 0.55f)
    val (gradientIndex) = rememberPreference(AppBackgroundGradientIndexKey, 0)

    if (style == AppBackgroundStyle.DEFAULT) return
    if (style == AppBackgroundStyle.CUSTOM_IMAGE && customUri.isBlank()) return

    val safeDim = dimAlpha.coerceIn(0.10f, 0.90f)
    val safeBlur = blurDp.coerceIn(0f, 40f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0D))
            .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = safeDim))
            },
    ) {
        when (style) {
            AppBackgroundStyle.DEFAULT -> Unit
            AppBackgroundStyle.CUSTOM_IMAGE -> {
                AsyncImage(
                    model = customUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val boost = 1f + safeBlur * 0.0045f
                            scaleX = boost
                            scaleY = boost
                        }
                        .then(
                            if (safeBlur > 0.5f) {
                                Modifier.blur(safeBlur.dpCompat, BlurredEdgeTreatment.Rectangle)
                            } else {
                                Modifier
                            }
                        ),
                )
            }
            AppBackgroundStyle.ARTWORK_AMBIENT -> {
                Crossfade(targetState = thumbnailUrl, animationSpec = tween(600), label = "appAmbientBg") { artUrl ->
                    if (artUrl.isNullOrBlank()) {
                        val fallback = AppGradientPresets[1]
                        GradientBackdrop(fallback.first, fallback.second)
                    } else {
                        val effectiveBlur = safeBlur.coerceAtLeast(18f)
                        AsyncImage(
                            model = artUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.14f
                                    scaleY = 1.14f
                                }
                                .blur(effectiveBlur.dpCompat, BlurredEdgeTreatment.Rectangle),
                        )
                    }
                }
            }
            AppBackgroundStyle.CUSTOM_GRADIENT -> {
                val pair = AppGradientPresets.getOrElse(gradientIndex) { AppGradientPresets.first() }
                GradientBackdrop(pair.first, pair.second)
            }
        }
    }
}

@Composable
private fun GradientBackdrop(top: Color, bottom: Color) {
    val brush = remember(top, bottom) {
        Brush.linearGradient(
            colors = listOf(top, bottom, Color(0xFF08080B)),
            start = Offset.Zero,
            end = Offset(1200f, 2200f),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush),
    )
}

private val Float.dpCompat get() = androidx.compose.ui.unit.Dp(this)

fun saveCustomAppBackgroundFile(context: Context, sourceUri: Uri): String? {
    return runCatching {
        val decoded = context.contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        val maxEdge = 1600
        val w = decoded.width
        val h = decoded.height
        val scaled = if (maxOf(w, h) > maxEdge && w > 0 && h > 0) {
            val ratio = maxEdge.toFloat() / maxOf(w, h).toFloat()
            Bitmap.createScaledBitmap(decoded, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        context.filesDir.listFiles()?.forEach { existing ->
            if (existing.name.startsWith("app_custom_bg_")) {
                runCatching { existing.delete() }
            }
        }
        val outFile = File(context.filesDir, "app_custom_bg_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.flush()
        }
        Uri.fromFile(outFile).toString()
    }.getOrNull()
}

fun clearCustomAppBackgroundFiles(context: Context) {
    context.filesDir.listFiles()?.forEach { existing ->
        if (existing.name.startsWith("app_custom_bg_")) {
            runCatching { existing.delete() }
        }
    }
}
