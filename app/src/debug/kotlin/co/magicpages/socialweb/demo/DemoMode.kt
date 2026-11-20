package co.magicpages.socialweb.demo

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug builds, and the baseline-profile variants, only: runs the whole app offline against made-up
 * content, signed in as a fictional Ghost site, for screenshots, demos and recording the profile. The
 * store release doesn't contain this source set; `app/build.gradle.kts` adds it to `nonMinifiedRelease`
 * and `benchmarkRelease`, which are never published.
 *
 * Start it with an extra on the launch intent:
 *
 * ```
 * adb shell am force-stop co.magicpages.socialweb.debug
 * adb shell am start -n co.magicpages.socialweb.debug/co.magicpages.socialweb.MainActivity --ez demo true
 * ```
 *
 * `--ez demo false` on a running app switches back to the real accounts (the activity restarts either
 * way). The flag lives as long as the process: nothing is saved, and demo mode never reads or writes
 * the real accounts or their credentials.
 *
 * How it works: [DemoModule] adds an HTTP interceptor ([DemoApi]) that answers every request while
 * the flag is on, and an account storage that serves one in-memory account instead of the encrypted
 * file. Everything else in the app runs unchanged. The content is in [DemoContent], its pictures in
 * `app/src/debug/assets/demo/`.
 */
object DemoMode {
    /** The boolean launch-intent extra that turns demo mode on or off. */
    const val EXTRA = "demo"

    private val state = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = state.asStateFlow()

    internal fun set(on: Boolean) {
        state.value = on
    }
}

/**
 * Reads [DemoMode.EXTRA] off the launch intent. A content provider is created before the application
 * and its Hilt graph, so the flag is known before the first screen asks for an account, without
 * touching the production [co.magicpages.socialweb.MainActivity].
 */
class DemoModeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (checkNotNull(context).applicationContext as Application).registerActivityLifecycleCallbacks(Watcher)
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private object Watcher : Application.ActivityLifecycleCallbacks {
        /** A fresh launch decides before the activity creates anything; a restored one keeps the current state. */
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            val intent = activity.intent ?: return
            if (savedInstanceState == null && intent.hasExtra(DemoMode.EXTRA)) {
                DemoMode.set(intent.getBooleanExtra(DemoMode.EXTRA, false))
            }
        }

        /** `am start` on a running app arrives as a new intent (the activity stores it); restart so the other mode's data shows. */
        override fun onActivityResumed(activity: Activity) {
            val intent = activity.intent ?: return
            if (!intent.hasExtra(DemoMode.EXTRA)) return
            val wanted = intent.getBooleanExtra(DemoMode.EXTRA, false)
            if (wanted != DemoMode.enabled.value) {
                DemoMode.set(wanted)
                activity.recreate()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
