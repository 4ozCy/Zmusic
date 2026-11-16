package com.zmusic.app.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.data.TrackEntity
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.ScreenHeader
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.components.frostedGlassCard
import com.zmusic.app.ui.components.pressClick
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.TimeStyle

/**
 * Search. The field is a glass capsule; results are the same bare rows as everywhere else.
 *
 * @param currentId MediaStore id of the song that is playing, or null.
 * @param contentPadding status bar at the top, floating dock and mini player at the bottom.
 */
@Composable
fun SearchScreen(
    vm: LibraryViewModel,
    currentId: Long?,
    player: PlayerController,
    contentPadding: PaddingValues,
    onTrackOptions: (TrackEntity) -> Unit,
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val artists by vm.topArtists.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val mode = when {
        query.isBlank() -> if (artists.isEmpty()) Mode.Prompt else Mode.Suggestions
        results.isEmpty() -> Mode.NoMatches
        else -> Mode.Results
    }
    val listPadding = PaddingValues(top = 12.dp, bottom = contentPadding.calculateBottomPadding())

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        ScreenHeader("Find anything", "Search")
        GlassSearchField(
            query = query,
            onQueryChange = vm::setQuery,
            onSearch = { keyboard?.hide() },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        // The area under the field fades between its states instead of snapping.
        Crossfade(targetState = mode, animationSpec = tween(180), modifier = Modifier.fillMaxSize(), label = "search") { m ->
            when (m) {
                Mode.Prompt -> Hint(Icons.Rounded.Search, "Type to search your library.")

                Mode.NoMatches -> Hint(Icons.Rounded.SearchOff, "No matches for \"${query.trim()}\".")

                // A ranked list rather than a strip of chips: it fills the idle screen and scans top to bottom.
                Mode.Suggestions -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 28.dp, bottom = contentPadding.calculateBottomPadding()),
                ) {
                    item { Eyebrow("Your top artists", modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)) }
                    itemsIndexed(artists, key = { _, name -> name }) { i, name ->
                        ArtistRow(i + 1, name) { vm.setQuery(name) }
                    }
                }

                Mode.Results -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                    itemsIndexed(results, key = { _, t -> t.mediaStoreId }) { index, track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.mediaStoreId == currentId,
                            onLongClick = { onTrackOptions(track) },
                        ) {
                            keyboard?.hide()
                            player.setShuffle(false)
                            player.playQueue(results, index)
                        }
                    }
                }
            }
        }
    }
}

/** Glass capsule text field: search glyph, the query, and a clear button once there is something to clear. */
@Composable
private fun GlassSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppTheme.extended.text),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .frostedGlassCard(CircleShape, surfaceAlpha = 0.10f)
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "Songs, artists, albums",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppTheme.extended.textDim,
                            maxLines = 1,
                        )
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(44.dp).pressClick(onClickLabel = "Clear search") { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

private enum class Mode { Prompt, Suggestions, NoMatches, Results }

@Composable
private fun ArtistRow(rank: Int, name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Search for $name", onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = rank.toString().padStart(2, '0'),
            style = TimeStyle,
            color = AppTheme.extended.textDim,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            color = AppTheme.extended.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Idle and no-result states: a glass disc with a glyph, and one line under it. */
@Composable
private fun Hint(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(72.dp).frostedGlassCard(CircleShape, surfaceAlpha = 0.10f), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = AppTheme.extended.textSoft, modifier = Modifier.size(30.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.extended.textSoft,
            textAlign = TextAlign.Center,
        )
    }
}
