package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/**
 * The eat/drink prop window: a small (FOOD_RIG dp square) RigView floating near the character's
 * mouth showing the food/drink rig (currently pizza.json / cup.json). OverlayService drives the
 * timeline (slide-in from the kitchen, per-bite wedge shrink + jiggle, drink tilt, slide-away on
 * finish) by calling the primitives here - mirrors src/jsEngine/foodProp.js + character.js's food
 * mode on PC. Rebuilt per serve (detach/attach), matching the desktop's create + close per meal.
 */
class FoodPropOverlay(private val context: Context, private val windowManager: WindowManager) {

    companion object {
        // Rig-box for the food/drink window - matches PC's FOOD_RIG.
        const val FOOD_RIG_DP = 46
        const val BITE_MS = 700L
        const val EAT_BITES = 6
        const val EAT_DURATION_MS = 5000L // must match CharacterState.EAT_DURATION_MS
        const val DRINK_GULP_MS = 1200L
        const val DRINK_DURATION_MS = 4200L // must match CharacterState.DRINK_DURATION_MS
        const val SLIDE_STEPS = 6
        // Add future foods here - only existing rigs are served, cycling so consecutive meals
        // visibly switch foods. Mirrors PC's FOOD_CANDIDATES discovery list.
        val FOOD_CANDIDATES = listOf("pizza")
    }

    private val density = context.resources.displayMetrics.density
    val sizePx = (FOOD_RIG_DP * density).toInt()

    private var rigView: RigView? = null
    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Show a food rig (e.g. "pizza") in wedge-bite mode. Returns false if the rig asset is missing. */
    fun showFood(rigId: String): Boolean {
        val figure = RigFigure.forCharacterOrNull(context, rigId) ?: return false
        rigView = RigView(context, figure).apply { setFoodMode(EAT_BITES) }
        windowManager.addView(rigView!!, params)
        return true
    }

    /** Show the cup in drink mode (no wedge clip, just tilt) - returns false if cup.json is missing. */
    fun showDrink(): Boolean {
        val figure = RigFigure.forCharacterOrNull(context, "cup") ?: return false
        rigView = RigView(context, figure)
        windowManager.addView(rigView!!, params)
        return true
    }

    /** Move the window's top-left corner to a screen point (character-mouth anchor in OverlayService). */
    fun moveTo(x: Int, y: Int) {
        val view = rigView ?: return
        params.x = x
        params.y = y
        windowManager.updateViewLayout(view, params)
    }

    fun updateBites(bitesLeft: Int, jiggle: Float) {
        rigView?.setFoodBites(bitesLeft, jiggle)
    }

    fun updateDrinkTilt(deg: Float) {
        rigView?.setDrinkTilt(deg)
    }

    fun detach() {
        rigView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already gone */ }
        }
        rigView = null
    }
}