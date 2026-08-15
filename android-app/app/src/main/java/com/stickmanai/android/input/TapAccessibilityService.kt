package com.stickmanai.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Lets a character actually tap the screen (other apps included), the mobile equivalent of the
 * desktop's click/move_mouse actions - dispatching gestures is the only way to do that on
 * Android without root. The user has to enable this manually under
 * Settings > Accessibility (see MainActivity's grant button); until then `tapAt` below is
 * simply a no-op, same fallback style as CameraCapture without camera permission.
 *
 * canRetrieveWindowContent is intentionally left off in tap_accessibility_service.xml - this
 * service only ever dispatches gestures, it never reads what's on screen.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: TapAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        /** Taps at the given screen coordinates. No-op (returns false) if the service isn't enabled. */
        fun tapAt(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
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
