package com.nivra.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun newerPatchIsDetected() {
        assertTrue(VersionComparator.isNewer("0.0.2", "0.0.1"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(VersionComparator.isNewer("0.0.1", "0.0.1"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(VersionComparator.isNewer("0.0.9", "0.1.0"))
    }

    @Test
    fun versionPrefixIsHandled() {
        assertTrue(VersionComparator.isNewer("1.0.0", "0.9.9"))
    }
}
