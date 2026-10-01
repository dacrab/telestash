package com.telestash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room resolves an upgrade by walking the registered migrations from the
 * device's schema version to the current one. A missing edge means every
 * affected install crashes on launch, and only at that moment — so the chain is
 * checked here instead.
 */
class MigrationChainTest {
    @Test
    fun everyShippedVersionJumpsStraightToTheCurrentSchema() {
        // v0.2.0 shipped schema 1, v0.3.0 shipped schema 2; both are still
        // installed in the wild. Intermediate schemas 3-7 only ever existed in
        // development builds and are deliberately not supported.
        val shipped = ALL_MIGRATIONS.map { it.startVersion }.toSet()
        assertEquals("a migration per released schema version", setOf(1, 2), shipped)
        assertTrue(ALL_MIGRATIONS.all { it.endVersion == CURRENT_VERSION })
    }

    @Test
    fun noTwoMigrationsCoverTheSameVersion() {
        val duplicates =
            ALL_MIGRATIONS
                .groupBy { it.startVersion }
                .filterValues { it.size > 1 }
                .keys
        assertTrue("duplicate migration for $duplicates", duplicates.isEmpty())
    }

    private companion object {
        /** Keep in sync with @Database(version = ...) in StashDb. */
        const val CURRENT_VERSION = 8
    }
}
