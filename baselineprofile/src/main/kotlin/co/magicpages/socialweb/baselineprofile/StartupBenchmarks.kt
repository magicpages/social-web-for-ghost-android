package co.magicpages.socialweb.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start with and without the baseline profile, on the `benchmarkRelease` variant (R8, like the
 * store build, but profileable and with demo mode). Run `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`;
 * the comparison is `timeToInitialDisplayMs` of the two tests in the output. Generate the profile
 * first, or the second test fails because there is none to require.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Nothing precompiled: what a sideloaded or just-installed app runs like before ART has profiled it. */
    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    /** The state Play leaves an installed app in: the baseline profile's methods compiled ahead of time. */
    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = Iterations,
        setupBlock = { pressHome() },
    ) {
        startDemo()
        waitForNotes()
    }

    private companion object {
        const val Iterations = 10
    }
}
