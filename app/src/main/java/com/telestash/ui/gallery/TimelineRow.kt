package com.telestash.ui.gallery

import com.telestash.model.StashItem

/** Rows of the paged timeline: month section labels plus photo cells. */
sealed interface TimelineRow {
    data class Month(
        val label: String,
    ) : TimelineRow

    data class Photo(
        val item: StashItem,
    ) : TimelineRow
}
