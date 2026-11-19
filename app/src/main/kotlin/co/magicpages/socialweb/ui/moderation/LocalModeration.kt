package co.magicpages.socialweb.ui.moderation

import androidx.compose.runtime.staticCompositionLocalOf
import co.magicpages.socialweb.data.activitypub.Moderation

/** Deleting and blocking from any post's or profile's menu; null outside the signed-in screens. */
val LocalModeration = staticCompositionLocalOf<Moderation?> { null }
