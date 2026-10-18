package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The audit file is shared by everyone who goes out, so writes must not eat each other. */
class AuditLogTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun independentInstancesDoNotLoseEachOthersEntries() {
        // Each thread builds its own AuditLog, the way a fresh gateway would, all pointing at the
        // same file. Without one lock per file the read-append-overwrite cycle drops entries.
        val file = File(directory, "privacy/audit.json")
        val writers = 8
        val each = 25
        val done = CountDownLatch(writers)
        val pool = Executors.newFixedThreadPool(writers)
        repeat(writers) { writer ->
            pool.submit {
                try {
                    val log = AuditLog(file)
                    repeat(each) { index -> log.record(entry("$writer-$index")) }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "写审计的线程没有跑完")
        pool.shutdown()

        val entries = AuditLog(file).read()
        assertEquals(writers * each, entries.size, "有记录被覆盖了")
        assertEquals(writers * each, entries.map { it.sentBody }.toSet().size, "有记录重复了")
        assertTrue(entries.all { it.sentBody.isNotEmpty() }, "有记录的正文是空的")
    }

    private fun entry(mark: String) = AuditEntry(
        at = "2026-10-18T00:00:00Z",
        host = "127.0.0.1",
        purpose = GatewayPurpose.HearthChat,
        sentCharacters = mark.length,
        sentBody = mark,
        status = 200,
        succeeded = true,
        promptTokens = null,
        completionTokens = null,
    )
}
