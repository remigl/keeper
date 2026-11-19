package io.github.remigl.keeper

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable

/**
 * Label + icon of the apps that posted notifications, resolved on demand (viewer only) and cached
 * for the life of the screen. Apps that Android does not let Keeper see (package visibility) or that
 * were uninstalled get their package name and a lettered circle instead.
 */
class AppIcons(context: Context) {
    class App(val label: String, val icon: Bitmap)

    private val pm: PackageManager = context.packageManager
    private val size = context.dp(36)
    private val cache = HashMap<String, App>()

    @Synchronized
    fun get(packageName: String): App = cache.getOrPut(packageName) { load(packageName) }

    @Suppress("DEPRECATION") // getApplicationInfo(String, Int): the flags overload needs API 33
    private fun load(packageName: String): App = try {
        val info = pm.getApplicationInfo(packageName, 0)
        App(info.loadLabel(pm).toString(), render(info.loadIcon(pm)))
    } catch (e: Exception) { // NameNotFoundException (not visible / uninstalled), broken icon resources…
        App(packageName, letter(packageName))
    }

    private fun render(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun letter(packageName: String): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = ((packageName.hashCode() % 360) + 360) % 360
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.65f)) }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
        val initial = packageName.substringAfterLast('.').firstOrNull()?.uppercaseChar() ?: '?'
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.45f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initial.toString(), size / 2f, y, text)
        return bitmap
    }
}
