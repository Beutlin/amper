/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core.messages

import org.jetbrains.amper.core.forEachEndAware

interface ProblemReporter {
    /**
     * Check if we reported any fatal errors.
     */
    val hasFatal: Boolean

    fun reportMessage(message: BuildProblem)
}

interface ProblemReporterContext {
    val problemReporter: ProblemReporter
}

// TODO: Can be refactored to the reporter chain to avoid inheritance.
// Note: This class is not thread-safe.
// Problems collecting might misbehave when used from multiple threads (e.g. in Gradle).
abstract class CollectingProblemReporter : ProblemReporter {
    override val hasFatal get() = problems.any { it.level == Level.Fatal }

    protected val problems: MutableList<BuildProblem> = mutableListOf()

    protected abstract fun doReportMessage(message: BuildProblem)

    override fun reportMessage(message: BuildProblem) {
        problems.add(message)
        doReportMessage(message)
    }
}

@OptIn(NonIdealDiagnostic::class)
fun renderMessage(problem: BuildProblem): String = buildString {
    fun appendSource(source: BuildProblemSource) {
        when (source) {
            is FileBuildProblemSource -> {
                append(source.file.normalize())
                if (source is FileWithRangesBuildProblemSource) {
                    val start = source.range.start
                    append(":${start.line}:${start.column}")
                }
                append(": ")
                append(problem.message)
            }
            is MultipleLocationsBuildProblemSource -> source.sources.forEachEndAware { isLast, it ->
                appendSource(it)
                if (!isLast) appendLine()
            }
            GlobalBuildProblemSource -> append(problem.message)
        }
    }

    appendSource(problem.source)
}
