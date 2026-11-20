package com.glasslauncher.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order of stable and beta versions the update check relies on. */
class UpdaterVersionTest {
    @Test fun numbersFirst() {
        assertTrue(Updater.newer("3.7.4", "3.7.3"))
        assertTrue(Updater.newer("3.10.0", "3.9.9"))
        assertFalse(Updater.newer("3.7.3", "3.7.3"))
    }
    @Test fun betaBeforeItsRelease() {
        assertTrue(Updater.newer("3.8.0", "3.8.0-beta2"))
        assertFalse(Updater.newer("3.8.0-beta2", "3.8.0"))
        assertTrue(Updater.newer("3.8.0-beta1", "3.7.4"))
        assertTrue(Updater.newer("3.8.0-beta10", "3.8.0-beta2"))
        assertTrue(Updater.newer("3.8.1-beta1", "3.8.0"))
    }
    @Test fun fourPointZero() {
        // The beta after 3.8.0 is 4.0.0-beta1: newer than the last stable, older than its own release.
        assertTrue(Updater.newer("4.0.0-beta1", "3.8.0"))
        assertFalse(Updater.newer("3.8.0", "4.0.0-beta1"))
        assertTrue(Updater.newer("4.0.0", "4.0.0-beta1"))
        assertFalse(Updater.newer("4.0.0-beta1", "4.0.0"))
        assertTrue(Updater.newer("4.0.1-beta1", "4.0.0"))
        assertFalse(Updater.newer("4.0.0", "4.0.1-beta1"))
    }
}
