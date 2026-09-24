package com.v2ray.ang.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Иконки приложений — по одной, по мере надобности, и никогда все сразу.**
 *
 * `AppManagerUtil.loadNetworkAppList` used to call `ApplicationInfo.loadIcon()` for EVERY installed
 * app while building the list, and put the resulting [Drawable] in the `AppInfo` record. That is
 * the донор's shape and it is a memory profile, not a list: the icons were held for as long as
 * «Прокси по приложениям» or the routing app picker stayed open, whether or not a single one of
 * them was on screen. A launcher icon at xxhdpi is a 324×324 ARGB bitmap — 420 KB — and an adaptive
 * icon is two of those layers; a phone with 200 visible apps was therefore holding a hundred-odd
 * megabytes of bitmaps to draw the eight rows in the viewport.
 *
 * The list carries package names now, and the row asks for its icon when it binds. This cache is
 * what keeps that from being slow: the icons the user is actually scrolling through stay decoded,
 * bounded by BYTES rather than by count, and everything past the bound is dropped and re-read from
 * the PackageManager if it comes back.
 *
 * It caches [Drawable.ConstantState], not the drawable. A `Drawable` carries mutable per-view state
 * (bounds, alpha, and a single `callback`), so handing the same instance to two `ImageView`s lets
 * them fight over it; `newDrawable()` gives each row its own wrapper around the SAME bitmap, which
 * costs one small object and no pixels.
 */
object AppIconLoader {

    /**
     * The byte ceiling: a thirty-second of the heap, floored at 2 MB and capped at 12 MB.
     *
     * Proportional because the screens this feeds are lists whose length is the user's app drawer,
     * and a 96 MB heap and a 512 MB one should not hold the same number of icons. The cap is there
     * because past a few megabytes the cache stops earning anything — the viewport is eight rows.
     */
    private val maxBytes: Int =
        (Runtime.getRuntime().maxMemory() / 32).coerceIn(2L * 1024 * 1024, 12L * 1024 * 1024).toInt()

    private class Entry(val state: Drawable.ConstantState, val bytes: Int)

    private val cache = object : LruCache<String, Entry>(maxBytes) {
        override fun sizeOf(key: String, value: Entry): Int = value.bytes
    }

    /**
     * The icon for [packageName] if it is already decoded, without touching the PackageManager.
     *
     * This is what makes a fast scroll look right: a row returning to the viewport gets its icon in
     * the same frame it binds, instead of flashing the placeholder for one hop through a
     * dispatcher.
     */
    fun cached(context: Context, packageName: String): Drawable? =
        cache.get(packageName)?.state?.newDrawable(context.resources)

    /**
     * The icon for [packageName], decoding it off the main thread when it is not cached yet.
     *
     * `AppConfig.UNIDENTIFIED_PACKAGE` is the picker's «неопознанные приложения» pseudo-entry — not
     * an installed app, so it answers with the platform's help glyph rather than a lookup failure.
     * A package that has been uninstalled since the list was built returns null and the row simply
     * keeps the placeholder it already has.
     */
    suspend fun load(context: Context, packageName: String): Drawable? {
        cached(context, packageName)?.let { return it }

        val appContext = context.applicationContext
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                if (packageName == AppConfig.UNIDENTIFIED_PACKAGE) {
                    ContextCompat.getDrawable(appContext, android.R.drawable.ic_menu_help)
                } else {
                    val pm = appContext.packageManager
                    pm.getApplicationInfo(packageName, 0).loadIcon(pm)
                }
            }.getOrNull()
        } ?: return null

        decoded.constantState?.let { cache.put(packageName, Entry(it, bytesOf(decoded))) }
        return decoded
    }

    /**
     * A drawable's cost, from the pixels it will occupy at 4 bytes each.
     *
     * Intrinsic size is the honest number for the bitmap-backed icons that dominate this list; a
     * drawable that declares none (a plain colour, some vectors) is charged a nominal 4 KB so it
     * still counts against the bound instead of being free and unevictable.
     */
    private fun bytesOf(drawable: Drawable): Int {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return 4 * 1024
        return w * h * 4
    }
}
