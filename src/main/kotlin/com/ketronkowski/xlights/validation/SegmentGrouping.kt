package com.ketronkowski.xlights.validation

import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.domain.XLightsModel

// Turns a controller's xLights models into the list of WLED segments to sync.
//
// Two merges, in this order:
//  1. Always: consecutive models whose names differ only by a trailing "-N" become
//     one segment (PeaceStake-1 … PeaceStake-35 → "PeaceStake").
//  2. Only when the controller still has more segments than WLED allows: an xLights
//     model group whose members all sit next to each other on this controller becomes
//     one segment named after the group (the "KET - Dogwood" group's Dogwood-Base,
//     -Back, -Right and -Left → "KET - Dogwood"). Just enough groups are merged to fit,
//     so controllers under the limit keep one segment per model.
internal object SegmentGrouping {

    private val numberedSuffix = Regex("""-\d+$""")

    // A segment to sync and the xLights model names it covers.
    private data class Entry(val model: XLightsModel, val members: Set<String>)

    fun effectiveModels(controller: XLightsController, maxSegments: Int?): List<XLightsModel> {
        var entries = collapseNumbered(controller.models)
        if (maxSegments == null) return entries.map { it.model }

        val used = mutableSetOf<String>()
        while (entries.size > maxSegments) {
            val runs = candidateRuns(entries, controller.modelGroups)
                .filter { it.groupName !in used }
            if (runs.isEmpty()) break
            val needed = entries.size - maxSegments
            // The smallest group that closes the gap on its own; otherwise the group
            // that saves the most, and go round again.
            val pick = runs.filter { it.saves >= needed }.minWithOrNull(compareBy({ it.saves }, { it.groupName }))
                ?: runs.maxWith(compareBy<Run>({ it.saves }).thenByDescending { it.groupName })
            used += pick.groupName
            entries = entries.subList(0, pick.from) +
                merge(pick.groupName, entries.subList(pick.from, pick.to)) +
                entries.subList(pick.to, entries.size)
        }
        return entries.map { it.model }
    }

    private data class Run(val groupName: String, val from: Int, val to: Int) {
        val saves get() = to - from - 1
    }

    // Groups whose members are exactly a run of two or more adjacent entries.
    private fun candidateRuns(entries: List<Entry>, groups: Map<String, List<String>>): List<Run> =
        groups.mapNotNull { (name, rawMembers) ->
            val members = rawMembers.map { it.lowercase() }.toSet()
            val hits = entries.indices.filter { i -> entries[i].members.all { it in members } }
            if (hits.size < 2) return@mapNotNull null
            val from = hits.first()
            val to   = hits.last() + 1
            val contiguous = hits.size == to - from
            val exact = entries.subList(from, to).flatMap { it.members }.toSet() == members
            if (contiguous && exact) Run(name, from, to) else null
        }

    private fun collapseNumbered(models: List<XLightsModel>): List<Entry> {
        val result = mutableListOf<Entry>()
        var i = 0
        while (i < models.size) {
            val model = models[i]
            if (!numberedSuffix.containsMatchIn(model.name)) {
                result += Entry(model, setOf(model.name.lowercase()))
                i++
                continue
            }
            val baseName = model.name.replace(numberedSuffix, "")
            var j = i + 1
            while (j < models.size) {
                val next = models[j]
                if (!numberedSuffix.containsMatchIn(next.name)) break
                if (next.name.replace(numberedSuffix, "") != baseName) break
                j++
            }
            val group = models.subList(i, j)
            result += if (group.size == 1) Entry(model, setOf(model.name.lowercase()))
            else merge(baseName, group.map { Entry(it, setOf(it.name.lowercase())) })
            i = j
        }
        return result
    }

    private fun merge(name: String, run: List<Entry>): Entry {
        val first = run.first().model
        return Entry(
            model = first.copy(
                name         = name,
                channelCount = run.sumOf { it.model.channelCount },
                isNull       = run.all { it.model.isNull },
            ),
            members = run.flatMap { it.members }.toSet(),
        )
    }
}
