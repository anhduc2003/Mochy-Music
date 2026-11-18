// customisation's launcher icons: the one in use, and behind a chevron every family's ready-made ones and a custom colour each

package com.mochyy.mochymusic.ui.screens.gesture

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.component.rememberResourcePainter
import com.mochyy.mochymusic.ui.player.findActivity
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.screens.setup.PhotoCropperContainer
import com.mochyy.mochymusic.utils.AppIcon
import com.mochyy.mochymusic.utils.AppIconColor
import com.mochyy.mochymusic.utils.AppIconFamily
import com.mochyy.mochymusic.utils.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mochyy.mochymusic.ui.component.RemoteFocus

private val TileSize = 66.dp
// close to a launcher's squircle, so the tile reads as the icon it will be
private val TileShape = RoundedCornerShape(TileSize * 0.27f)
private val TileRing = Color.White.copy(alpha = 0.12f)
private val TileRingPicked = Color.White.copy(alpha = 0.9f)

// the custom colours sit in two rows of six, which fit across any phone
private val SwatchSize = 30.dp
private const val SwatchesPerRow = 6

@Composable
internal fun IconsSection() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(AppIcons.current(context)) }
    var galleryActive by remember { mutableStateOf(AppIcons.isCustomGalleryActive(context)) }
    var hasGalleryIcon by remember { mutableStateOf(AppIcons.hasCustomGalleryIcon(context)) }
    var galleryVersion by remember { mutableLongStateOf(AppIcons.customGalleryVersion(context)) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // the family whose colours are open under its customize button
    var customizing by remember { mutableStateOf<AppIconFamily?>(null) }
    // each family's custom tile keeps the colour last picked for it, starting from the one in use
    val customColors = remember { mutableStateMapOf<AppIconFamily, AppIconColor>().apply { picked.color?.let { put(picked.family, it) } } }
    val art = rememberIconArt()
    val savedToast = stringResource(R.string.custom_icon_gallery_updated)

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingCropUri = uri
    }

    fun pick(icon: AppIcon) {
        icon.color?.let { customColors[icon.family] = it }
        if (icon == picked && !galleryActive) return
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        picked = icon
        galleryActive = false
        // the next launch's splash goes with it: the icon itself for an anime one, the bare mark for the rest
        context.findActivity()?.let { AppIcons.syncSplash(it, icon) }
        // the package manager is a call to the system, kept off the main thread
        scope.launch(Dispatchers.IO) { AppIcons.apply(context.applicationContext, icon) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LabColors.Card)
            .padding(top = CardTextInset, bottom = 20.dp),
    ) {
        PickedIcon(
            icon = picked,
            galleryActive = galleryActive,
            galleryVersion = galleryVersion,
            art = art,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column {
                GalleryIconSection(
                    hasGalleryIcon = hasGalleryIcon,
                    galleryActive = galleryActive,
                    galleryVersion = galleryVersion,
                    onPickPhoto = {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onSelectExisting = {
                        if (hasGalleryIcon) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            AppIcons.activateCustomGalleryIcon(context)
                            galleryActive = true
                        }
                    },
                    onPinHome = {
                        scope.launch(Dispatchers.IO) {
                            AppIcons.pinCustomGalleryIcon(context.applicationContext)
                        }
                    },
                    onReset = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        AppIcons.clearCustomGalleryIcon(context)
                        hasGalleryIcon = false
                        galleryActive = false
                        galleryVersion = System.currentTimeMillis()
                    },
                )
                AppIconFamily.entries.forEach { family ->
                    FamilyIcons(
                        family = family,
                        picked = picked.takeUnless { galleryActive },
                        customColor = customColors[family] ?: AppIconColor.entries.first(),
                        art = art,
                        customizing = customizing == family,
                        onPick = ::pick,
                        onCustomize = { customizing = if (customizing == family) null else family },
                    )
                }
                Text(
                    text = stringResource(R.string.customise_icons_caption),
                    style = CaptionStyle,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = CardTextInset, end = CardTextInset, top = 16.dp),
                )
            }
        }
    }

    if (pendingCropUri != null) {
        Dialog(
            onDismissRequest = { pendingCropUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize()) {
                PhotoCropperContainer(
                    uri = pendingCropUri,
                    onDone = { croppedUri ->
                        scope.launch(Dispatchers.IO) {
                            val ok = AppIcons.saveCustomGalleryIcon(context.applicationContext, croppedUri, autoPinIfNeeded = true)
                            withContext(Dispatchers.Main) {
                                pendingCropUri = null
                                if (ok) {
                                    hasGalleryIcon = true
                                    galleryActive = true
                                    galleryVersion = AppIcons.customGalleryVersion(context)
                                    Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onCancel = { pendingCropUri = null },
                    onSelectNewImage = {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    title = stringResource(R.string.custom_icon_gallery_adjust),
                ) {}
            }
        }
    }
}

// each piece of art loaded once and shared by every tile, through coil so the anime bitmap decodes off the main thread (it cost 20 ms on the page's first frame)
@Composable
private fun rememberIconArt(): Map<Int, Painter> {
    val ids = remember { AppIcon.entries.map { it.foreground }.distinct() }
    return ids.associateWith { rememberResourcePainter(it) }
}

// the icon in use with its name and family, and the chevron that opens every other choice
@Composable
private fun PickedIcon(
    icon: AppIcon,
    galleryActive: Boolean,
    galleryVersion: Long,
    art: Map<Int, Painter>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "iconsChevron")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onToggle)
            .padding(horizontal = CardTextInset),
    ) {
        Text(
            text = stringResource(R.string.customise_icons),
            style = CardLabelStyle,
            color = Color.White.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (galleryActive) {
                GalleryIconArt(version = galleryVersion, picked = true)
            } else {
                IconArt(icon = icon, art = art, picked = true)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(if (galleryActive) R.string.custom_icon_gallery_title else icon.label),
                    style = CardLabelStyle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(if (galleryActive) R.string.custom_icon_gallery_subtitle else icon.family.title),
                    style = CaptionStyle,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .pressBounce(interaction, pressedScale = 0.9f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.expand_more),
                    contentDescription = stringResource(if (expanded) R.string.customise_icons_show_less else R.string.customise_icons_show_all),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = turn },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryIconSection(
    hasGalleryIcon: Boolean,
    galleryActive: Boolean,
    galleryVersion: Long,
    onPickPhoto: () -> Unit,
    onSelectExisting: () -> Unit,
    onPinHome: () -> Unit,
    onReset: () -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = stringResource(R.string.custom_icon_gallery_title),
        style = CaptionStyle,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = CardTextInset),
    )
    Spacer(Modifier.height(10.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardTextInset),
    ) {
        if (hasGalleryIcon) {
            GalleryIconArt(
                version = galleryVersion,
                picked = galleryActive,
                modifier = Modifier.clickable(onClick = onSelectExisting),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(TileSize)
                    .clip(TileShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, TileRing, TileShape)
                    .clickable(onClick = onPickPhoto),
            ) {
                Icon(
                    painter = painterResource(R.drawable.image),
                    contentDescription = stringResource(R.string.custom_icon_gallery_pick),
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPillButton(
                    text = stringResource(if (hasGalleryIcon) R.string.custom_icon_gallery_change else R.string.custom_icon_gallery_pick),
                    emphasized = !hasGalleryIcon,
                    onClick = onPickPhoto,
                )
                if (hasGalleryIcon) {
                    ActionPillButton(
                        text = stringResource(R.string.custom_icon_gallery_pin),
                        emphasized = true,
                        onClick = onPinHome,
                    )
                    ActionPillButton(
                        text = stringResource(R.string.custom_icon_gallery_reset),
                        emphasized = false,
                        onClick = onReset,
                    )
                }
            }
            Text(
                text = stringResource(R.string.custom_icon_gallery_pinned_hint),
                style = CaptionStyle,
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun ActionPillButton(text: String, emphasized: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = CaptionStyle,
        color = if (emphasized) Color.Black else Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.94f)
            .clip(CircleShape)
            .background(if (emphasized) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun GalleryIconArt(version: Long, picked: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ring by animateColorAsState(if (picked) TileRingPicked else TileRing, tween(220), label = "galleryIconRing")
    val model = remember(version) {
        val file = AppIcons.customIconFile(context)
        ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey("custom_app_icon_$version")
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(TileSize)
            .clip(TileShape)
            .background(Color(0xFF1C1C1F))
            .border(if (picked) 2.dp else 1.dp, ring, TileShape),
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// one family: its ready-made icons swiped sideways with its custom one last, and its colours under customize
@Composable
private fun FamilyIcons(
    family: AppIconFamily,
    picked: AppIcon?,
    customColor: AppIconColor,
    art: Map<Int, Painter>,
    customizing: Boolean,
    onPick: (AppIcon) -> Unit,
    onCustomize: () -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = stringResource(family.title),
        style = CaptionStyle,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = CardTextInset),
    )
    Spacer(Modifier.height(10.dp))
    // swiped sideways: a family's tiles and its custom one don't fit across a phone with their names
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = CardTextInset)),
    ) {
        AppIcon.presets.filter { it.family == family }.forEach { icon ->
            IconTile(icon = icon, art = art, picked = icon == picked, onClick = { onPick(icon) }) {
                TileLabel(text = stringResource(icon.label), picked = icon == picked)
            }
        }
        val custom = AppIcon.custom(family, customColor)
        IconTile(icon = custom, art = art, picked = custom == picked, onClick = { onPick(custom) }) {
            CustomizeButton(open = customizing, onClick = onCustomize)
        }
    }
    AnimatedVisibility(
        visible = customizing,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        ColorSwatches(selected = customColor, onPick = { color -> onPick(AppIcon.custom(family, color)) })
    }
}

// a tile you tap to wear that icon, with its name or its customize button under it
@Composable
private fun IconTile(icon: AppIcon, art: Map<Int, Painter>, picked: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(TileSize + 10.dp),
    ) {
        IconArt(
            icon = icon,
            art = art,
            picked = picked,
            modifier = Modifier
                .pressBounce(interaction, pressedScale = 0.94f)
                .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick),
        )
        Spacer(Modifier.height(8.dp))
        label()
    }
}

/** the icon as the launcher will draw it: its background, and its art at the adaptive icon's 108 to 72 crop */
@Composable
private fun IconArt(icon: AppIcon, art: Map<Int, Painter>, picked: Boolean, modifier: Modifier = Modifier) {
    val ring by animateColorAsState(if (picked) TileRingPicked else TileRing, tween(220), label = "iconRing")
    // a custom tile fades between colours as they're picked; a see-through one has nothing behind its art
    val fill by animateColorAsState(icon.background?.let { colorResource(it) } ?: Color.Transparent, tween(220), label = "iconFill")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(TileSize)
            .clip(TileShape)
            .drawBehind { drawRect(fill) }
            .border(if (picked) 2.dp else 1.dp, ring, TileShape),
    ) {
        Image(
            painter = art.getValue(icon.foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.requiredSize(TileSize * 1.5f),
        )
    }
}

@Composable
private fun TileLabel(text: String, picked: Boolean) {
    Text(
        text = text,
        style = CaptionStyle,
        color = Color.White.copy(alpha = if (picked) 0.95f else 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// the pill under a custom tile that opens and closes its family's colours, filled while they're open
@Composable
private fun CustomizeButton(open: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (open) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.1f), tween(220), label = "customizeFill")
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = stringResource(R.string.app_icon_customize),
        style = CaptionStyle,
        color = if (open) Color.Black else Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.92f)
            .clip(CircleShape)
            .drawBehind { drawRect(fill) }
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

// the twelve custom colours; tapping one puts the family's art on it straight away
@Composable
private fun ColorSwatches(selected: AppIconColor, onPick: (AppIconColor) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardTextInset, end = CardTextInset, top = 16.dp),
    ) {
        AppIconColor.entries.chunked(SwatchesPerRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                row.forEach { color -> Swatch(color = color, selected = color == selected, onClick = { onPick(color) }) }
            }
        }
    }
}

@Composable
private fun Swatch(color: AppIconColor, selected: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) TileRingPicked else TileRing, tween(220), label = "swatchRing")
    val name = stringResource(color.label)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .pressBounce(interaction, pressedScale = 0.9f)
            .clip(CircleShape)
            .background(colorResource(color.background))
            .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
    )
}
