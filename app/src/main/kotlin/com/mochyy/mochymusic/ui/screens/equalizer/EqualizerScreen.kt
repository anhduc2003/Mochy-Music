// audio lab's equalizer: one curve shaped as a graph or as sliders, with an optional headphone correction

package com.mochyy.mochymusic.ui.screens.equalizer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.eq.EqPreset
import com.mochyy.mochymusic.eq.EqPresets
import com.mochyy.mochymusic.eq.EqSettings
import com.mochyy.mochymusic.ui.component.ActionPromptDialog
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.component.TextFieldDialog
import com.mochyy.mochymusic.ui.screens.audiolab.LabCard
import com.mochyy.mochymusic.ui.screens.audiolab.LabChip
import com.mochyy.mochymusic.ui.screens.audiolab.LabChips
import com.mochyy.mochymusic.ui.screens.audiolab.LabSectionLabel
import com.mochyy.mochymusic.ui.screens.audiolab.LabSegmented
import com.mochyy.mochymusic.ui.screens.audiolab.LabSlider
import com.mochyy.mochymusic.ui.screens.audiolab.LabTextAction
import com.mochyy.mochymusic.ui.screens.audiolab.LabToggleRow
import com.mochyy.mochymusic.ui.screens.audiolab.ListenToAudio
import com.mochyy.mochymusic.ui.screens.audiolab.animatedDim
import com.mochyy.mochymusic.viewmodels.AudioLabViewModel
import com.mochyy.mochymusic.viewmodels.isFlat
import kotlin.math.roundToInt

private val Tabs = listOf(R.string.eq_graph, R.string.eq_bands, R.string.eq_autoeq)

@Composable
fun EqualizerScreen(
    navController: NavController,
    viewModel: AudioLabViewModel = hiltViewModel(),
) {
    val eq by viewModel.eq.collectAsState()
    val presets by viewModel.presets.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedNode by rememberSaveable { mutableIntStateOf(0) }
    var savingPreset by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EqPreset?>(null) }

    // the graph's spectrum listens while this page is open
    ListenToAudio()

    SubSettingsScaffold(
        title = stringResource(R.string.equalizer),
        backLabel = stringResource(R.string.audio_lab),
        onBack = { navController.navigateUp() },
    ) {
        LabCard(padding = 10.dp) {
            LabToggleRow(
                title = stringResource(R.string.equalizer),
                subtitle = statusLine(eq, presets),
                checked = eq.enabled,
                onCheckedChange = viewModel::setEnabled,
                leadingDot = true,
            )
        }
        Spacer(Modifier.height(14.dp))
        LabSegmented(options = Tabs.map { stringResource(it) }, selectedIndex = tab, onSelect = { tab = it })
        Spacer(Modifier.height(18.dp))

        val dim = animatedDim(!eq.enabled)
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = dim }) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(220, 60)) + slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f)) { it / 8 * dir }) togetherWith fadeOut(tween(120))
                },
                label = "eqTab",
            ) { page ->
                Column(Modifier.fillMaxWidth()) {
                    when (page) {
                        0, 1 -> {
                            Presets(
                                eq = eq,
                                presets = presets,
                                onPick = viewModel::applyPreset,
                                onReset = viewModel::reset,
                                onSave = { savingPreset = true },
                                onLongPress = { id -> deleting = presets.firstOrNull { it.id == id } },
                            )
                            Spacer(Modifier.height(18.dp))
                            if (page == 0) {
                                EqGraphSection(
                                    nodes = eq.nodes,
                                    selected = selectedNode,
                                    onSelect = { selectedNode = it },
                                    onMove = { i, f, g -> viewModel.updateNode(i) { it.copy(frequency = f, gain = g) } },
                                    onAdd = { f, g -> viewModel.addNode(f, g).takeIf { it >= 0 }?.let { selectedNode = it } },
                                    onUpdate = viewModel::updateNode,
                                    onRemove = { i ->
                                        viewModel.removeNode(i)
                                        selectedNode = (i - 1).coerceAtLeast(0)
                                    },
                                    canAdd = eq.nodes.size < AudioLabViewModel.MaxNodes,
                                )
                            } else {
                                EqBandsSection(
                                    nodes = eq.nodes,
                                    onGain = { i, g -> viewModel.updateNode(i) { it.copy(gain = g) } },
                                )
                            }
                        }
                        else -> AutoEqTab(viewModel = viewModel, eq = eq)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Output(eq = eq, headroom = viewModel.autoHeadroom(eq), viewModel = viewModel)
        }
        Spacer(Modifier.height(120.dp))
    }

    if (savingPreset) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.eq_save_preset)) },
            placeholder = { Text(stringResource(R.string.eq_preset_placeholder)) },
            onDone = viewModel::savePreset,
            onDismiss = { savingPreset = false },
        )
    }
    deleting?.let { preset ->
        ActionPromptDialog(
            title = stringResource(R.string.eq_delete_preset, preset.name),
            onDismiss = { deleting = null },
            onCancel = { deleting = null },
            onConfirm = {
                viewModel.deletePreset(preset.id)
                deleting = null
            },
        )
    }
}

private fun statusLine(eq: EqSettings, presets: List<EqPreset>): String {
    if (!eq.enabled) return "Off"
    val curve = eq.presetId?.let { id ->
        EqPresets.BuiltIn.firstOrNull { it.id == id }?.name ?: presets.firstOrNull { it.id == id }?.name
    } ?: "Custom"
    val correction = eq.correction?.takeIf { eq.correctionEnabled }?.model
    return when {
        eq.isFlat() -> "On · Flat"
        correction != null && eq.presetId == EqPresets.FlatId -> "On · $correction"
        correction != null -> "On · $curve + $correction"
        else -> "On · $curve"
    }
}

@Composable
private fun Presets(
    eq: EqSettings,
    presets: List<EqPreset>,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onLongPress: (String) -> Unit,
) {
    LabSectionLabel(stringResource(R.string.eq_presets)) {
        LabTextAction(stringResource(R.string.reset), R.drawable.refresh, onClick = onReset)
        LabTextAction(stringResource(R.string.save), R.drawable.add, onClick = onSave)
    }
    val chips = buildList {
        if (eq.presetId == null) add(LabChip("custom", stringResource(R.string.eq_custom)))
        presets.forEach { add(LabChip(it.id, it.name, dot = androidx.compose.ui.graphics.Color.White)) }
        EqPresets.BuiltIn.forEach { add(LabChip(it.id, it.name)) }
    }
    LabChips(
        chips = chips,
        selectedKey = eq.presetId ?: "custom",
        onSelect = { if (it != "custom") onPick(it) },
        onLongPress = { id -> if (presets.any { it.id == id }) onLongPress(id) },
    )
}

@Composable
private fun Output(eq: EqSettings, headroom: Double, viewModel: AudioLabViewModel) {
    LabSectionLabel(stringResource(R.string.eq_output))
    LabCard(padding = 16.dp) {
        LabSlider(
            label = stringResource(R.string.eq_preamp),
            valueText = (if (eq.preamp > 0) "+" else "") + String.format("%.1f dB", eq.preamp),
            value = eq.preamp.toFloat(),
            onValueChange = { viewModel.setPreamp((it * 2).roundToInt() / 2.0) },
            valueRange = -12f..6f,
        )
        Spacer(Modifier.height(6.dp))
        LabToggleRow(
            title = stringResource(R.string.eq_auto_preamp),
            subtitle = if (eq.autoPreamp) {
                if (headroom < -0.05) stringResource(R.string.eq_lowering, String.format("%.1f", -headroom))
                else stringResource(R.string.eq_nothing_to_lower)
            } else stringResource(R.string.eq_auto_preamp_off),
            checked = eq.autoPreamp,
            onCheckedChange = viewModel::setAutoPreamp,
        )
    }
}
