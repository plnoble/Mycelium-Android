package com.xavierxia.mycelium.contract

import java.nio.file.Path

fun contractRoot(): Path {
    val override = System.getenv("MYCELIUM_CONTRACT_DIR")
    if (!override.isNullOrBlank()) return Path.of(override)
    return generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("contract") }
        .first { it.resolve("checksums.json").toFile().isFile() }
}
