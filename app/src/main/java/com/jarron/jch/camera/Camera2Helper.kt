package com.jarron.jch.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Camera2Helper(private val context: Context) {
    companion object {
        private const val TAG = "Camera2Helper"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var previewSurface: Surface? = null
    private var recordingSurface: Surface? = null

    private var isTorchOn = false
    private var isFrontFacing = false

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("Camera2Background").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping camera thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        startBackgroundThread()
        closeCamera()

        savedSurfaceTexture = surfaceTexture
        savedWidth = width
        savedHeight = height
        surfaceTexture.setDefaultBufferSize(width, height)
        previewSurface = Surface(surfaceTexture)

        try {
            val cameraId = getCameraId(isFrontFacing)
            if (cameraId == null) {
                _errorMessage.value = "No camera found on device."
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isCameraReady.value = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _isCameraReady.value = false
                    _errorMessage.value = "Camera error code: $error"
                    Log.e(TAG, "Camera open error: $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            _errorMessage.value = "Failed to access camera: ${e.message}"
        }
    }

    fun setRecordingSurface(surface: Surface?) {
        recordingSurface = surface
        // Restart session with both preview and recording surface if camera is active
        if (cameraDevice != null && previewSurface != null) {
            startCaptureSession()
        }
    }

    private fun startCaptureSession() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        try {
            val surfaces = mutableListOf<Surface>(preview)
            recordingSurface?.let { surfaces.add(it) }

            previewRequestBuilder = device.createCaptureRequest(
                if (recordingSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(preview)
                recordingSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                if (isTorchOn) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        previewRequestBuilder?.let {
                            session.setRepeatingRequest(it.build(), null, cameraHandler)
                        }
                        _isCameraReady.value = true
                        _errorMessage.value = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    _isCameraReady.value = false
                    _errorMessage.value = "Camera configuration failed."
                    Log.e(TAG, "Capture session configuration failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture session", e)
        }
    }

    fun toggleTorch(): Boolean {
        if (isFrontFacing) return false // Front camera usually doesn't have torch
        isTorchOn = !isTorchOn
        try {
            previewRequestBuilder?.let { builder ->
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
                captureSession?.setRepeatingRequest(builder.build(), null, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling torch", e)
        }
        return isTorchOn
    }

    private var savedSurfaceTexture: SurfaceTexture? = null
    private var savedWidth: Int = 1280
    private var savedHeight: Int = 720

    fun switchCamera() {
        val texture = savedSurfaceTexture ?: return
        isFrontFacing = !isFrontFacing
        isTorchOn = false
        openCamera(texture, savedWidth, savedHeight)
    }

    fun switchCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        savedSurfaceTexture = surfaceTexture
        savedWidth = width
        savedHeight = height
        switchCamera()
    }

    fun isUsingFrontFacing(): Boolean = isFrontFacing
    fun isTorchActive(): Boolean = isTorchOn

    private fun getCameraId(front: Boolean): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (front && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id
                } else if (!front && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            // Fallback to first available
            return cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            return null
        }
    }

    fun closeCamera() {
        _isCameraReady.value = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (ignored: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (ignored: Exception) {
        }
        cameraDevice = null

        previewSurface?.release()
        previewSurface = null
    }

    fun release() {
        closeCamera()
        stopBackgroundThread()
    }
}
