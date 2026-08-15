package com.stickmanai.android.ai

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Grabs a single low-res front-camera frame on demand - the mobile equivalent of the desktop's
 * hidden webcam.html capture, so each AI-driven character can "see" the real person in front of
 * the device instead of just its own proprioceptive state. No preview UI, ever: this only binds
 * an ImageCapture use case long enough to grab one frame.
 */
class CameraCapture(private val context: Context, private val lifecycleOwner: LifecycleOwner) {

    companion object {
        private const val MAX_DIMENSION = 480
        private const val JPEG_QUALITY = 60
    }

    private var imageCapture: ImageCapture? = null
    private var bound = false

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureBound(): Boolean {
        if (bound) return true
        return try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, capture)
            imageCapture = capture
            bound = true
            true
        } catch (e: Exception) {
            android.util.Log.w("StickmanAI", "no se pudo iniciar la camara", e)
            false
        }
    }

    /** Returns a base64 JPEG of one frame, or null if there's no permission/camera/failure. */
    suspend fun captureBase64(): String? {
        if (!hasPermission()) return null
        // CameraX's bindToLifecycle (inside ensureBound) requires the main thread - the AI loop
        // that calls this runs on Dispatchers.Default, so without this switch bindToLifecycle
        // silently throws (caught below as a warning) and the camera never actually starts.
        return withContext(Dispatchers.Main) {
            if (!ensureBound()) return@withContext null
            val capture = imageCapture ?: return@withContext null

            suspendCancellableCoroutine { cont ->
                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val result = try {
                                encodeJpeg(image)
                            } catch (e: Exception) {
                                android.util.Log.w("StickmanAI", "no se pudo procesar el frame de camara", e)
                                null
                            } finally {
                                image.close()
                            }
                            cont.resume(result)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            android.util.Log.w("StickmanAI", "fallo captura de camara", exception)
                            cont.resume(null)
                        }
                    },
                )
            }
        }
    }

    private fun encodeJpeg(image: ImageProxy): String {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / largestSide
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true,
            )
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
