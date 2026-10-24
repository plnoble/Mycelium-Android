package com.xavierxia.mycelium.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseJsonTest {
    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        val release = testRelease("0.6.0")

        val decoded = ReleaseJson.decode(ReleaseJson.encode(release))

        assertEquals(release, decoded)
    }

    @Test
    fun nullAttachmentsSurviveTheRoundTrip() {
        val release = testRelease("0.6.0", installable = false)

        val decoded = ReleaseJson.decode(ReleaseJson.encode(release))

        assertEquals(release, decoded)
        assertNull(decoded?.apkUrl)
        assertNull(decoded?.sha256Url)
        assertNull(decoded?.apkSizeBytes)
    }

    @Test
    fun garbageDecodesToNothingInsteadOfThrowing() {
        assertNull(ReleaseJson.decode("not json at all"))
        assertNull(ReleaseJson.decode("{\"versionName\":\"0.6.0\"}"))
    }
}