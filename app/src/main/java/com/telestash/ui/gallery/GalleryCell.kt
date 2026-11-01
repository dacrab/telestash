package com.telestash.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telestash.model.StashItem
import com.telestash.util.Format

/** How far a selected tile shrinks, and how round it becomes. */
private const val SELECTED_SCALE = 0.92f
private val SelectedCorner = 20.dp
private val RestingCorner = 8.dp

/**
 * One square timeline cell: rounded preview, video badge, device-backup mark,
 * and an expressive shape/scale change while selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryCell(
    item: StashItem,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    // Built once per cell state instead of on every recomposition (date formatting allocates).
    val description =
        remember(item, selected) {
            buildString {
                append(if (item.isVideo) "Video" else "Photo")
                append(", ")
                append(Format.date(item.date))
                append(", ")
                append(item.fileName)
                if (selected) append(", selected")
            }
        }
    val shape =
        RoundedCornerShape(
            animateDpAsState(
                if (selected) SelectedCorner else RestingCorner,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "cellCorner",
            ).value,
        )
    val scale by
        animateFloatAsState(
            if (selected) SELECTED_SCALE else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "cellScale",
        )
    val tint by
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "cellTint",
        )
    val borderColor by
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "cellBorder",
        )

    Box(
        Modifier
            .fillMaxSize()
            .aspectRatio(1f)
            .padding(2.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = if (selectionMode) Role.Checkbox else null,
            ).background(tint)
            .border(2.dp, borderColor, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                customActions =
                    if (selectionMode) {
                        listOf(
                            CustomAccessibilityAction(if (selected) "Deselect" else "Select") {
                                onClick()
                                true
                            },
                        )
                    } else {
                        emptyList()
                    }
            },
    ) {
        StashImage(
            item,
            thumbOnly = true,
            allowRetry = false,
            contentDescription = null,
        )
        if (item.isVideo) {
            Badge(
                icon = Icons.Filled.PlayArrow,
                description = null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
        if (item.deviceUri != null) {
            Badge(
                icon = Icons.Filled.CloudDone,
                description = null,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + scaleIn(initialScale = 0.5f),
            exit = fadeOut() + scaleOut(targetScale = 0.5f),
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Small translucent circular glyph used for video and backup markers. */
@Composable
private fun Badge(
    icon: ImageVector,
    description: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier.size(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(16.dp))
        }
    }
}
