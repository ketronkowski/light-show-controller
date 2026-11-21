package com.ketronkowski.xlights.validation

import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.domain.XLightsModel

// Turns a controller's xLights models into the list of WLED segments to sync.
//
// Merges, in this order:
//  1. Always: consecutive models whose names differ only by a trailing "-N" become
//     one segment (PeaceStake-1 … PeaceStake-35 → "PeaceStake").
//  2. Always: each xLights model group named in the xlights.segment-groups setting,
//     when its members all sit next to each other on this controller, becomes one
//     segment named after the group ("KET - Yew Left" → Yew Left Top/Middle/Bottom).
//  3. Always: back-to-back null models (gaps that stay dark) become one segment,
//     named after all of them ("Null-Front Door-Post + Null-LNL-Pre"). Saves WLED
//     segments, which some boards run short of (Octa6 sits at the 32 limit).
//  4. Only when the controller still has more segments than WLED allows: any other
//     group of adjacent models on this controller, merged the same way. Just enough
//     groups are merged to fit, so controllers under the limit are left alone.
internal object SegmentGrouping {

    private val numberedSuffix = Regex("""-\d+$""")

    // A segment to sync and the xLights model names it covers.
    private data class Entry(val model: XLightsModel, val members: Set<String>)

    fun effectiveModels(
        controller: XLightsController,
        maxSegments: Int?,
        alwaysMerge: Set<String> = emptySet(),
    ): List<XLightsModel> {
        var entries = collapseNumbered(controller.models)
        val used = mutableSetOf<String>()

        val wanted = alwaysMerge.map { it.lowercase() }.toSet()
        while (true) {
            val run = candidateRuns(entries, controller.modelGroups)
                .filter { it.groupName.lowercase() in wanted && it.groupName !in used }
                .minByOrNull { it.groupName }
                ?: break
            used += run.groupName
            entries = apply(entries, run)
        }
        entries = mergeAdjacentNulls(entries)
        if (maxSegments == null) return entries.map { it.model }

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
            entries = apply(entries, pick)
        }
        return entries.map { it.model }
    }

    // Consecutive null entries whose channels touch become one dark segment.
    private fun mergeAdjacentNulls(entries: List<Entry>): List<Entry> {
        val result = mutableListOf<Entry>()
        var run = mutableListOf<Entry>()
        fun flush() {
            if (run.size > 1) result.add(merge(run.joinToString(" + ") { it.model.name }, run)) else result.addAll(run)
            run = mutableListOf()
        }
        for (e in entries) {
            val prev = run.lastOrNull()
            val touches = prev != null && prev.model.startChannel + prev.model.channelCount == e.model.startChannel
            if (e.model.isNull && (prev == null || touches)) {
                run += e
            } else {
                flush()
                if (e.model.isNull) run += e else result += e
            }
        }
        flush()
        return result
    }

    private fun apply(entries: List<Entry>, run: Run): List<Entry> =
        entries.subList(0, run.from) +
            merge(run.groupName, entries.subList(run.from, run.to)) +
            entries.subList(run.to, entries.size)

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
