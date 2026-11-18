package com.mochyy.mochymusic.ui.screens.gesture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.AppBackgroundBlurKey
import com.mochyy.mochymusic.constants.AppBackgroundDimKey
import com.mochyy.mochymusic.constants.AppBackgroundGradientIndexKey
import com.mochyy.mochymusic.constants.AppBackgroundStyle
import com.mochyy.mochymusic.constants.AppBackgroundStyleKey
import com.mochyy.mochymusic.constants.AppCustomBackgroundUriKey
import com.mochyy.mochymusic.ui.component.AppCustomBackground
import com.mochyy.mochymusic.ui.component.AppGradientPresets
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.clearCustomAppBackgroundFiles
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.component.saveCustomAppBackgroundFile
import com.mochyy.mochymusic.ui.screens.audiolab.LabCard
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun AppBackgroundSection() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val connection = LocalPlayerConnection.current
    val mediaMetadata by connection?.mediaMetadata?.collectAsState() ?: remember { androidx.compose.runtime.mutableStateOf(null) }

    val (style, setStyle) = rememberEnumPreference(AppBackgroundStyleKey, AppBackgroundStyle.DEFAULT)
    val (customUri, setCustomUri) = rememberPreference(AppCustomBackgroundUriKey, "")
    val (blurDp, setBlurDp) = rememberPreference(AppBackgroundBlurKey, 12f)
    val (dimAlpha, setDimAlpha) = rememberPreference(AppBackgroundDimKey, 0.55f)
    val (gradientIndex, setGradientIndex) = rememberPreference(AppBackgroundGradientIndexKey, 0)

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val saved = saveCustomAppBackgroundFile(context.applicationContext, uri)
                if (saved != null) {
                    withContext(Dispatchers.Main) {
                        setCustomUri(saved)
                        setStyle(AppBackgroundStyle.CUSTOM_IMAGE)
                    }
                }
            }
        }
    }

    LabCard(padding = 20.dp) {
        Text(
            text = stringResource(R.string.custom_app_bg_title),
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = (-0.02).em,
            color = LabColors.Primary,
        )
        Text(
            text = stringResource(R.string.custom_app_bg_desc),
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = LabColors.Secondary,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(16.dp))

        // Live mini-app preview frame
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
        ) {
            AppCustomBackground(thumbnailUrl = mediaMetadata?.thumbnailUrl)
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.85f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .width(78.dp)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.72f)),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(54.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Style selector chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            AppBgChip(
                label = stringResource(R.string.custom_app_bg_default),
                selected = style == AppBackgroundStyle.DEFAULT,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    setStyle(AppBackgroundStyle.DEFAULT)
                },
            )
            AppBgChip(
                label = stringResource(R.string.custom_app_bg_image),
                selected = style == AppBackgroundStyle.CUSTOM_IMAGE,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    if (customUri.isBlank()) {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        setStyle(AppBackgroundStyle.CUSTOM_IMAGE)
                    }
                },
            )
            AppBgChip(
                label = stringResource(R.string.custom_app_bg_ambient),
                selected = style == AppBackgroundStyle.ARTWORK_AMBIENT,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    setStyle(AppBackgroundStyle.ARTWORK_AMBIENT)
                },
            )
            AppBgChip(
                label = stringResource(R.string.custom_app_bg_gradient),
                selected = style == AppBackgroundStyle.CUSTOM_GRADIENT,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    setStyle(AppBackgroundStyle.CUSTOM_GRADIENT)
                },
            )
        }

        AnimatedVisibility(
            visible = style == AppBackgroundStyle.CUSTOM_IMAGE,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.92f))
                        .clickable {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.image),
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (customUri.isBlank()) R.string.custom_app_bg_pick else R.string.custom_app_bg_change),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color.Black,
                    )
                }
                if (customUri.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.custom_icon_gallery_reset),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                clearCustomAppBackgroundFiles(context.applicationContext)
                                setCustomUri("")
                                setStyle(AppBackgroundStyle.DEFAULT)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = style == AppBackgroundStyle.CUSTOM_GRADIENT,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                AppGradientPresets.forEachIndexed { idx, (c1, c2) ->
                    val picked = gradientIndex == idx
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(c1, c2), Offset.Zero, Offset(100f, 100f)))
                            .border(
                                width = if (picked) 2.5.dp else 1.dp,
                                color = if (picked) Color.White else Color.White.copy(alpha = 0.2f),
                                shape = CircleShape,
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                setGradientIndex(idx)
                            },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = style != AppBackgroundStyle.DEFAULT,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                if (style == AppBackgroundStyle.CUSTOM_IMAGE || style == AppBackgroundStyle.ARTWORK_AMBIENT) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.custom_app_bg_blur),
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = LabColors.Secondary,
                        )
                        Text(
                            text = "${blurDp.roundToInt()} px",
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = LabColors.Primary,
                        )
                    }
                    Slider(
                        value = blurDp,
                        onValueChange = setBlurDp,
                        valueRange = 0f..36f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White.copy(alpha = 0.85f),
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.custom_app_bg_dim),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = LabColors.Secondary,
                    )
                    Text(
                        text = "${(dimAlpha * 100).roundToInt()}%",
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = LabColors.Primary,
                    )
                }
                Slider(
                    value = dimAlpha,
                    onValueChange = setDimAlpha,
                    valueRange = 0.15f..0.85f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.85f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun AppBgChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f),
        tween(200),
        label = "appBgChip",
    )
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = label,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.94f)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
