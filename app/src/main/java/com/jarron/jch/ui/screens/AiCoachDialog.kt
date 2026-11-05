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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.jarron.jch.ai.AiCoachUiState
import com.jarron.jch.ai.AiScriptCoach
import com.jarron.jch.ui.theme.AlertRed
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
import kotlinx.coroutines.launch

@Composable
fun AiCoachDialog(
    coach: AiScriptCoach,
    onLoadTeleprompter: (List<String>) -> Unit,
    onApplyBuffers: (preRollMs: Int, postRollMs: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by coach.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var topicInput by remember { mutableStateOf("Why jump cut videos dominate short-form retention") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(24.dp))
                .background(StudioSurface)
                .border(1.dp, PulsePurple.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(20.dp)
                .testTag("ai_coach_dialog")
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
                                .background(PulsePurple.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = PulsePurple,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Hero Script Coach",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Powered by Gemini 4 Argon · High Thinking",
                                color = PulsePurple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_ai_coach_button")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                // Topic Input
                OutlinedTextField(
                    value = topicInput,
                    onValueChange = { topicInput = it },
                    label = { Text("Script Topic, Hook, or Raw Idea", color = TextSecondary) },
                    placeholder = { Text("e.g. 3 secret productivity hacks", color = TextMuted) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_topic_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PulsePurple,
                        unfocusedBorderColor = StudioBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = StudioBackground,
                        unfocusedContainerColor = StudioBackground
                    ),
                    maxLines = 3
                )

                // Generate Button
                Button(
                    onClick = {
                        scope.launch {
                            coach.generateJumpCutScript(topicInput)
                        }
                    },
                    enabled = uiState !is AiCoachUiState.Loading && topicInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("generate_script_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PulsePurple,
                        contentColor = TextPrimary
                    )
                ) {
                    if (uiState is AiCoachUiState.Loading) {
                        CircularProgressIndicator(
                            color = TextPrimary,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Argon Thinking Deeply...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Jump Cut Script", fontWeight = FontWeight.Bold)
                    }
                }

                // UI State handling
                when (val state = uiState) {
                    is AiCoachUiState.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AlertRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Generation notice: ${state.message}",
                                color = AlertRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    is AiCoachUiState.Success -> {
                        val result = state.result
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StudioBackground),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, StudioBorder, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = result.title,
                                    color = NeonCyan,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                if (result.hook.isNotEmpty()) {
                                    Text(
                                        text = "⚡ Hook: \"${result.hook}\"",
                                        color = ElectricOrange,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Text(
                                    text = "Teleprompter Lines (${result.teleprompterLines.size} cuts):",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                result.teleprompterLines.forEachIndexed { idx, line ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = "#${idx + 1}",
                                            color = PulsePurple,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = line,
                                            color = TextPrimary,
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                if (result.cadenceTip.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Speed,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = result.cadenceTip,
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            onApplyBuffers(result.recommendedPreRollMs, result.recommendedPostRollMs)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("apply_coach_buffers_button"),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                                    ) {
                                        Text("Set Clamps (${result.recommendedPreRollMs}/${result.recommendedPostRollMs}ms)", fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = {
                                            onLoadTeleprompter(result.teleprompterLines)
                                            onDismiss()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("load_teleprompter_button"),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PulsePurple)
                                    ) {
                                        Text("Load Prompter", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
