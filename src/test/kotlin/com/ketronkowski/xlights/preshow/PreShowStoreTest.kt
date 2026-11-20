package com.ketronkowski.xlights.preshow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PreShowStoreTest {

    @TempDir lateinit var dir: Path

    @Test
    fun `runs round-trip and list newest first`() {
        val store = PreShowStore(mapper, dir.toString())
        val results = listOf(CheckResult.warn("FPP", "Clock", "FPP", "off by 30 s"))
        store.save(PreShowRun("20261126-140000-early", "early", at(14), at(14), Verdict.GO_WITH_WARNINGS, results))
        store.save(PreShowRun("20261126-165000-final", "final", at(16, 50), at(16, 50), Verdict.GO, emptyList()))

        assertEquals(listOf("20261126-165000-final", "20261126-140000-early"), store.list().map { it.id })
        assertEquals(results, store.get("20261126-140000-early")!!.results)
        assertNull(store.get("../etc/passwd"))
    }
}
