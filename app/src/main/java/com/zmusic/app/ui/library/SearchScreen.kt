package com.zmusic.app.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.ScreenHeader
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.TimeStyle

@Composable
fun SearchScreen(
    vm: LibraryViewModel,
    playerState: PlayerUiState,
    player: PlayerController,
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

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Find anything", "Search")
        // No box: just a field with a hairline underline that turns to the accent on focus.
        TextField(
            value = query,
            onValueChange = vm::setQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text("Songs, artists, albums", color = AppTheme.extended.faint) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )

        // The area under the field fades between its states instead of snapping.
        Crossfade(targetState = mode, animationSpec = tween(180), modifier = Modifier.fillMaxSize(), label = "search") { m ->
            when (m) {
                Mode.Prompt -> Hint("Type to search your library.")

                Mode.NoMatches -> Hint("No matches for \"${query.trim()}\".")

                // A ranked list rather than a strip of chips: it fills the idle screen and scans top to bottom.
                Mode.Suggestions -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp)) {
                    item { Eyebrow("Your top artists", modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)) }
                    itemsIndexed(artists, key = { _, name -> name }) { i, name ->
                        ArtistRow(i + 1, name) { vm.setQuery(name) }
                    }
                }

                Mode.Results -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                    itemsIndexed(results, key = { _, t -> t.mediaStoreId }) { index, track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.mediaStoreId.toString() == playerState.mediaId,
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
            color = AppTheme.extended.faint,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}
