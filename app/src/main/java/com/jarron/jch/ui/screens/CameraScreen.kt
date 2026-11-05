package com.jarron.jch.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarron.jch.ai.AiScriptCoach
import com.jarron.jch.audio.AudioCaptureService
import com.jarron.jch.camera.Camera2Helper
import com.jarron.jch.data.RecordedVideo
import com.jarron.jch.data.SettingsManager
import com.jarron.jch.data.VideoStorageManager
import com.jarron.jch.recorder.JumpCutRecorder
import com.jarron.jch.ui.components.CameraViewfinder
import com.jarron.jch.ui.components.LiveSoundMeter
import com.jarron.jch.ui.components.TeleprompterOverlay
import com.jarron.jch.ui.theme.AlertRed
import com.jarron.jch.ui.theme.ElectricOrange
import com.jarron.jch.ui.theme.NeonCyan
import com.jarron.jch.ui.theme.PulsePurple
import com.jarron.jch.ui.theme.SignalGreen
import com.jarron.jch.ui.theme.StudioBackground
import com.jarron.jch.ui.theme.StudioBorder
import com.jarron.jch.ui.theme.StudioSurface
import com.jarron.jch.ui.theme.TextMuted
import com.jarron.jch.ui.theme.TextPrimary
import com.jarron.jch.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun CameraScreen(
    cameraHelper: Camera2Helper,
    audioCaptureService: AudioCaptureService,
    recorder: JumpCutRecorder,
    settingsManager: SettingsManager,
    storageManager: VideoStorageManager,
    aiCoach: AiScriptCoach
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by settingsManager.settings.collectAsState()
    val meterData by audioCaptureService.meterData.collectAsState()
    val recordingStats by recorder.recordingStats.collectAsState()
    val isCameraReady by cameraHelper.isCameraReady.collectAsState()
    val savedVideos by storageManager.videos.collectAsState()

    // Dialog & overlay states
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showGalleryDialog by remember { mutableStateOf(false) }
    var showAiCoachDialog by remember { mutableStateOf(false) }
    var reviewVideo: RecordedVideo? by remember { mutableStateOf(null) }

    // Teleprompter state
    var isTeleprompterActive by remember { mutableStateOf(false) }
    var teleprompterLines by remember {
        mutableStateOf(
            listOf(
                "Welcome to Jump Cut Hero!",
                "Pause naturally between your words.",
                "The pre-roll and post-roll buffers slice out silence gaps.",
                "Hit stop to run the scorched-earth remuxer."
            )
        )
    }

    var isTorchActive by remember { mutableStateOf(false) }

    // Wire completion callback
    recorder.onVideoCompleted = { video ->
        reviewVideo = video
    }
    recorder.onError = { error ->
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .testTag("camera_screen")
    ) {
        // 1. Fullscreen Camera2 Viewfinder - Unobstructed view
        CameraViewfinder(
            cameraHelper = cameraHelper,
            isRecording = recordingStats.isRecording,
            isCameraReady = isCameraReady,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Non-obtrusive Floating HUD Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP SECTION: Compact Bar + Slim Sound Meter Ribbon
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Sleek Top Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(StudioSurface.copy(alpha = 0.85f))
                        .border(1.dp, StudioBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // App Logo
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(NeonCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✂", fontSize = 13.sp)
                        }
                        Text(
                            text = "JUMP CUT HERO",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Top Action Buttons including Camera Switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Switch Camera (Front <-> Back)
                        IconButton(
                            onClick = {
                                cameraHelper.switchCamera()
                                Toast.makeText(
                                    context,
                                    if (cameraHelper.isUsingFrontFacing()) "Switched to Front Camera" else "Switched to Rear Camera",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("switch_camera_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = "Switch Camera Front/Back",
                                tint = NeonCyan,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Torch
                        IconButton(
                            onClick = { isTorchActive = cameraHelper.toggleTorch() },
                            modifier = Modifier.size(34.dp).testTag("toggle_torch_button")
                        ) {
                            Icon(
                                imageVector = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Toggle Torch",
                                tint = if (isTorchActive) ElectricOrange else TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Teleprompter toggle
                        IconButton(
                            onClick = { isTeleprompterActive = !isTeleprompterActive },
                            modifier = Modifier.size(34.dp).testTag("toggle_teleprompter_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Teleprompter",
                                tint = if (isTeleprompterActive) NeonCyan else TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // AI Script Coach
                        IconButton(
                            onClick = { showAiCoachDialog = true },
                            modifier = Modifier.size(34.dp).testTag("open_ai_coach_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Script Coach",
                                tint = PulsePurple,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Vault / Saved Videos
                        IconButton(
                            onClick = { showGalleryDialog = true },
                            modifier = Modifier.size(34.dp).testTag("open_gallery_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = "Vault",
                                tint = TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Settings
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(34.dp).testTag("open_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Buffer Settings",
                                tint = TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                // Slim, non-obtrusive sound meter ribbon docked at the top
                LiveSoundMeter(
                    meterData = meterData,
                    preRollMs = settings.preRollMs,
                    postRollMs = settings.postRollMs,
                    onCalibrateClick = {
                        audioCaptureService.startCalibration()
                        Toast.makeText(context, "Calibrating room tone... keep quiet 2s", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Optional floating teleprompter line (at top edge, never blocking the center)
                AnimatedVisibility(
                    visible = isTeleprompterActive,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    TeleprompterOverlay(
                        lines = teleprompterLines,
                        onClose = { isTeleprompterActive = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // MIDDLE AREA IS DELIBERATELY EMPTY so the creator can frame their shot clearly!

            // BOTTOM CONTROL DOCK
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Buffer Clamps Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(StudioSurface.copy(alpha = 0.8f))
                        .border(1.dp, StudioBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { showSettingsDialog = true }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PRE ${settings.preRollMs}ms",
                        color = ElectricOrange,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("•", color = TextMuted, fontSize = 9.sp)
                    Text(
                        text = "POST ${settings.postRollMs}ms",
                        color = PulsePurple,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("•", color = TextMuted, fontSize = 9.sp)
                    Text(
                        text = "CBR ${settings.audioBitrate / 1000}k",
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Recording HUD (shown while recording or remuxing)
                if (recordingStats.isRecording) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioSurface.copy(alpha = 0.85f))
                            .border(1.dp, AlertRed.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val sec = (recordingStats.elapsedRealtimeMs / 1000) % 60
                        val min = (recordingStats.elapsedRealtimeMs / 1000) / 60
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AlertRed))
                            Text(
                                text = String.format(Locale.US, "REC %02d:%02d", min, sec),
                                color = AlertRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = "${recordingStats.chunkCount} cuts · ~${recordingStats.estimatedSavedMs / 1000}s sliced",
                            color = SignalGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (recordingStats.isRemuxing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioSurface)
                            .border(1.dp, NeonCyan, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = NeonCyan,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Remuxing clean audio...",
                            color = NeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Big Record Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .border(3.dp, if (recordingStats.isRecording) AlertRed else NeonCyan, CircleShape)
                        .clickable(enabled = !recordingStats.isRemuxing) {
                            if (recordingStats.isRecording) {
                                recorder.stopRecording()
                            } else {
                                recorder.startRecording(scope)
                            }
                        }
                        .testTag("record_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (recordingStats.isRecording) {
                        // Stop Square
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AlertRed)
                        )
                    } else {
                        // Record Circle
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(AlertRed)
                        )
                    }
                }
            }
        }

        // Modals & Dialogs
        if (showSettingsDialog) {
            SettingsDialog(
                currentSettings = settings,
                onSave = { newSettings ->
                    settingsManager.updateSettings(newSettings)
                    recorder.updateSettings(newSettings)
                },
                onResetDefaults = {
                    settingsManager.resetToDefaults()
                },
                onDismiss = { showSettingsDialog = false }
            )
        }

        reviewVideo?.let { vid ->
            VideoReviewDialog(
                video = vid,
                onShare = { storageManager.shareVideo(vid) },
                onDismiss = { reviewVideo = null }
            )
        }

        if (showGalleryDialog) {
            GalleryDialog(
                videos = savedVideos,
                onSelectVideo = { vid ->
                    showGalleryDialog = false
                    reviewVideo = vid
                },
                onShareVideo = { vid -> storageManager.shareVideo(vid) },
                onDeleteVideo = { vid -> storageManager.deleteVideo(vid) },
                onDismiss = { showGalleryDialog = false }
            )
        }

        if (showAiCoachDialog) {
            AiCoachDialog(
                coach = aiCoach,
                onLoadTeleprompter = { lines ->
                    teleprompterLines = lines
                    isTeleprompterActive = true
                },
                onApplyBuffers = { preRoll, postRoll ->
                    val updated = settings.copy(preRollMs = preRoll, postRollMs = postRoll)
                    settingsManager.updateSettings(updated)
                    recorder.updateSettings(updated)
                    Toast.makeText(context, "Applied AI buffer clamps: ${preRoll}ms / ${postRoll}ms", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showAiCoachDialog = false }
            )
        }
    }
}
