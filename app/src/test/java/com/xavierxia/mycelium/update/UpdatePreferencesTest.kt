package com.xavierxia.mycelium.update

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatePreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun defaultsAreAutoCheckOnAndNeverCheckedAndNothingIgnored() {
        val prefs = UpdatePreferences(context)

        assertTrue(prefs.autoCheck)
        assertEquals(0L, prefs.lastAutoAttemptAtMs)
        assertEquals(0L, prefs.lastSuccessAtMs)
        assertNull(prefs.ignoredTag)
        assertNull(prefs.pendingReleaseJson)
        assertNull(prefs.pendingReleaseTag)
        assertNull(prefs.pendingApkPath)
    }

    @Test
    fun valuesSurviveANewInstance() {
        val first = UpdatePreferences(context)
        first.autoCheck = false
        first.lastAutoAttemptAtMs = 111L
        first.lastSuccessAtMs = 222L
        first.ignoredTag = "v0.5.0"
        first.pendingReleaseJson = "{\"a\":1}"
        first.pendingReleaseTag = "v0.5.0"
        first.pendingApkPath = "/tmp/x.apk"

        val second = UpdatePreferences(context)

        assertFalse(second.autoCheck)
        assertEquals(111L, second.lastAutoAttemptAtMs)
        assertEquals(222L, second.lastSuccessAtMs)
        assertEquals("v0.5.0", second.ignoredTag)
        assertEquals("{\"a\":1}", second.pendingReleaseJson)
        assertEquals("v0.5.0", second.pendingReleaseTag)
        assertEquals("/tmp/x.apk", second.pendingApkPath)
    }

    @Test
    fun clearPendingKeepsTheIgnoredTag() {
        val prefs = UpdatePreferences(context)
        prefs.ignoredTag = "v0.5.0"
        prefs.pendingReleaseJson = "{}"
        prefs.pendingReleaseTag = "v0.5.0"
        prefs.pendingApkPath = "/tmp/x.apk"

        prefs.clearPending()

        assertNull(prefs.pendingReleaseJson)
        assertNull(prefs.pendingReleaseTag)
        assertNull(prefs.pendingApkPath)
        assertEquals("v0.5.0", prefs.ignoredTag)
    }
}