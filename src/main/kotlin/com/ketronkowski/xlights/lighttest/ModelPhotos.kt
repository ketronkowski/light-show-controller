package com.ketronkowski.xlights.lighttest

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * One camera picture per model, taken while that model alone was lit: for the latest
 * calibration and the latest test, so the page can show what the camera saw for each.
 *
 * A sweep writes into a staging folder and only replaces the shown set when it finishes,
 * so a stopped or failed run never leaves half a set behind. Each picture is encoded and
 * written as soon as it's taken; none are held in memory.
 */
class ModelPhotos(root: Path) {
    private val base = root.resolve("photos")

    private fun dir(job: String): Path = base.resolve(job)
    private fun staging(job: String): Path = base.resolve("$job.new")

    /** Starts a fresh staging folder for [job] ("calibration" or "test"). */
    fun begin(job: String): Path {
        val s = staging(job)
        deleteTree(s)
        Files.createDirectories(s)
        return s
    }

    /** Writes [model]'s picture into a staging folder from [begin]. */
    fun save(staging: Path, model: String, frame: Frame) {
        val rgb = frame.rgb ?: return
        val out = ByteArrayOutputStream()
        ImageIO.write(LightAnalysis.toImage(rgb, frame.width, frame.height), "jpg", out)
        Files.write(staging.resolve(fileName(model)), out.toByteArray())
    }

    /**
     * Makes the staged pictures the shown set. With [merge] (recalibrating only the missing
     * models), they replace just those models' pictures and the rest stay.
     */
    fun commit(job: String, merge: Boolean = false) {
        val s = staging(job)
        if (!s.isDirectory()) return
        val d = dir(job)
        if (!merge || !d.isDirectory()) {
            deleteTree(d)
            Files.move(s, d, StandardCopyOption.ATOMIC_MOVE)
            return
        }
        for (f in s.listDirectoryEntries()) Files.move(f, d.resolve(f.fileName), StandardCopyOption.REPLACE_EXISTING)
        deleteTree(s)
    }

    fun discard(job: String) = deleteTree(staging(job))

    fun has(job: String, model: String): Boolean = dir(job).resolve(fileName(model)).exists()

    fun get(job: String, model: String): ByteArray? =
        dir(job).resolve(fileName(model)).takeIf { it.exists() }?.let { Files.readAllBytes(it) }

    companion object {
        val JOBS = setOf("calibration", "test")

        /** Model names hold spaces, slashes and the like: a hash keeps file names safe and unique. */
        fun fileName(model: String): String =
            MessageDigest.getInstance("SHA-256").digest(model.toByteArray()).take(10)
                .joinToString("") { "%02x".format(it) } + ".jpg"

        private fun deleteTree(p: Path) {
            if (!p.exists()) return
            Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
