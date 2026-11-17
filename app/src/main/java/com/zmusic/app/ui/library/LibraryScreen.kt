package com.zmusic.app.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.components.RescanButton
import com.zmusic.app.ui.components.ScreenHeader
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.components.frostedGlassCard
import com.zmusic.app.ui.components.topFade
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.TimeStyle
import kotlinx.coroutines.launch

/** One letter of the index: where its header sits in the list, and its songs with their index in the full library. */
@Immutable
private data class LetterGroup(
    val letter: String,
    val headerIndex: Int,
    val rows: List<IndexedValue<TrackEntity>>,
)

/**
 * Library. A clean alphabetical index: a large letter with a glass count badge opens each group,
 * rows are bare type, and a slim glass rail on the right edge jumps between letters (tap or drag).
 * A long press on a song opens its options.
 *
 * @param currentId MediaStore id of the song that is playing, or null.
 * @param contentPadding status bar at the top, floating dock and mini player at the bottom.
 */
@Composable
fun LibraryScreen(
    tracks: List<TrackEntity>?,
    currentId: Long?,
    player: PlayerController,
    scanning: Boolean,
    scanError: String?,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onTrackOptions: (TrackEntity) -> Unit,
) {
    // Tracks arrive sorted by title, so letter groups are contiguous. Each song keeps its index in
    // the full list so tapping plays from the right place. List item 0 is the screen header.
    val groups = remember(tracks) {
        var next = 1
        // One string per letter rather than one per song.
        val names = HashMap<Char, String>()
        tracks.orEmpty().withIndex()
            .groupBy { iv ->
                val letter = iv.value.title.firstOrNull { it.isLetter() }?.uppercaseChar()
                if (letter == null) "#" else names.getOrPut(letter) { letter.toString() }
            }
            .map { (letter, rows) ->
                LetterGroup(letter, next, rows).also { next += rows.size + 1 }
            }
    }
    val listState = rememberLazyListState()
    val showRail = groups.size >= 3

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().topFade(contentPadding.calculateTopPadding()),
            contentPadding = contentPadding,
        ) {
            item(key = "header") {
                Column {
                    ScreenHeader(
                        eyebrow = when {
                            tracks == null -> "Loading"
                            tracks.size == 1 -> "1 song"
                            else -> "${tracks.size} songs"
                        },
                        title = "Library",
                    ) { RescanButton(scanning, onRefresh) }

                    if (scanError != null) {
                        Text(
                            text = "Scan problem: $scanError",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            when {
                tracks == null -> item(key = "loading") { LoadingIndicator(Modifier.fillMaxWidth().padding(48.dp)) }

                tracks.isEmpty() -> item(key = "empty") { EmptyLibrary(scanning, onRefresh) }

                else -> groups.forEach { group ->
                    item(key = "h-${group.letter}", contentType = "letter") {
                        LetterHeader(group.letter, group.rows.size, railInset = showRail)
                    }
                    items(group.rows, key = { it.value.mediaStoreId }, contentType = { "track" }) { iv ->
                        TrackRow(
                            track = iv.value,
                            isCurrent = iv.value.mediaStoreId == currentId,
                            modifier = if (showRail) Modifier.padding(end = 22.dp) else Modifier,
                            onLongClick = { onTrackOptions(iv.value) },
                        ) {
                            player.setShuffle(false)
                            player.playQueue(tracks, iv.index)
                        }
                    }
                }
            }
        }

        if (showRail) {
            IndexRail(
                groups = groups,
                listState = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        top = contentPadding.calculateTopPadding() + 120.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp,
                        end = 6.dp,
                    ),
            )
        }
    }
}

/** Big letter, a glass badge with the number of songs under it, and a hairline running out to the edge. */
@Composable
private fun LetterHeader(letter: String, count: Int, railInset: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = if (railInset) 46.dp else 24.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(letter, style = MaterialTheme.typography.headlineMedium, color = AppTheme.extended.text)
        Text(
            text = count.toString(),
            style = TimeStyle,
            color = AppTheme.extended.textSoft,
            modifier = Modifier
                .frostedGlassCard(CircleShape, surfaceAlpha = 0.09f)
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        HairlineDivider(Modifier.weight(1f))
    }
}

/**
 * Letter rail. One touch anywhere on it jumps the list; dragging scrubs through the letters with
 * a haptic tick on each change. The letter of the group at the top of the list is lit in amber.
 * It is a touch shortcut only: screen readers already reach every row by scrolling, so it is
 * hidden from accessibility rather than adding twenty unlabeled stops.
 */
@Composable
private fun IndexRail(groups: List<LetterGroup>, listState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(groups)
    // Held as a State and read inside each letter rather than here: a flick through the library
    // changes this many times a second, and reading it in this scope would recompose every letter
    // in the rail each time instead of only the two whose colour actually changes.
    val active = remember {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            current.indexOfLast { it.headerIndex <= first }.coerceAtLeast(0)
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val railHeight = min(maxHeight, 22.dp * groups.size + 16.dp)
        Column(
            modifier = Modifier
                .width(28.dp)
                .height(railHeight)
                .frostedGlassCard(CircleShape, surfaceAlpha = 0.09f)
                .padding(vertical = 8.dp)
                .clearAndSetSemantics { }
                .pointerInput(Unit) {
                    var last = -1
                    fun jump(y: Float) {
                        val list = current
                        if (list.isEmpty()) return
                        val i = (y / size.height * list.size).toInt().coerceIn(0, list.lastIndex)
                        if (i != last) {
                            last = i
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch { listState.scrollToItem(list[i].headerIndex) }
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        last = -1
                        jump(down.position.y)
                        drag(down.id) { change ->
                            change.consume()
                            jump(change.position.y)
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            groups.forEachIndexed { i, group ->
                RailLetter(group.letter, i, active, Modifier.weight(1f))
            }
        }
    }
}

/** One letter of the rail. Amber while its group is the one at the top of the list. */
@Composable
private fun RailLetter(letter: String, index: Int, active: State<Int>, modifier: Modifier) {
    val isActive by remember(index) { derivedStateOf { active.value == index } }
    val color by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else AppTheme.extended.textDim,
        animationSpec = tween(160),
        label = "railLetter",
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(letter, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
