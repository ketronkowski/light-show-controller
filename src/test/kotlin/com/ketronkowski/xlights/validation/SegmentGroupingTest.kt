package com.ketronkowski.xlights.validation

import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.domain.XLightsModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SegmentGroupingTest {

    // Lays models out back to back, 30 channels (10 RGB pixels) each.
    private fun controller(names: List<String>, groups: Map<String, List<String>> = emptyMap()) =
        XLightsController(
            name = "Octa6", ipAddress = "1.2.3.4", protocol = "DDP",
            totalChannels = names.size * 30,
            models = names.mapIndexed { i, n ->
                XLightsModel(n, "Octa6", startChannel = 1 + i * 30, channelCount = 30, isNull = n.startsWith("Null"))
            },
            modelGroups = groups,
        )

    private val dogwood = listOf("Dogwood-Base", "Dogwood-Back", "Dogwood-Right", "Dogwood-Left")
    private val windows = (1..10).flatMap { listOf("Window - W$it", "Null-W$it-Post") }

    @Test
    fun `numbered models always collapse into one segment`() {
        val c = controller(listOf("Arch", "PeaceStake-1", "PeaceStake-2", "PeaceStake-3", "Tree"))

        val result = SegmentGrouping.effectiveModels(c, maxSegments = 32)

        assertEquals(listOf("Arch", "PeaceStake", "Tree"), result.map { it.name })
        assertEquals(90, result[1].channelCount)
    }

    @Test
    fun `a group is left alone while the controller fits within the limit`() {
        val c = controller(windows + dogwood, mapOf("KET - Dogwood" to dogwood))

        val result = SegmentGrouping.effectiveModels(c, maxSegments = 32)

        assertEquals(24, result.size)
    }

    @Test
    fun `a contiguous group becomes one segment when the controller is over the limit`() {
        val c = controller(windows + dogwood, mapOf("KET - Dogwood" to dogwood.reversed()))

        val result = SegmentGrouping.effectiveModels(c, maxSegments = 21)

        assertEquals(21, result.size)
        val merged = result.last()
        assertEquals("KET - Dogwood", merged.name)
        assertEquals(1 + 20 * 30, merged.startChannel)
        assertEquals(4 * 30, merged.channelCount)
        assertEquals(false, merged.isNull)
    }

    @Test
    fun `groups that are not adjacent or include other models are never merged`() {
        val c = controller(windows + dogwood, mapOf(
            "Windows"  to windows.filter { it.startsWith("Window") },   // nulls sit between them
            "Partial"  to listOf("Dogwood-Base", "Dogwood-Back", "Window - W10"),
        ))

        val result = SegmentGrouping.effectiveModels(c, maxSegments = 21)

        assertEquals(24, result.size)
    }

    @Test
    fun `the smallest group that closes the gap is merged, not a bigger one`() {
        val c = controller(windows + dogwood, mapOf(
            "Big"   to listOf("Window - W1", "Null-W1-Post", "Window - W2", "Null-W2-Post", "Window - W3"),
            "Small" to listOf("Dogwood-Right", "Dogwood-Left"),
        ))

        val result = SegmentGrouping.effectiveModels(c, maxSegments = 23)

        assertEquals(23, result.size)
        assertEquals("Small", result.last().name)
        assertEquals("Window - W1", result.first().name)
    }

    @Test
    fun `several groups are merged when one is not enough`() {
        val c = controller(windows + dogwood, mapOf(
            "First3" to listOf("Window - W1", "Null-W1-Post", "Window - W2"),
            "Dog"    to dogwood,
        ))

        val result = SegmentGrouping.effectiveModels(c, maxSegments = 19)

        assertEquals(19, result.size)
        assertEquals("First3", result.first().name)
        assertEquals("Dog", result.last().name)
    }

    @Test
    fun `without a known limit nothing beyond numbered models is merged`() {
        val c = controller(dogwood, mapOf("KET - Dogwood" to dogwood))

        assertEquals(4, SegmentGrouping.effectiveModels(c, maxSegments = null).size)
    }
}
