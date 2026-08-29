package com.github.ronah123.vanderbilttestplugin.startup

import com.github.ronah123.vanderbilttestplugin.actions.AnalyzeCoverageAction
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuiteListener
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts TestCompass whenever IntelliJ finishes calculating a coverage run. */
class CoverageAutoAnalysisActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        CoverageDataManager.getInstance(project).addSuiteListener(
            CoverageCalculatedListener(project),
            project
        )
    }
}

internal class CoverageCalculatedListener(
    private val project: Project,
    private val runTracker: CoverageRunTracker = CoverageRunTracker(),
    private val analyze: (Project, CoverageSuitesBundle) -> Unit = { currentProject, bundle ->
        AnalyzeCoverageAction().analyze(currentProject, calculatedBundle = bundle)
    }
) : CoverageSuiteListener {

    override fun coverageDataCalculated(bundle: CoverageSuitesBundle) {
        if (project.isDisposed) return
        if (!runTracker.claim(bundle, bundle.lastCoverageTimeStamp)) return

        analyze(project, bundle)
    }
}

/** Prevents duplicate callbacks for the same calculated coverage result. */
internal class CoverageRunTracker {
    private val processedTimestamps = java.util.IdentityHashMap<Any, Long>()

    @Synchronized
    fun claim(bundleIdentity: Any, timestamp: Long): Boolean {
        if (processedTimestamps[bundleIdentity] == timestamp) return false

        processedTimestamps[bundleIdentity] = timestamp
        return true
    }
}
