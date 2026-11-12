package com.zmusic.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.art.ArtworkCache

/** Cover at the size the UI needs. Neutral placeholder (no colour, no icon fuss) when a song has no art. */
@Composable
fun Artwork(
    albumId: Long,
    uri: String,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    px: Int = 256,
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(ArtworkCache.peek(albumId, uri, px), albumId, uri, px) {
        value = ArtworkCache.peek(albumId, uri, px) ?: ArtworkCache.load(context, uri, albumId, px)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
    ) {
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
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f),
            )
        }
    }
}

/**
 * Now Playing cover: sharp 12dp corners, a 1dp top-edge specular rim (white at 14%), and a soft,
 * grounded ambient shadow. Static. No spinning, no tilt.
 */
@Composable
fun ArtworkFrame(albumId: Long, uri: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.6f),
            )
            .clip(shape)
            .drawWithCache {
                val radius = CornerRadius(12.dp.toPx())
                val rim = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.35f,
                )
                val stroke = Stroke(width = 1.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    drawRoundRect(brush = rim, cornerRadius = radius, style = stroke)
                }
            },
    ) {
        Artwork(albumId, uri, Modifier.fillMaxSize(), corner = 0.dp, px = 512)
    }
}
