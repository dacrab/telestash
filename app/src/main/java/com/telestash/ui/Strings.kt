package com.telestash.ui

/** "1 item" / "N items" — the app's only plural noun, so it lives in one place. */
fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"
