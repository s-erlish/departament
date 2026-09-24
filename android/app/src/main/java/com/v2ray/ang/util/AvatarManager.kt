package com.v2ray.ang.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.LifecycleCoroutineScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.auth.dto.UserProfileDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Account avatar handling, dependency-free (no Glide/Coil).
 *
 * Resolution order applied by [applyAvatar]:
 *   1. a user-picked custom photo stored locally ([saveCustomAvatar]);
 *   2. the Telegram profile photo URL carried on [UserProfileDto.avatarUrl], if the
 *      backend supplies one (fetched with OkHttp and disk-cached);
 *   3. a monogram fallback (first letter of the display name).
 *
 * Bitmaps are down-sampled to at most [MAX_DIM] px and clipped to a circle with
 * [RoundedBitmapDrawableFactory], so a plain [ImageView] renders a round avatar.
 */
object AvatarManager {

    private const val TAG = AppConfig.TAG
    private const val MAX_DIM = 512
    private const val CUSTOM_FILE = "user_avatar.jpg"
    private const val CACHE_DIR = "avatar_cache"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * In-memory cache so re-binding the same avatar does not re-decode from disk/network.
     *
     * **БЫЛО `HashMap`, БЕЗ ГРАНИЦЫ И БЕЗ ЗАМКА.** Two things were wrong with that.
     *
     * It never shrank. The custom photo is keyed by the file's `lastModified`, so EVERY time the
     * user picks a new avatar the map gains an entry and keeps the old one: a 512×512 ARGB bitmap,
     * a megabyte, held for the life of the process, and only `clearCustomAvatar` ever removed any
     * of them. Change the photo ten times in a session and ten megabytes stay behind it.
     *
     * And it was written from two threads: [applyAvatar] reads and fills it on the main thread,
     * while [saveCustomAvatar] runs on `Dispatchers.IO` (`AccountFragment` calls it there) and
     * wrote into the same map.
     *
     * `LruCache` fixes both — it is synchronized, and it is bounded BY BYTES rather than by entry
     * count, because one entry here is a bitmap and «сколько-то штук» says nothing about how much
     * memory that is.
     */
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 64).coerceIn(1L * 1024 * 1024, 6L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // region custom (user-picked) avatar

    private fun customFile(context: Context): File = File(context.filesDir, CUSTOM_FILE)

    fun hasCustomAvatar(context: Context): Boolean = customFile(context).exists()

    /** Copies + down-samples the picked image into app storage. Returns true on success. */
    fun saveCustomAvatar(context: Context, uri: Uri): Boolean {
        return try {
            val bmp = decodeSampled(context, uri) ?: return false
            val out = customFile(context)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            memory.put("custom:${out.lastModified()}", bmp)
            true
        } catch (e: Exception) {
            Log.w(TAG, "saveCustomAvatar failed", e)
            false
        }
    }

    fun clearCustomAvatar(context: Context) {
        try {
            customFile(context).delete()
        } catch (e: Exception) {
            Log.w(TAG, "clearCustomAvatar failed", e)
        }
        memory.snapshot().keys.filter { it.startsWith("custom:") }.forEach { memory.remove(it) }
    }

    // endregion

    /**
     * Binds the best available avatar into [image], keeping [monogram] as the visible fallback.
     * Safe to call from render callbacks; remote loads happen off the main thread on [scope].
     */
    fun applyAvatar(
        scope: LifecycleCoroutineScope,
        context: Context,
        image: ImageView,
        monogram: TextView,
        profile: UserProfileDto?,
    ) {
        // 1. custom photo
        val custom = customFile(context)
        if (custom.exists()) {
            val key = "custom:${custom.lastModified()}"
            val cached = memory.get(key)
            if (cached != null) {
                showBitmap(image, monogram, cached)
            } else {
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { decodeSampledFile(custom.absolutePath) }.getOrNull()
                    }
                    if (bmp != null) {
                        memory.put(key, bmp)
                        showBitmap(image, monogram, bmp)
                    } else {
                        showMonogram(image, monogram)
                    }
                }
            }
            return
        }

        // 2. Telegram photo URL from the backend
        val url = profile?.avatarUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            val cached = memory.get(url)
            if (cached != null) {
                showBitmap(image, monogram, cached)
                return
            }
            showMonogram(image, monogram) // fallback until the fetch resolves
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { fetchRemote(context, url) }
                if (bmp != null) {
                    memory.put(url, bmp)
                    // Only apply if no custom photo was added while the fetch was in flight.
                    if (!custom.exists()) showBitmap(image, monogram, bmp)
                }
            }
            return
        }

        // 3. monogram
        showMonogram(image, monogram)
    }

    /** Sets the display monogram letter from a name (used with [applyAvatar]). */
    fun setMonogram(monogram: TextView, name: String?) {
        val letter = name?.trim()?.trimStart('@')?.firstOrNull()?.uppercaseChar()
        monogram.text = letter?.toString() ?: "?"
    }

    // region internals

    private fun showBitmap(image: ImageView, monogram: TextView, bmp: Bitmap) {
        val square = centerSquare(bmp)
        val rounded = RoundedBitmapDrawableFactory.create(image.resources, square).apply {
            isCircular = true
        }
        image.setImageDrawable(rounded)
        image.visibility = View.VISIBLE
        monogram.visibility = View.INVISIBLE
    }

    /** Crops to a centered square so [RoundedBitmapDrawableFactory] renders a clean circle. */
    private fun centerSquare(bmp: Bitmap): Bitmap {
        val size = minOf(bmp.width, bmp.height)
        if (bmp.width == bmp.height) return bmp
        val x = (bmp.width - size) / 2
        val y = (bmp.height - size) / 2
        return try {
            Bitmap.createBitmap(bmp, x, y, size, size)
        } catch (e: Exception) {
            bmp
        }
    }

    private fun showMonogram(image: ImageView, monogram: TextView) {
        image.setImageDrawable(null)
        image.visibility = View.GONE
        monogram.visibility = View.VISIBLE
    }

    private fun fetchRemote(context: Context, url: String): Bitmap? {
        return try {
            val cacheFile = File(cacheDir(context), md5(url) + ".jpg")
            if (cacheFile.exists()) {
                decodeSampledFile(cacheFile.absolutePath)?.let { return it }
            }
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body.bytes()
                val bmp = decodeSampledBytes(bytes) ?: return null
                runCatching {
                    cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }
                bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "avatar fetch failed", e)
            null
        }
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeSampledFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeSampledBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleSizeFor(w: Int, h: Int): Int {
        var sample = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW >= MAX_DIM && halfH >= MAX_DIM) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // endregion
}
