package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.GatewayCall
import java.io.File

/** `.sha256` 附件只有一行哈希：4 KiB 是方案审 C4 定死的上限，超了就当校验文件异常。 */
const val CHECKSUM_LIMIT_BYTES = 4 * 1024

/**
 * 第 4 步的「app 侧出网口子」。第 3 步已经把上层的分工定稿（见 `UpdateGateway.kt`），
 * 这一层只是把「一条 APK 流下载 / 一次小元数据读取 / 本地哈希」变成可注入的接口，
 * 单测因此不需要网络、不需要真机。
 *
 * 三条都是方案审 C4 的硬要求：
 *  * [downloadApk] **必须**收 `expectedSha256`：`FileDownloader` 靠它决定「哈希不符就删掉重下」；
 *  * [fetchChecksumText] **必须**收 [GatewayCall]：取消要能停掉这次校验文件读取；
 *  * [sha256] 只读本地文件，校验器**不再联网**取校验文件。
 */
interface UpdateTransport {
    fun downloadApk(
        url: String,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        progress: DownloadProgress? = null,
    ): DownloadOutcome

    /** 取 `.sha256` 附件正文；走跟跳转、带上限的通道，超限抛 [com.xavierxia.mycelium.net.GatewayRejected]。 */
    fun fetchChecksumText(url: String, call: GatewayCall, limitBytes: Int = CHECKSUM_LIMIT_BYTES): String

    /** 整文件流式哈希（分块读，不整包进内存），读盘时检查取消。 */
    fun sha256(file: File, call: GatewayCall): String
}