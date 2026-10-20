package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 工作包 1.2 第 3 条：跟随跳转时，**跳到别的域名就不再带凭据类请求头**。
 *
 * 现在的更新下载不带这些头，但 P2-5 下载模型会带 Hugging Face 的 token；如果 token 跟着
 * 302 跳到 release-assets 那种 CDN 上，就等于把凭据交给了第三方。同域跳转照旧保留。
 *
 * 用 `127.0.0.1` 与 `localhost` 这两个**不同的域名**指向同一台假服务，就能在一个测试里造出
 * 「同域」和「跨域」两种跳转。
 */
class RedirectCredentialsTest {
    @TempDir
    lateinit var directory: File

    private val credentials = mapOf(
        "Authorization" to "Bearer hf_secret_token",
        "Cookie" to "session=abc",
        "Proxy-Authorization" to "Basic eA==",
        "X-Trace" to "keep-me",
    )

    private fun gateway() = OutboundGateway(
        hosts = HostPolicy { uri, _ -> uri.host?.lowercase() in setOf("127.0.0.1", "localhost") },
        audit = AuditLog(File(directory, "privacy/audit.json")),
        clock = { "2026-10-21T00:00:00Z" },
        open = ::openHttp,
    )

    /** 一个只回答 `/start`（302）与 `/final`（200）的假服务，跳转目标在测试里现设。 */
    private fun redirectServer(): Pair<LocalHttp, (String) -> Unit> {
        lateinit var server: LocalHttp
        var target = ""
        server = LocalHttp { call ->
            if (call.path == "/start") {
                HttpReply(302, ByteArray(0), mapOf("Location" to target))
            } else {
                HttpReply(200, "final")
            }
        }
        return server to { url -> target = url }
    }

    private fun run(gateway: OutboundGateway, port: Int, host: String = "127.0.0.1") = gateway.readFollowingRedirects(
        GatewayRequest(
            url = "http://$host:$port/start",
            purpose = GatewayPurpose.Update,
            headers = credentials,
        ),
    )

    @Test
    @Timeout(30)
    fun aRedirectWithinTheSameHostKeepsCredentialHeaders() {
        val (server, aimAt) = redirectServer()
        server.use {
            aimAt("http://127.0.0.1:${server.port}/final")
            assertEquals(200, run(gateway(), server.port).status)

            val calls = server.calls()
            assertEquals(2, calls.size, "应该正好两跳：/start 与 /final")
            // LocalHttp 把请求头名统一成小写。
            assertEquals("Bearer hf_secret_token", calls[1].headers["authorization"], "同域跳转要保留 Authorization")
            assertEquals("session=abc", calls[1].headers["cookie"], "同域跳转要保留 Cookie")
            assertEquals("Basic eA==", calls[1].headers["proxy-authorization"], "同域跳转要保留 Proxy-Authorization")
            assertEquals("keep-me", calls[1].headers["x-trace"], "普通头一直保留")
        }
    }

    @Test
    @Timeout(30)
    fun aRedirectToAnotherHostDropsCredentialHeaders() {
        val (server, aimAt) = redirectServer()
        server.use {
            aimAt("http://localhost:${server.port}/final") // 同一台机器、另一个域名
            assertEquals(200, run(gateway(), server.port).status)

            val calls = server.calls()
            assertEquals(2, calls.size, "应该正好两跳：/start 与 /final")
            assertEquals("Bearer hf_secret_token", calls[0].headers["authorization"], "第一跳还是最初那个域名，凭据照发")
            assertNull(calls[1].headers["authorization"], "跨域跳转不许再带 Authorization")
            assertNull(calls[1].headers["cookie"], "跨域跳转不许再带 Cookie")
            assertNull(calls[1].headers["proxy-authorization"], "跨域跳转不许再带 Proxy-Authorization")
            assertEquals("keep-me", calls[1].headers["x-trace"], "非凭据的普通头不受影响")
        }
    }
    @Test
    @Timeout(30)
    fun aRedirectToTheSameHostOnAnotherPortDropsCredentialHeaders() {
        // 同一台主机、另一个端口：那是同一主机上的**另一个服务**，凭据不能跟过去（复审要求）。
        val second = LocalHttp { HttpReply(200, "final") }
        second.use {
            val (server, aimAt) = redirectServer()
            server.use {
                aimAt("http://127.0.0.1:${second.port}/final")
                assertEquals(200, run(gateway(), server.port).status)

                assertEquals("Bearer hf_secret_token", server.calls()[0].headers["authorization"])
                val hopTwo = second.calls().single()
                assertNull(hopTwo.headers["authorization"], "同主机另一个端口也不许带 Authorization")
                assertNull(hopTwo.headers["cookie"], "同主机另一个端口也不许带 Cookie")
                assertEquals("keep-me", hopTwo.headers["x-trace"])
            }
        }
    }

    @Test
    @Timeout(30)
    fun aRedirectThatOnlyChangesTheCaseOfTheHostKeepsCredentialHeaders() {
        val (server, aimAt) = redirectServer()
        server.use {
            aimAt("http://LOCALHOST:${server.port}/final")
            // 起点就叫 localhost，跳转只是把大小写换了一下：同一个域名，凭据要保留。
            assertEquals(200, run(gateway(), server.port, host = "localhost").status)
            // JUnit5 的参数顺序：期望值、实际值、说明（说明放最后）。
            assertEquals(
                "Bearer hf_secret_token",
                server.calls()[1].headers["authorization"],
                "主机名大小写不算换域",
            )
        }
    }
}