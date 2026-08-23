package com.stickmanai.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Lets a character actually tap the screen (other apps included), the mobile equivalent of the
 * desktop's click/move_mouse actions - dispatching gestures is the only way to do that on
 * Android without root. Also grabs a raw pixel screenshot (captureScreenshotBase64) so the AI can
 * see what's on screen, the same way CameraCapture lets it see the room. Both need the user to
 * enable this manually under Settings > Accessibility (see MainActivity's grant button); until
 * then both are simply no-ops, same fallback style as CameraCapture without camera permission.
 *
 * canRetrieveWindowContent + flagRetrieveInteractiveWindows are on in tap_accessibility_service.xml
 * so isKeyboardVisible() below can see the system's window LIST (type/bounds of each top-level
 * window, including the IME) - this service still never calls a node-content API
 * (findAccessibilityNodeInfosByText, getRootInActiveWindow, etc.), so it never reads the actual
 * UI hierarchy or text of other apps, only pixels (takeScreenshot), where to tap, and whether a
 * keyboard window is present.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: TapAccessibilityService? = null
        private const val MAX_DIMENSION = 720
        private const val JPEG_QUALITY = 65

        val isEnabled: Boolean get() = instance != null

        /**
         * True if the on-screen keyboard is currently up, in ANY app - not just this one.
         * Checks the system's window list for one of type TYPE_INPUT_METHOD instead of the old
         * getWindowVisibleDisplayFrame() trick (see CharacterOverlay's git history), which only
         * ever reflected this app's own windows and got stuck once the character view it measured
         * from was hidden. False (never hides the character) if the service isn't enabled.
         */
        fun isKeyboardVisible(): Boolean {
            val service = instance ?: return false
            return service.windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
        }

        /** Taps at the given screen coordinates. No-op (returns false) if the service isn't enabled. */
        fun tapAt(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        /**
         * Returns a base64 JPEG of the current screen, or null if the service isn't enabled or
         * the device is below Android 11 (takeScreenshot needs API 30).
         */
        suspend fun captureScreenshotBase64(): String? {
            val service = instance ?: run {
                android.util.Log.w("StickmanAI", "captureScreenshotBase64: servicio no conectado")
                return null
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                android.util.Log.w("StickmanAI", "captureScreenshotBase64: SDK ${Build.VERSION.SDK_INT} < 30")
                return null
            }
            return suspendCancellableCoroutine { cont ->
                // takeScreenshot() itself (not just the async callback) can throw
                // SecurityException synchronously - "Services don't have the capability of
                // taking the screenshot" - on OS versions/OEM builds that don't grant it even
                // with the service enabled. That's a real crash (uncaught = kills the whole
                // app), not something onFailure() catches, so it needs its own try/catch here.
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        ContextCompat.getMainExecutor(service),
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                val encoded = try {
                                    encodeJpeg(result)
                                } catch (e: Exception) {
                                    android.util.Log.w("StickmanAI", "no se pudo procesar el screenshot", e)
                                    null
                                } finally {
                                    result.hardwareBuffer.close()
                                }
                                android.util.Log.i("StickmanAI", "captureScreenshotBase64: exito, bytes=${encoded?.length}")
                                cont.resume(encoded)
                            }

                            override fun onFailure(errorCode: Int) {
                                android.util.Log.w("StickmanAI", "captureScreenshotBase64: fallo, errorCode=$errorCode")
                                cont.resume(null)
                            }
                        },
                    )
                } catch (e: SecurityException) {
                    android.util.Log.w("StickmanAI", "captureScreenshotBase64: sin capacidad de screenshot en este dispositivo/OS", e)
                    cont.resume(null)
                }
            }
        }

        private fun encodeJpeg(result: ScreenshotResult): String {
            val hwBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                ?: throw IllegalStateException("wrapHardwareBuffer devolvio null")
            var bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture-only service - no interest in accessibility events themselves.
    }

    override fun onInterrupt() {}
}
