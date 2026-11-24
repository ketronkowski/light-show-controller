package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Keeps run results and the small amount of memory checks need between runs
 * (last seen uptimes, standby current history) under `<data-dir>/preshow`.
 */
@Component
class PreShowStore(
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(PreShowStore::class.java)
    private val root: Path = Path.of(dataDir, "preshow")
    private val runsDir: Path = root.resolve("runs")
    private val stateFile: Path = root.resolve("state.json")
    private val lock = Any()

    fun save(run: PreShowRun) {
        Files.createDirectories(runsDir)
        Files.writeString(runsDir.resolve("${run.id}.json"), objectMapper.writeValueAsString(run))
    }

    fun list(limit: Int = 50): List<PreShowRun> {
        if (!runsDir.exists()) return emptyList()
        return runsDir.listDirectoryEntries("*.json")
            .sortedByDescending { it.name }
            .take(limit)
            .mapNotNull { read(it) }
    }

    fun get(id: String): PreShowRun? {
        if (!id.matches(Regex("[A-Za-z0-9_-]+"))) return null
        val file = runsDir.resolve("$id.json")
        return if (file.exists()) read(file) else null
    }

    fun latest(): PreShowRun? = list(1).firstOrNull()

    /** Saves a file that belongs to a run (e.g. the light test picture) next to it. */
    fun saveAttachment(id: String, name: String, bytes: ByteArray) {
        val file = attachmentFile(id, name) ?: return
        Files.createDirectories(runsDir)
        Files.write(file, bytes)
    }

    fun attachment(id: String, name: String): ByteArray? =
        attachmentFile(id, name)?.takeIf { it.exists() }?.let { Files.readAllBytes(it) }

    private fun attachmentFile(id: String, name: String): Path? {
        val safe = Regex("[A-Za-z0-9_.-]+")
        if (!id.matches(safe) || !name.matches(safe) || name.startsWith(".")) return null
        return runsDir.resolve("$id-$name")
    }

    fun prune(keepDays: Long) {
        if (!runsDir.exists()) return
        val cutoff = Instant.now().minus(Duration.ofDays(keepDays))
        runsDir.listDirectoryEntries()
            .filter { it.getLastModifiedTime().toInstant().isBefore(cutoff) }
            .forEach { runCatching { Files.delete(it) } }
    }

    private fun read(file: Path): PreShowRun? =
        runCatching { objectMapper.readValue(file.readText(), PreShowRun::class.java) }
            .onFailure { log.warn("Unreadable pre-show run {}: {}", file.name, it.message) }
            .getOrNull()

    // ── Small key/value memory between runs ────────────────────────────────

    fun <T> state(block: (ObjectNode) -> T): T = synchronized(lock) {
        val node = if (stateFile.exists())
            runCatching { objectMapper.readTree(stateFile.readText()) as? ObjectNode }.getOrNull()
        else null
        val state = node ?: objectMapper.createObjectNode()
        val result = block(state)
        Files.createDirectories(root)
        Files.writeString(stateFile, objectMapper.writeValueAsString(state))
        result
    }
}
