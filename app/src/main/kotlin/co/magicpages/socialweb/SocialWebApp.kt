package co.magicpages.socialweb

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import co.magicpages.socialweb.background.NotificationScheduler
import co.magicpages.socialweb.data.media.ImageLoaders
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SocialWebApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notificationScheduler: NotificationScheduler

    @Inject lateinit var imageLoaders: ImageLoaders

    // WorkManager's own initializer is removed in the manifest so workers get their dependencies from Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    // Images that aren't avatars: see ImageLoaders for how they are cached.
    override fun newImageLoader(context: Context): ImageLoader = imageLoaders.general

    override fun onCreate() {
        super.onCreate()
        notificationScheduler.start()
    }
}
