package com.xavierxia.mycelium.update

import java.io.File

/**
 * 更新文件落在缓存目录 `updates/`（`res/xml/file_paths.xml` 里已经有这条 cache-path）。
 * 命名带 tag，不同版本的半成品互不覆盖；第 4 步的续传与清理都基于同一份命名。
 */
class UpdateFiles(private val root: File) {
    fun apkFile(tagName: String): File = File(root, "mycelium-$tagName.apk")

    /** 第 4 步的 `.part` 与清理要用到目录本身。 */
    fun directory(): File = root
}