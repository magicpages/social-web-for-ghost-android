package co.magicpages.socialweb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.magicpages.socialweb.data.media.ImageLoadersEntryPoint
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors

/**
 * The first letter of [name] on a tonal background, covered by the image once it loads. Accounts
 * without a picture (the service sends "") or with a broken one still get a recognisable avatar.
 * Profile photos are cached apart from other images, see [co.magicpages.socialweb.data.media.ImageLoaders].
 */
@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trimStart('@').firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        if (initial != null) {
            // Decorative: whatever shows the avatar names the account.
            Text(
                initial.toString(),
                modifier = Modifier.clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                imageLoader = rememberAvatarLoader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun rememberAvatarLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, ImageLoadersEntryPoint::class.java).imageLoaders().avatars
    }
}
