package co.magicpages.socialweb.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/*
 * Steps shared by the profile generator and the startup benchmark. The app runs in its demo mode
 * (app/src/debug/.../demo/DemoMode.kt), which the :app profiling variants include: it signs in as a
 * fictional site and answers every request from made-up content, so no account, network or server is
 * needed. Elements are found by the text of that content, and by tab labels, which are in English:
 * run on a device or emulator set to English.
 */

internal const val TargetPackage = "co.magicpages.socialweb"

/** The launch-intent extra that turns demo mode on (`DemoMode.EXTRA`). */
private const val DemoExtra = "demo"

private const val WaitMs = 15_000L
private const val SoftWaitMs = 3_000L

/** First note in the Notes feed, "ridge" in DemoContent. Its presence means the feed has loaded. */
private const val FirstNote = "Up at 4:40 for this"

/** A text-only note in the Notes feed ("bench"); tapping its middle opens the thread, not a picture. */
private const val TextNote = "Unpopular opinion"

/** First article in the Reader ("newsletters-serif"). */
private const val FirstArticle = "quietly switching to serif"

/** Author of [TextNote]; the avatar's content description is the name. */
private const val TextNoteAuthor = "Kofi Mensah"

/** Cold-starts the app (the process must be dead already) in demo mode, returning on the first frame. */
internal fun MacrobenchmarkScope.startDemo() {
    startActivityAndWait { it.putExtra(DemoExtra, true) }
}

/** Waits until the Notes feed, the start tab, shows its first note. */
internal fun MacrobenchmarkScope.waitForNotes() {
    check(device.wait(Until.hasObject(By.textContains(FirstNote)), WaitMs)) {
        "The Notes feed never showed \"$FirstNote\". Is the device set to English, and the app built with demo mode?"
    }
}

/**
 * Scrolls the screen's main list by dragging its middle. Plain swipes rather than a scrollable
 * selector: it works the same for every list, and for the article reader.
 */
internal fun MacrobenchmarkScope.scrollDown(times: Int) {
    val x = device.displayWidth / 2
    val from = (device.displayHeight * 0.72f).toInt()
    val to = (device.displayHeight * 0.28f).toInt()
    repeat(times) {
        device.swipe(x, from, x, to, SwipeSteps)
        device.waitForIdle()
    }
}

private const val SwipeSteps = 20

/** Switches to the bottom navigation tab labelled [label]. */
internal fun MacrobenchmarkScope.openTab(label: String) {
    // The label is also a screen title and may be inside content; the bar is the lowest match.
    val tab = device.findObjects(By.textContains(label)).maxByOrNull { it.visibleCenter.y }
        ?: error("No \"$label\" tab. Is the device set to English?")
    tab.click()
    device.waitForIdle()
}

/** Finds the node matching [selector], scrolling the list down up to [maxScrolls] times to bring it in. */
internal fun MacrobenchmarkScope.findScrolling(selector: BySelector, maxScrolls: Int = 5): UiObject2 {
    repeat(maxScrolls + 1) { attempt ->
        device.wait(Until.findObject(selector), SoftWaitMs)?.let { return it }
        if (attempt < maxScrolls) scrollDown(1)
    }
    error("Not found on screen after scrolling: $selector")
}

/** Taps a text-only note and leaves its thread again. */
internal fun MacrobenchmarkScope.openThreadAndBack() {
    findScrolling(By.textContains(TextNote)).click()
    // The back button's label is localised; if it isn't found the thread is still given time to load.
    device.wait(Until.hasObject(By.desc("Back")), SoftWaitMs)
    device.waitForIdle()
    scrollDown(2)
    device.pressBack()
    device.waitForIdle()
}

/** Opens the author of [TextNote] from the avatar, scrolls the profile and goes back. */
internal fun MacrobenchmarkScope.openProfileAndBack() {
    val avatar = device.wait(Until.findObject(By.desc(TextNoteAuthor)), SoftWaitMs) ?: return
    avatar.click()
    device.waitForIdle()
    scrollDown(2)
    device.pressBack()
    device.waitForIdle()
}

/** Opens the Reader's first article, reads down it and goes back. */
internal fun MacrobenchmarkScope.openArticleAndBack() {
    val card = device.wait(Until.findObject(By.textContains(FirstArticle)), WaitMs)
        ?: error("The Reader never showed an article \"$FirstArticle\".")
    // The card's top is a picture that opens full screen; the title and excerpt below it open the article.
    val bounds = card.visibleBounds
    device.click(bounds.centerX(), bounds.top + (bounds.height() * 0.75f).toInt())
    device.waitForIdle()
    scrollDown(4)
    device.pressBack()
    device.waitForIdle()
}

/** Waits for [text] to show, without failing if it doesn't: the step after it still exercises the screen. */
internal fun MacrobenchmarkScope.waitSoftly(text: String) {
    device.wait(Until.hasObject(By.textContains(text)), SoftWaitMs)
}

/**
 * Lets Android's notification permission dialog, asked once on the first visit to the Notifications
 * tab, not appear over the journey. Before Android 13 there is nothing to grant and the command is a no-op.
 */
internal fun grantNotificationPermission() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.executeShellCommand("pm grant $TargetPackage android.permission.POST_NOTIFICATIONS")
}
