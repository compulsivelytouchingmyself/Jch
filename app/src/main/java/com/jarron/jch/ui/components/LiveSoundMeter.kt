package com.jarron.jch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarron.jch.audio.AudioMeterData
import com.jarron.jch.audio.SpeechState
import com.jarron.jch.ui.theme.AlertRed
import com.jarron.jch.ui.theme.AmberWarning
import com.jarron.jch.ui.theme.MeterGreen
import com.jarron.jch.ui.theme.MeterOrange
import com.jarron.jch.ui.theme.MeterRed
import com.jarron.jch.ui.theme.MeterYellow
import com.jarron.jch.ui.theme.NeonCyan
import com.jarron.jch.ui.theme.PulsePurple
import com.jarron.jch.ui.theme.SignalGreen
import com.jarron.jch.ui.theme.StudioBackground
import com.jarron.jch.ui.theme.StudioBorder
import com.jarron.jch.ui.theme.StudioSurface
import com.jarron.jch.ui.theme.TextMuted
import com.jarron.jch.ui.theme.TextPrimary
import java.util.Locale

/**
 * Ultra-slim broadcast audio ribbon.
 * Designed to never obstruct the camera viewfinder, occupying minimal screen space.
 */
@Composable
fun LiveSoundMeter(
    meterData: AudioMeterData,
    preRollMs: Int,
    postRollMs: Int,
    onCalibrateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val animatedRawDb by animateFloatAsState(
        targetValue = meterData.rawDb,
        animationSpec = tween(durationMillis = 60),
        label = "rawDb"
    )

    val stateColor by animateColorAsState(
        targetValue = when (meterData.state) {
            SpeechState.SPEAKING -> NeonCyan
            SpeechState.PRE_ROLL -> AmberWarning
            SpeechState.POST_ROLL -> PulsePurple
            SpeechState.SILENCE_CUT -> AlertRed
            SpeechState.IDLE_MONITORING -> SignalGreen
        },
        label = "stateColor"
    )

    val stateBadge = when {
        meterData.isCalibrating -> "CALIBRATING 2s..."
        meterData.state == SpeechState.SPEAKING -> "VOICE"
        meterData.state == SpeechState.PRE_ROLL -> "PRE-ROLL"
        meterData.state == SpeechState.POST_ROLL -> "POST-ROLL"
        meterData.state == SpeechState.SILENCE_CUT -> "CUT"
        else -> "ROOM TONE"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StudioSurface.copy(alpha = 0.75f))
            .border(1.dp, StudioBorder.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("live_sound_meter")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // State indicator pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(stateColor.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Text(
                    text = stateBadge,
                    color = stateColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            // Compact 6dp audio meter track with room-tone baseline tick
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(StudioBackground)
            ) {
                val fraction = (animatedRawDb / 100f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(MeterGreen, MeterYellow, MeterOrange, MeterRed)
                            )
                        )
                )
            }

            // Numeric dB readout
            Text(
                text = String.format(Locale.US, "%.1fdB", animatedRawDb),
                color = TextPrimary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            // Room tone reference
            Text(
                text = String.format(Locale.US, "RT:%.0f", meterData.roomToneBaselineDb),
                color = TextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

            // Quick calibrate button
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(NeonCyan.copy(alpha = 0.15f))
                    .clickable { onCalibrateClick() }
                    .testTag("calibrate_rt_pill"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Calibrate Room Tone",
                    tint = NeonCyan,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}
