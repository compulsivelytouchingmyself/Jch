package com.jarron.jch.ui.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarron.jch.camera.Camera2Helper
import com.jarron.jch.ui.theme.AlertRed
import com.jarron.jch.ui.theme.NeonCyan
import com.jarron.jch.ui.theme.StudioBackground

@Composable
fun CameraViewfinder(
    cameraHelper: Camera2Helper,
    isRecording: Boolean,
    isCameraReady: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }

    DisposableEffect(textureView) {
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                cameraHelper.openCamera(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Handled in Camera2Helper
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraHelper.closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        textureView.surfaceTextureListener = listener
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let { cameraHelper.openCamera(it, textureView.width, textureView.height) }
        }

        onDispose {
            cameraHelper.closeCamera()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "borderAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(StudioBackground)
            .testTag("camera_viewfinder")
    ) {
        // Camera2 Surface
        AndroidView(
            factory = { textureView },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay: Rule of thirds grid & Framing markers
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Grid lines
            val gridColor = Color.White.copy(alpha = 0.12f)
            val stroke = Stroke(width = 1.dp.toPx())

            // Vertical 1/3 and 2/3
            drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = stroke.width)
            drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = stroke.width)

            // Horizontal 1/3 and 2/3
            drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = stroke.width)
            drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = stroke.width)

            // Center crosshair
            val cx = w / 2f
            val cy = h / 2f
            val chLen = 16.dp.toPx()
            val chColor = NeonCyan.copy(alpha = 0.35f)
            drawLine(chColor, Offset(cx - chLen, cy), Offset(cx + chLen, cy), strokeWidth = 1.5.dp.toPx())
            drawLine(chColor, Offset(cx, cy - chLen), Offset(cx, cy + chLen), strokeWidth = 1.5.dp.toPx())
        }

        // Active Recording Cinematic Border
        if (isRecording) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 4.dp,
                        color = AlertRed.copy(alpha = borderAlpha),
                        shape = RoundedCornerShape(20.dp)
                    )
            )
        }

        // Camera loading state indicator if not yet ready
        if (!isCameraReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Initializing Camera2 Stream...",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            }
        }
    }
}
