package com.zmusic.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.playback.PlayerController
import com.zmusic.app.playback.PlayerUiState
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.TrackRow
import com.zmusic.app.ui.components.pressClick

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
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp)) {
            Eyebrow("Find anything", color = MaterialTheme.colorScheme.primary)
            Text("Search", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 4.dp))
        }
        // No box: just a field with a hairline underline that turns to the accent on focus.
        TextField(
            value = query,
            onValueChange = vm::setQuery,
            singleLine = true,
            placeholder = { Text("Songs, artists, albums") },
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
                unfocusedIndicatorColor = hairline,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )

        when {
            query.isBlank() -> {
                if (artists.isNotEmpty()) {
                    Eyebrow("Your top artists", modifier = Modifier.padding(start = 24.dp, top = 32.dp, bottom = 4.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        items(artists, key = { it }) { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
                                modifier = Modifier.pressClick { vm.setQuery(name) }.padding(vertical = 14.dp),
                            )
                        }
                    }
                } else {
                    Hint("Type to search your library.")
                }
            }

            results.isEmpty() -> Hint("No matches for \"${query.trim()}\".")

            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp)) {
                itemsIndexed(results, key = { _, t -> t.mediaStoreId }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.mediaStoreId.toString() == playerState.mediaId,
                        isPlaying = playerState.isPlaying,
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

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        modifier = Modifier.padding(24.dp),
    )
}
