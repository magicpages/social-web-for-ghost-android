package co.magicpages.socialweb.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the app's baseline profile (and its startup profile) from the demo mode of the
 * `nonMinifiedRelease` variant. Run with `./gradlew :app:generateBaselineProfile`, see RELEASING.md.
 *
 * Each test is one profile collection: the app is started cold, the steps run, and the classes and
 * methods ART compiled or loaded along the way are read out. Collection repeats until the result
 * stops changing.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun grantPermissions() = grantNotificationPermission()

    /**
     * Only the launch: cold start up to the first Notes screen. This is what goes into
     * `startup-prof.txt`, which R8 uses to put the startup classes together in the primary dex.
     * Keep it to the launch: everything here is treated as needed before the first frame.
     */
    @Test
    fun startup() = rule.collect(
        packageName = TargetPackage,
        maxIterations = MaxIterations,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startDemo()
        waitForNotes()
    }

    /** What people do in the app: the four tabs, a thread, a profile and an article. */
    @Test
    fun userJourney() = rule.collect(
        packageName = TargetPackage,
        maxIterations = MaxIterations,
    ) {
        pressHome()
        startDemo()
        waitForNotes()

        // Notes: a thread, the author's profile, then the feed itself.
        openThreadAndBack()
        waitForNotes()
        openProfileAndBack()
        waitForNotes()
        scrollDown(5)

        // Reader: an article, then the feed.
        openTab("Reader")
        openArticleAndBack()
        scrollDown(3)

        openTab("Explore")
        waitSoftly("Elena Marsh")
        scrollDown(3)

        openTab("Notifications")
        waitSoftly("Duarte")
        scrollDown(3)
    }

    private companion object {
        /** Below the default of 15: each extra iteration is a full cold start and walk through the app. */
        const val MaxIterations = 10
    }
}
