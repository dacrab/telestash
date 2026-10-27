package com.telestash.ui.gallery

import androidx.compose.runtime.Immutable
import com.telestash.model.StashItem

/** Rows of the paged timeline: month section labels plus photo cells. */
@Immutable
sealed interface TimelineRow {
    data class Month(
        val label: String,
    ) : TimelineRow

    data class Photo(
        val item: StashItem,
    ) : TimelineRow
}
