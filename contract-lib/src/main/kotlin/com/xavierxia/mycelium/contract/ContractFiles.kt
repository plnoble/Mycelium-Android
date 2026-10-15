package com.xavierxia.mycelium.contract

import java.nio.file.Path
import kotlin.io.path.readText

/** The copied contract tree. Tests and the sync script both point at the repo's `contract/`. */
class ContractFiles(val root: Path) {
    fun version(): String = root.resolve("VERSION").readText().trim()

    fun text(relative: String): String = root.resolve(relative).readText()

    fun path(relative: String): Path = root.resolve(relative)

    fun samplePack(): Path = path("samples/synthetic-persona-v1/synthetic-persona-v1.mpack")

    fun sampleDatabase(): Path = path("samples/synthetic-persona-v1/persona.sqlite")
}
