package com.telestash.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telestash.model.StashItem
import com.telestash.util.Format

/** One square timeline cell: preview, video badge, and the device-backup mark. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryCell(
    item: StashItem,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val description =
        buildString {
            append(if (item.isVideo) "Video" else "Photo")
            append(", ")
            append(Format.date(item.date))
            append(", ")
            append(item.fileName)
            if (selected) append(", selected")
        }
    Box(
        Modifier
            .fillMaxSize()
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = if (selectionMode) Role.Checkbox else null,
            ).then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            ).semantics(mergeDescendants = true) {
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
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(2.dp)
                        .size(18.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(22.dp),
            )
        }
        if (item.deviceUri != null) {
            Icon(
                Icons.Filled.CloudDone,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(2.dp)
                        .size(14.dp),
            )
        }
    }
}
