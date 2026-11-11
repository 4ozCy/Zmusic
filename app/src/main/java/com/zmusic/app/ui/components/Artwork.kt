package com.zmusic.app.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Memory-bounded artwork cache. Loads 512px thumbnails only, never full-size covers. */
object ArtworkCache {
    private const val SIZE = 512
    private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(48 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val misses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun key(albumId: Long, uri: String) = if (albumId > 0) "a$albumId" else "u$uri"

    fun peek(albumId: Long, uri: String): Bitmap? = cache.get(key(albumId, uri))

    suspend fun load(context: Context, uri: String, albumId: Long): Bitmap? {
        val k = key(albumId, uri)
        cache.get(k)?.let { return it }
        val missKey = "m$uri"
        if (missKey in misses) return null
        return withContext(Dispatchers.IO) {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(Uri.parse(uri), Size(SIZE, SIZE), null)
                } else {
                    legacy(context, albumId)
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

    private fun legacy(context: Context, albumId: Long): Bitmap? {
        if (albumId <= 0) return null
        val u = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
        return context.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    }
}

@Composable
fun Artwork(
    albumId: Long,
    uri: String,
    seed: String,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(ArtworkCache.peek(albumId, uri), albumId, uri) {
        value = ArtworkCache.peek(albumId, uri) ?: ArtworkCache.load(context, uri, albumId)
    }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val b = bitmap
        if (b != null) {
            val image = remember(b) { b.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FallbackArt(seed)
        }
    }
}

/** Consistent generated cover: same album name always gets the same colors. */
@Composable
private fun FallbackArt(seed: String) {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color.hsv(hue, 0.35f, 0.34f), Color.hsv((hue + 40f) % 360f, 0.45f, 0.16f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxSize(0.4f),
        )
    }
}
