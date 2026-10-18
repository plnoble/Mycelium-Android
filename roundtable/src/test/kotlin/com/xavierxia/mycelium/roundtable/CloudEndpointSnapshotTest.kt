package com.xavierxia.mycelium.roundtable

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 全量审必修第 5 条：发请求用的地址和密钥必须来自**同一次**读取。
 *
 * 这里让存储在读取之间“被后台保存改了”（第一次读拿到旧版本，之后都是新版本），
 * 然后证明 [CloudStore.endpoint] 不会把旧地址和新密钥拼在一起——而分两次读就会，
 * 第二个测例正是把那个组合钉出来，说明第一个测例不是空过。
 */
class CloudEndpointSnapshotTest {
    private class FakeBox : SecretBox {
        override fun wrap(plain: ByteArray): String = "wrapped:" + String(plain, Charsets.UTF_8)

        override fun unwrap(stored: String): ByteArray =
            stored.removePrefix("wrapped:").toByteArray(Charsets.UTF_8)
    }

    private fun blob(url: String, key: String): String =
        """{"services":[{"id":"s1","name":"生产服务","baseUrl":"$url","key":"wrapped:$key"}],""" +
            """"models":{},"selection":null}"""

    /** 第一次读给旧版本，之后都给新版本：模拟两次读取之间发生的保存。 */
    private fun storeThatChangesBetweenReads(old: String, new: String): Pair<CloudStore, () -> Int> {
        var reads = 0
        val store = CloudStore(
            storage = { if (reads++ == 0) old else new },
            persist = {},
            secrets = FakeBox(),
        )
        return store to { reads }
    }

    @Test
    fun theAddressAndTheKeyComeFromTheSameSavedVersion() {
        val (store, reads) = storeThatChangesBetweenReads(
            old = blob("http://127.0.0.1:1000", "旧密钥"),
            new = blob("http://127.0.0.1:2000", "新密钥"),
        )

        val snapshot = store.endpoint("s1")

        assertEquals("http://127.0.0.1:1000", snapshot?.baseUrl, "地址该来自第一次读取")
        assertEquals("旧密钥", snapshot?.apiKey, "密钥必须和地址同一次读取")
        assertEquals(1, reads(), "endpoint 只能读一次配置")
    }

    @Test
    fun readingThemSeparatelyWouldMixTwoVersions() {
        val (store, _) = storeThatChangesBetweenReads(
            old = blob("http://127.0.0.1:1000", "旧密钥"),
            new = blob("http://127.0.0.1:2000", "新密钥"),
        )

        // 这就是审稿描述的老做法：先读服务、再读密钥，两次之间被保存插进来。
        val address = store.services().first { it.id == "s1" }.baseUrl
        val key = store.apiKey("s1")

        assertEquals("http://127.0.0.1:1000", address)
        assertEquals("新密钥", key, "分开读就会拼出「旧地址 + 新密钥」，所以要一次读完")
    }
}