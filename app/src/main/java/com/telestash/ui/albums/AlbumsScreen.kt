@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.telestash.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telestash.data.AlbumEntity
import com.telestash.ui.components.AppLoadingIndicator
import com.telestash.ui.components.ShapeBackdrop
import com.telestash.ui.itemCount

/** The Albums tab: tonal cards per topic with live photo counts, or a hint. */
@Composable
fun AlbumsScreen(
    albums: List<AlbumEntity>,
    counts: Map<Long, Int>,
    countsLoaded: Boolean,
    isLoading: Boolean,
    padding: PaddingValues,
    topBarScroll: TopAppBarScrollBehavior,
    onOpen: (Long) -> Unit,
    onNewAlbum: () -> Unit,
) {
    if (albums.isEmpty()) {
        if (isLoading) {
            LoadingAlbums(padding)
        } else {
            EmptyAlbums(padding, onNewAlbum)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(topBarScroll.nestedScrollConnection),
    ) {
        items(albums, key = { it.topicId }) { album ->
            AlbumCard(
                album = album,
                count = if (countsLoaded) counts[album.topicId] ?: 0 else null,
                onClick = { onOpen(album.topicId) },
            )
        }
    }
}

private class AlbumPalette(
    val container: Color,
    val content: Color,
)

@Composable
private fun albumPalette(topicId: Long): AlbumPalette =
    when (((topicId % PALETTE_SIZE) + PALETTE_SIZE) % PALETTE_SIZE) {
        0L ->
            AlbumPalette(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        1L ->
            AlbumPalette(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        else ->
            AlbumPalette(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
    }

@Composable
private fun AlbumCard(
    album: AlbumEntity,
    count: Int?,
    onClick: () -> Unit,
) {
    val palette = albumPalette(album.topicId)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = palette.container,
        contentColor = palette.content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBackdrop(
                    modifier = Modifier.size(52.dp),
                    color = palette.content.copy(alpha = 0.14f),
                    shape = MaterialShapes.Cookie4Sided,
                ) {
                    Icon(
                        Icons.Filled.PhotoAlbum,
                        contentDescription = null,
                        tint = palette.content,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = palette.content.copy(alpha = 0.12f),
                    contentColor = palette.content,
                ) {
                    Text(
                        count?.let(::itemCount) ?: "…",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                album.name,
                style = MaterialTheme.typography.titleLargeEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Album",
                style = MaterialTheme.typography.bodySmall,
                color = palette.content.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun LoadingAlbums(padding: PaddingValues) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppLoadingIndicator(size = 48.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Loading albums…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyAlbums(
    padding: PaddingValues,
    onNewAlbum: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShapeBackdrop(
            modifier = Modifier.size(104.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialShapes.Clover4Leaf,
        ) {
            Icon(
                Icons.Filled.PhotoAlbum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "No albums yet",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Group related photos and videos together.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onNewAlbum) { Text("Create album") }
    }
}

private const val PALETTE_SIZE = 3L
