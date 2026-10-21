package com.xavierxia.mycelium.update

/**
 * 卡片上的更新说明：发布说明是 Markdown，但卡片只显示纯文本，所以在**显示之前**做一次很轻的
 * 去标记（§1.5 第 2 条）。只处理任务书点名的几种，不做通用 Markdown 解析：
 *
 *  * 行首的 `#`（几级标题都算）与 `>`（引用）去掉；
 *  * 粗体的 `**`、`__` 与行内代码的反引号去掉；
 *  * `<网址>` 去掉尖括号（GitHub 的 compare 链接就是这么写的）；
 *  * 行首的 `- ` 换成 `• `（列表符号）。
 *
 * 没点名的写法一律**原样保留**：宁可漏一点符号，也不要把用户写的内容改坏。
 */
internal fun plainReleaseNotes(raw: String): String =
    raw.lines().joinToString("\n") { line -> plainReleaseNotesLine(line) }

private fun plainReleaseNotesLine(line: String): String {
    val indent = line.takeWhile { it == ' ' }
    var body = line.drop(indent.length)
    // 行首的标题 / 引用标记：`###`、`>`，后面通常还跟一个空格。
    body = body.trimStart('#', '>').removePrefix(" ")
    // 列表符号：只认「行首的 - 」，段中的连字符不动。
    if (body.startsWith("- ")) body = "• " + body.removePrefix("- ")
    body = body.replace("**", "").replace("__", "").replace("`", "")
    // `<网址>`：只去掉成对的尖括号，别把别的尖括号也吃掉。
    body = ANGLE_URL.replace(body) { match -> match.groupValues[1] }
    return indent + body
}

private val ANGLE_URL = Regex("<([^<>\\s]+)>")