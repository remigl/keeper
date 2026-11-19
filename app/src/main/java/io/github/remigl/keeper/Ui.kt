package io.github.remigl.keeper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Log content is shown on screen: keep it out of screenshots and the recents thumbnail. */
internal fun Activity.secureWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
}

/** Android 15 draws apps edge-to-edge: keep the content clear of the status and navigation bars. */
internal fun applySystemBarInsets(root: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

internal fun Activity.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
