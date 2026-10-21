package com.xavierxia.mycelium.update

import android.os.StatFs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * 空间不够。方案审 C4 第 3 条要求文案里必须出现「还差多少」，所以差值由这里算好、界面照抄。
 */
class SpaceShortage(val neededBytes: Long, val actualBytes: Long) : Exception(
    "存储空间不够：需要 ${UpdateFiles.humanMiB(neededBytes)}，" +
        "可用只有 ${UpdateFiles.humanMiB(actualBytes)}，" +
        "还差 ${UpdateFiles.humanMiB((neededBytes - actualBytes).coerceAtLeast(0L))}",
)

/**
 * 更新文件落在缓存目录 `updates/`（`res/xml/file_paths.xml` 里已经有这条 cache-path）。
 * 命名带 tag，不同版本的半成品互不覆盖；第 4 步的续传与清理都基于同一份命名。
 *
 * 空间口径（方案审 C4 第 3 条）：**剩余下载量 + 一份安装会话大小 + 余量预算**。
 * `PackageInstaller` 的会话会把包再复制一份到自己的目录，这一份躲不掉，所以按两份算；
 * 已经下到 `.part` 的字节**不再重复算成新增占用**（`occupied + remaining` 恰好就是整包大小），
 * 余量取 `max(32 MiB, 包大小 / 4)`。`apkSizeBytes` 未知时只用 64 MiB 做初始门槛，
 * 拿到真实长度后由下载器复查一次（见 `GatewayApkDownloader`）。
 */
class UpdateFiles(
    private val root: File,
    /** 可用空间。默认真机的 `StatFs`；单测注入假值，不依赖 Robolectric 影子。 */
    private val availableBytes: () -> Long = { StatFs(root.path).availableBytes },
) {
    fun apkFile(tagName: String): File = finalFile(tagName)

    fun finalFile(tagName: String): File = File(root, "mycelium-$tagName.apk")

    fun partFile(tagName: String): File = File(root, "mycelium-$tagName.apk.part")

    /** 下载时记下的期望哈希，校验器只读它，**不再联网**（C4 第 2 条）。 */
    fun checksumFile(tagName: String): File = File(root, "mycelium-$tagName.apk.sha256")

    /** 第 4 步的 `.part` 与清理要用到目录本身。 */
    fun directory(): File = root

    /** `.part` 已经在磁盘上的字节：续传时它决定还剩多少要下。 */
    fun existingBytes(tagName: String): Long =
        maxOf(partFile(tagName).length(), finalFile(tagName).length())

    /** 下载前必调：目录得先存在，否则第一块写不进去。 */
    fun ensureDirectory(): File {
        if (!root.isDirectory) root.mkdirs()
        return root
    }

    /**
     * `.part` → 最终名（`Files.move` + `REPLACE_EXISTING`）。只在哈希与四项校验通过之前调用，
     * 失败路径绝不调用它，所以「可安装的文件」一定对应一个完整的包。
     */
    fun promote(tagName: String): File {
        val part = partFile(tagName)
        val final = finalFile(tagName)
        require(part.isFile) { "没有可改名的下载文件：${part.name}" }
        return Files.move(part.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING).toFile()
    }

    /**
     * 放弃某版本 / 取消安装后的缓存清理：`.apk`、`.part`、`.sha256` 一起删。
     * 与「下载中点取消」区分开——那条路径**保留** `.part`，下次接着下（C4 第 4 条）。
     */
    fun deleteForTag(tagName: String): List<File> =
        listOf(finalFile(tagName), partFile(tagName), checksumFile(tagName)).mapNotNull { file ->
            file.takeIf { it.isFile && it.delete() }
        }

    /**
     * 换 tag 时把别的 tag 的半成品清掉，免得缓存里堆一串永远用不上的 `.part`。
     * [inUse] 为 true 的文件**不删**：可能另一个下载正拿着它写（C4 第 4 条：清理不得删正在使用的文件）。
     */
    fun deleteForeignParts(tagName: String, inUse: (File) -> Boolean = { false }): List<File> {
        val keep = partFile(tagName)
        return parts().filter { it != keep && !inUse(it) && it.delete() }
    }

    /**
     * 启动时的过期清理：超过 7 天的 `.apk` / `.part` / `.sha256` 全清。
     * [keepTagName] 的 `.part` 不动——它是下次续传的前提（简报测例 16）。
     */
    fun purge(nowMs: Long, keepTagName: String?, inUse: (File) -> Boolean = { false }): List<File> {
        val deadline = nowMs - PURGE_AGE_MS
        val keepPart = keepTagName?.let(::partFile)
        return candidates().filter { file ->
            file != keepPart && !inUse(file) && file.lastModified() <= deadline && file.delete()
        }
    }

    /** 真机可用空间；单测走注入的实现。 */
    fun freeBytes(): Long = availableBytes()

    /**
     * 需要腾出多少：`剩余下载 + 会话副本 + 余量`（C4 第 3 条的公式，M4 修正）。
     *
     * **已经在盘上的字节不占新增空间**，所以这里是 `remaining`，而不是 `occupied + remaining`：
     * 38 MiB 的包已下 19 MiB、可用 90 MiB 时，需要的是 19+38+32=89 MiB —— 旧公式算成 108 MiB，
     * 会把一次正常的续传误判成空间不够。
     */
    fun requiredBytes(apkSizeBytes: Long?, existingBytes: Long = 0L): Long {
        val size = apkSizeBytes
        if (size == null || size <= 0L) return UNKNOWN_SIZE_FLOOR_BYTES
        val occupied = existingBytes.coerceIn(0L, size)
        val remaining = size - occupied
        val margin = maxOf(MARGIN_MIN_BYTES, size / 4)
        return remaining + size + margin
    }

    /**
     * 安装那一步还要一份会话副本 + 余量。下载完成到用户点安装之间可用空间可能已经变了，
     * 所以安装入口必须单独复查一次（M4），不能沿用下载前那次判断。
     */
    fun sessionRequiredBytes(apkSizeBytes: Long): Long {
        val size = apkSizeBytes.coerceAtLeast(0L)
        return size + maxOf(MARGIN_MIN_BYTES, size / 4)
    }

    /** 安装前复查空间：不够就抛 [SpaceShortage]（文案里同样有「还差多少」）。 */
    fun requireSessionSpace(apkSizeBytes: Long) {
        val needed = sessionRequiredBytes(apkSizeBytes)
        val actual = freeBytes()
        if (actual < needed) throw SpaceShortage(needed, actual)
    }

    /** 不够就抛 [SpaceShortage]（差值在人话里）；够就静默返回。下载前必调。 */
    fun requireSpace(apkSizeBytes: Long?, existingBytes: Long = 0L) {
        val needed = requiredBytes(apkSizeBytes, existingBytes)
        val actual = freeBytes()
        if (actual < needed) throw SpaceShortage(needed, actual)
    }

    private fun parts(): List<File> =
        root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(PART_SUFFIX) }

    private fun candidates(): List<File> =
        root.listFiles().orEmpty().filter { it.isFile && SUFFIXES.any(it.name::endsWith) }

    companion object {
        const val PART_SUFFIX = ".part"

        private val SUFFIXES = listOf(".apk", PART_SUFFIX, ".sha256")

        /** 余量预算下限：38 MiB 的包按这一档就偏薄，所以会话副本之外还要单独留出来。 */
        const val MARGIN_MIN_BYTES = 32L * 1024 * 1024

        /** 大小未知时的初始门槛；拿到真实长度后必须复查（简报 Q7 / C4 第 3 条）。 */
        const val UNKNOWN_SIZE_FLOOR_BYTES = 64L * 1024 * 1024

        /** 过期清理的界限：7 天。 */
        const val PURGE_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun humanMiB(bytes: Long): String =
            String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)
    }
}