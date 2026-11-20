package com.ketronkowski.xlights.preshow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

/**
 * One check's outcome. FAIL is a show blocker (the show can't run or looks broken),
 * WARN means the show runs but something needs attention, SKIP means the check is
 * not configured or does not apply right now.
 */
enum class Outcome { PASS, WARN, FAIL, SKIP }

enum class Verdict(val label: String) {
    GO("GO"),
    GO_WITH_WARNINGS("GO with warnings"),
    NO_GO("NO-GO"),
}

data class CheckResult(
    val layer: String,      // Power, Network, Boards, FPP, Web, ...
    val name: String,       // what was checked, e.g. "Answering"
    val target: String,     // what it was checked on, e.g. "Octa5"
    val outcome: Outcome,
    val message: String,
) {
    companion object {
        fun pass(layer: String, name: String, target: String, message: String = "OK") =
            CheckResult(layer, name, target, Outcome.PASS, message)
        fun warn(layer: String, name: String, target: String, message: String) =
            CheckResult(layer, name, target, Outcome.WARN, message)
        fun fail(layer: String, name: String, target: String, message: String) =
            CheckResult(layer, name, target, Outcome.FAIL, message)
        fun skip(layer: String, name: String, target: String, message: String) =
            CheckResult(layer, name, target, Outcome.SKIP, message)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PreShowRun(
    val id: String,
    val pass: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val verdict: Verdict,
    val results: List<CheckResult>,
    // Where the expected layout came from, so a stale upload is visible in the report.
    val layoutUploadedAt: Instant? = null,
) {
    val failures: List<CheckResult> get() = results.filter { it.outcome == Outcome.FAIL }
    val warnings: List<CheckResult> get() = results.filter { it.outcome == Outcome.WARN }
    val passed: Int get() = results.count { it.outcome == Outcome.PASS }
    val counted: Int get() = results.count { it.outcome != Outcome.SKIP }

    /** One line for a push title or email subject. */
    fun headline(): String = when (verdict) {
        Verdict.GO -> "GO: all $counted checks passed"
        Verdict.GO_WITH_WARNINGS -> "GO with ${warnings.size} warning${plural(warnings.size)}: $passed of $counted passed"
        Verdict.NO_GO -> "NO-GO: ${failures.size} blocker${plural(failures.size)}, ${warnings.size} warning${plural(warnings.size)}"
    }

    /** The top problems in plain words, blockers first. */
    fun topProblems(max: Int = 3): List<String> =
        (failures + warnings).take(max).map { "${it.target}: ${it.message}" }

    companion object {
        fun verdictOf(results: List<CheckResult>): Verdict = when {
            results.any { it.outcome == Outcome.FAIL } -> Verdict.NO_GO
            results.any { it.outcome == Outcome.WARN } -> Verdict.GO_WITH_WARNINGS
            else -> Verdict.GO
        }

        private fun plural(n: Int) = if (n == 1) "" else "s"
    }
}
