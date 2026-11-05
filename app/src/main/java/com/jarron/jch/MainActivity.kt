package com.jarron.jch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jarron.jch.ai.AiScriptCoach
import com.jarron.jch.audio.AudioCaptureService
import com.jarron.jch.camera.Camera2Helper
import com.jarron.jch.data.SettingsManager
import com.jarron.jch.data.VideoStorageManager
import com.jarron.jch.recorder.JumpCutRecorder
import com.jarron.jch.ui.screens.CameraScreen
import com.jarron.jch.ui.screens.PermissionGate
import com.jarron.jch.ui.theme.MyApplicationTheme
import com.jarron.jch.ui.theme.StudioBackground

class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var storageManager: VideoStorageManager
    private lateinit var cameraHelper: Camera2Helper
    private lateinit var audioCaptureService: AudioCaptureService
    private lateinit var recorder: JumpCutRecorder
    private lateinit var aiCoach: AiScriptCoach

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsManager = SettingsManager(applicationContext)
        storageManager = VideoStorageManager(applicationContext)
        cameraHelper = Camera2Helper(applicationContext)
        audioCaptureService = AudioCaptureService(settingsManager.settings.value)
        recorder = JumpCutRecorder(
            context = applicationContext,
            cameraHelper = cameraHelper,
            audioCaptureService = audioCaptureService,
            storageManager = storageManager,
            settings = settingsManager.settings.value
        )
        aiCoach = AiScriptCoach()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = StudioBackground
                ) {
                    JumpCutHeroApp(
                        cameraHelper = cameraHelper,
                        audioCaptureService = audioCaptureService,
                        recorder = recorder,
                        settingsManager = settingsManager,
                        storageManager = storageManager,
                        aiCoach = aiCoach
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioCaptureService.stopCapture()
        recorder.release()
        cameraHelper.release()
    }
}

@Composable
fun JumpCutHeroApp(
    cameraHelper: Camera2Helper,
    audioCaptureService: AudioCaptureService,
    recorder: JumpCutRecorder,
    settingsManager: SettingsManager,
    storageManager: VideoStorageManager,
    aiCoach: AiScriptCoach
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            audioCaptureService.startCapture(scope)
            recorder.prepareRecording()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioCaptureService.stopCapture()
        }
    }

    if (hasPermissions) {
        CameraScreen(
            cameraHelper = cameraHelper,
            audioCaptureService = audioCaptureService,
            recorder = recorder,
            settingsManager = settingsManager,
            storageManager = storageManager,
            aiCoach = aiCoach
        )
    } else {
        PermissionGate(
            onPermissionsGranted = {
                hasPermissions = true
            }
        )
    }
}
