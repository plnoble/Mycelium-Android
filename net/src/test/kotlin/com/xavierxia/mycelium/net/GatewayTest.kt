package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI

class GatewayTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun rejectsHostsOutsideTheWhitelist() {
        val gateway = gateway("https://api.deepseek.com")
        val rejected = assertThrows(GatewayRejected::class.java) {
            gateway.send(GatewayRequest("https://example.com/v1", GatewayPurpose.RoundtableChat, body = "secret-key"))
        }
        assertTrue(rejected.message!!.contains("example.com"))
        assertTrue(AuditLog(File(directory, "privacy/audit.json")).read().isEmpty())
    }

    @Test
    fun chatHostCannotBeUsedForTheUpdateCheck() {
        val hosts = AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false)
        assertFalse(hosts.allows(URI("https://api.deepseek.com/chat/completions"), GatewayPurpose.Update))
        assertTrue(hosts.allows(URI("https://api.github.com/repos/x/releases/latest"), GatewayPurpose.Update))
        assertTrue(hosts.allows(URI("https://github.com/x/releases/download/a.apk"), GatewayPurpose.Update))
        assertTrue(hosts.allows(URI("https://objects.githubusercontent.com/a.apk"), GatewayPurpose.Update))
        assertFalse(hosts.allows(URI("https://example.com/a.apk"), GatewayPurpose.Update))
        assertFalse(hosts.allows(URI("http://api.github.com/repos/x"), GatewayPurpose.Update))
    }

    @Test
    fun recordsEveryChatCallWithoutTheKey() {
        LocalHttp { call ->
            assertEquals("POST", call.method)
            assertFalse(call.body.contains("sk-test"))
            HttpReply(200, """{"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":10,"completion_tokens":4}}""")
        }.use { server ->
            val gateway = gateway("http://127.0.0.1:${server.port}", cleartext = true)
            val response = gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:${server.port}/chat/completions",
                    purpose = GatewayPurpose.RoundtableChat,
                    method = "POST",
                    headers = mapOf("Authorization" to "Bearer sk-test"),
                    body = """{"model":"deepseek-flash","messages":[]}""",
                ),
            )
            assertEquals(200, response.status)
            val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
            assertEquals("127.0.0.1", entry.host)
            assertEquals(GatewayPurpose.RoundtableChat, entry.purpose)
            assertTrue(entry.succeeded)
            assertFalse(entry.sentBody.contains("sk-test"))
            assertFalse(File(directory, "privacy/audit.json").readText().contains("Authorization"))
            assertEquals(0.000014, entry.estimatedCostUsd, 0.0000001)
        }
    }

    @Test
    fun cleartextIsOnlyLoopbackAndOnlyWhenDebugAllowsIt() {
        val release = AllowedHosts("http://127.0.0.1:9", allowCleartextLocalhost = false)
        assertFalse(release.allows(URI("http://127.0.0.1:9/chat"), GatewayPurpose.RoundtableChat))
        val debug = AllowedHosts("http://127.0.0.1:9", allowCleartextLocalhost = true)
        assertTrue(debug.allows(URI("http://127.0.0.1:9/chat"), GatewayPurpose.RoundtableChat))
        assertFalse(debug.allows(URI("http://10.0.0.8/chat"), GatewayPurpose.RoundtableChat))
        assertFalse(debug.allows(URI("http://localhost/chat"), GatewayPurpose.RoundtableChat))
    }

    @Test
    fun transportFailureIsAudited() {
        val gateway = gateway("http://127.0.0.1:1", cleartext = true)
        assertThrows(Exception::class.java) {
            gateway.send(GatewayRequest("http://127.0.0.1:1/chat", GatewayPurpose.RoundtableChat, method = "POST", body = "问"))
        }
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertFalse(entry.succeeded)
        assertEquals(null, entry.status)
        assertEquals("问", entry.sentBody)
    }

    private fun gateway(base: String, cleartext: Boolean = false) = OutboundGateway(
        hosts = AllowedHosts(base, cleartext),
        audit = AuditLog(File(directory, "privacy/audit.json")),
        clock = { "2026-10-17T00:00:00Z" },
        open = ::openHttp,
        costOf = { _, response ->
            val usage = org.json.JSONObject(response.body).optJSONObject("usage") ?: return@OutboundGateway 0.0
            usage.optInt("prompt_tokens") * 0.000001 + usage.optInt("completion_tokens") * 0.000001
        },
    )
}
