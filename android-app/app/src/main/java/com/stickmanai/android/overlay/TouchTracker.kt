package com.stickmanai.android.overlay

/**
 * Last place the user touched on the screen, in 0-100 percent (stable across devices unlike
 * raw pixels). Written on the main thread by every CharacterOverlay's handleTouch() - the
 * character windows are the touchable part of the overlay - and read once per AI tick round by
 * OverlayService to build the "where is the user's finger" attention signal (the Android analog
 * of PC's mousePosition; see attentionFocus in Prefs/GeminiClient).
 */
object TouchTracker {
    private const val UNKNOWN = -1

    @Volatile
    var xPercent: Int = UNKNOWN
        private set

    @Volatile
    var yPercent: Int = UNKNOWN
        private set

    fun record(rawX: Float, rawY: Float, screenWidthPx: Int, screenHeightPx: Int) {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return
        xPercent = ((rawX * 100) / screenWidthPx).toInt().coerceIn(0, 100)
        yPercent = ((rawY * 100) / screenHeightPx).toInt().coerceIn(0, 100)
    }

    /** True until the user has actually touched the screen at least once this session. */
    fun isKnown(): Boolean = xPercent >= 0
}