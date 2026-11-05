package com.jarron.jch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarron.jch.data.JumpCutSettings
import com.jarron.jch.ui.theme.ElectricOrange
import com.jarron.jch.ui.theme.NeonCyan
import com.jarron.jch.ui.theme.PulsePurple
import com.jarron.jch.ui.theme.StudioBackground
import com.jarron.jch.ui.theme.StudioBorder
import com.jarron.jch.ui.theme.StudioSurface
import com.jarron.jch.ui.theme.StudioSurfaceVariant
import com.jarron.jch.ui.theme.TextMuted
import com.jarron.jch.ui.theme.TextPrimary
import com.jarron.jch.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun SettingsDialog(
    currentSettings: JumpCutSettings,
    onSave: (JumpCutSettings) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit
) {
    var preRollMs by remember { mutableIntStateOf(currentSettings.preRollMs) }
    var postRollMs by remember { mutableIntStateOf(currentSettings.postRollMs) }
    var noiseGateOffsetDb by remember { mutableFloatStateOf(currentSettings.noiseGateOffsetDb) }
    var compressorRatio by remember { mutableFloatStateOf(currentSettings.compressorRatio) }
    var audioBitrate by remember { mutableIntStateOf(currentSettings.audioBitrate) }
    var videoResolution by remember { mutableStateOf(currentSettings.videoResolution) }
    var autoCalibrate by remember { mutableStateOf(currentSettings.autoCalibrateRoomTone) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .background(StudioSurface)
                .border(1.dp, StudioBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
                .testTag("settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(NeonCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Broadcast Buffer Clamps",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Radio-style delay & slicing calibration",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted
                        )
                    }
                }

                // Educational Broadcast Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = ElectricOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Broadcast delay buffer manipulation: The pre-roll buffer recovers the instant before words begin (so consonants don't get clipped), while the post-roll buffer prevents cutting breaths and natural phrase endings.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Pre-Roll Buffer Clamp Setting
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Pre-Roll Buffer Clamp",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Prepended audio/video prior to speech detection",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = "${preRollMs} ms",
                            color = ElectricOrange,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = preRollMs.toFloat(),
                        onValueChange = { preRollMs = it.toInt() },
                        valueRange = 50f..800f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricOrange,
                            activeTrackColor = ElectricOrange,
                            inactiveTrackColor = StudioBackground
                        ),
                        modifier = Modifier.testTag("slider_pre_roll")
                    )
                }

                // Post-Roll Buffer Clamp Setting
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Post-Roll Buffer Clamp",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Trailing hold duration after speech pauses",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = "${postRollMs} ms",
                            color = PulsePurple,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = postRollMs.toFloat(),
                        onValueChange = { postRollMs = it.toInt() },
                        valueRange = 50f..1200f,
                        steps = 22,
                        colors = SliderDefaults.colors(
                            thumbColor = PulsePurple,
                            activeTrackColor = PulsePurple,
                            inactiveTrackColor = StudioBackground
                        ),
                        modifier = Modifier.testTag("slider_post_roll")
                    )
                }

                // Noise Gate Offset (relative to room tone)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Speech Threshold Offset",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Decibels required above ambient room tone",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%+.1f dB", noiseGateOffsetDb),
                            color = NeonCyan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = noiseGateOffsetDb,
                        onValueChange = { noiseGateOffsetDb = it },
                        valueRange = -5f..20f,
                        steps = 24,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan,
                            inactiveTrackColor = StudioBackground
                        ),
                        modifier = Modifier.testTag("slider_gate_offset")
                    )
                }

                // Dynamic Range Compressor Ratio
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Compressor Separation Ratio",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Sharpens contrast between room tone & loud voice",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.1f:1", compressorRatio),
                            color = NeonCyan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = compressorRatio,
                        onValueChange = { compressorRatio = it },
                        valueRange = 1.5f..10.0f,
                        steps = 17,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan,
                            inactiveTrackColor = StudioBackground
                        ),
                        modifier = Modifier.testTag("slider_compressor_ratio")
                    )
                }

                // CBR Audio Bitrate Selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Constant Bit Rate (CBR) Audio",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Enables precise scorched-earth timestamp recalculation",
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(96_000 to "96 kbps", 128_000 to "128 kbps", 192_000 to "192 kbps", 256_000 to "256 kbps").forEach { (rate, label) ->
                            FilterChip(
                                selected = audioBitrate == rate,
                                onClick = { audioBitrate = rate },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonCyan
                                )
                            )
                        }
                    }
                }

                // Video Resolution
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Camera2 Resolution",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1080p" to "1080p FHD (1920x1080)", "720p" to "720p HD (1280x720)").forEach { (res, label) ->
                            FilterChip(
                                selected = videoResolution == res,
                                onClick = { videoResolution = res },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricOrange.copy(alpha = 0.2f),
                                    selectedLabelColor = ElectricOrange
                                )
                            )
                        }
                    }
                }

                // Auto room tone calibration toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Continuous Room Tone Averaging",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Dynamically adjusts ambient floor as background shifts",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = autoCalibrate,
                        onCheckedChange = { autoCalibrate = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.3f)
                        )
                    )
                }

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            onResetDefaults()
                            val def = JumpCutSettings()
                            preRollMs = def.preRollMs
                            postRollMs = def.postRollMs
                            noiseGateOffsetDb = def.noiseGateOffsetDb
                            compressorRatio = def.compressorRatio
                            audioBitrate = def.audioBitrate
                            videoResolution = def.videoResolution
                            autoCalibrate = def.autoCalibrateRoomTone
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        modifier = Modifier.testTag("reset_defaults_button")
                    ) {
                        Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            onSave(
                                JumpCutSettings(
                                    preRollMs = preRollMs,
                                    postRollMs = postRollMs,
                                    noiseGateOffsetDb = noiseGateOffsetDb,
                                    compressorRatio = compressorRatio,
                                    audioBitrate = audioBitrate,
                                    videoResolution = videoResolution,
                                    autoCalibrateRoomTone = autoCalibrate
                                )
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = StudioBackground),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("save_settings_button")
                    ) {
                        Text("Save Clamps", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
