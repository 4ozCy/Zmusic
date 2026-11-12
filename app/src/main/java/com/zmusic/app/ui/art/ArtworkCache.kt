package com.zmusic.app.ui.art

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory-bounded artwork cache plus dominant-colour sampling.
 * Thumbnails are requested at the size the UI needs (128 for rows, 512 for Now Playing), never full-size covers.
 */
object ArtworkCache {
    private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(48 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val misses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val seeds = ConcurrentHashMap<String, Int>()

    private fun base(albumId: Long, uri: String) = if (albumId > 0) "a$albumId" else "u$uri"
    private fun key(albumId: Long, uri: String, px: Int) = "${base(albumId, uri)}@$px"

    fun peek(albumId: Long, uri: String, px: Int): Bitmap? = cache.get(key(albumId, uri, px))

    suspend fun load(context: Context, uri: String, albumId: Long, px: Int): Bitmap? {
        if (uri.isEmpty()) return null
        val k = key(albumId, uri, px)
        cache.get(k)?.let { return it }
        val missKey = "m$uri@$px"
        if (missKey in misses) return null
        return withContext(Dispatchers.IO) {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(Uri.parse(uri), Size(px, px), null)
                } else {
                    legacy(context, albumId, px)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(k, bmp) else misses.add(missKey)
            bmp
        }
    }

    /** Muted-able dominant colour of the cover, or null when the song has no artwork. */
    suspend fun seed(context: Context, uri: String, albumId: Long): Color? {
        val k = base(albumId, uri)
        seeds[k]?.let { return Color(it) }
        val bmp = load(context, uri, albumId, 256) ?: return null
        val argb = withContext(Dispatchers.Default) { dominant(bmp) }
        seeds[k] = argb
        return Color(argb)
    }

    /** Average of a tiny downscale, weighted toward colourful, bright pixels so greys don't win. */
    private fun dominant(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        val px = IntArray(24 * 24)
        s.getPixels(px, 0, 24, 0, 0, 24, 24)
        val hsv = FloatArray(3)
        var r = 0.0
        var g = 0.0
        var bl = 0.0
        var wSum = 0.0
        for (p in px) {
            android.graphics.Color.colorToHSV(p, hsv)
            val w = 0.15 + hsv[1] * hsv[2]
            r += android.graphics.Color.red(p) * w
            g += android.graphics.Color.green(p) * w
            bl += android.graphics.Color.blue(p) * w
            wSum += w
        }
        return android.graphics.Color.rgb((r / wSum).toInt(), (g / wSum).toInt(), (bl / wSum).toInt())
    }

    private fun legacy(context: Context, albumId: Long, px: Int): Bitmap? {
        if (albumId <= 0) return null
        val u = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
        return context.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (px >= 512) 1 else 2 })
        }
    }
}
