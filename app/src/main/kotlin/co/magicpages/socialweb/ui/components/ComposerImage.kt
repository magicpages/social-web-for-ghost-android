package co.magicpages.socialweb.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.media.LocalImages
import co.magicpages.socialweb.data.media.LocalImagesEntryPoint
import coil3.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.launch

/** Opens the photo picker for one image; [onPick] gets its `content://` Uri. */
@Composable
fun AddImageButton(enabled: Boolean, onPick: (String) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPick(uri.toString())
    }
    IconButton(
        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = enabled,
    ) {
        Icon(AppIcons.AddPhotoAlternate, contentDescription = stringResource(R.string.note_add_image))
    }
}

/**
 * Takes a photo with the device's camera app (no camera permission needed: the app writes to a cache file
 * this app hands it) and passes it on as a picked image is: copied into the staging cache, so the camera's
 * file can go. A photo too large or unreadable to stage goes on as the capture file itself, so it is turned
 * away at send time with the same message a picked one gets. Hidden when no camera app can take a photo.
 */
@Composable
fun TakePhotoButton(enabled: Boolean, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val available = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null
    }
    if (!available) return
    val images = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, LocalImagesEntryPoint::class.java).localImages()
    }
    val scope = rememberCoroutineScope()
    // Saved, so a photo survives the app being stopped while the camera is open.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val path = pendingPath ?: return@rememberLauncherForActivityResult
        val uri = pendingUri ?: return@rememberLauncherForActivityResult
        pendingPath = null
        pendingUri = null
        val file = File(path)
        if (!taken || file.length() == 0L) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            when (val staged = images.stage(uri, ActivityPubClient.MAX_IMAGE_BYTES)) {
                is LocalImages.Staged.Ready -> {
                    file.delete()
                    onPick(staged.uri)
                }
                is LocalImages.Staged.Rejected -> onPick(uri)
            }
        }
    }
    IconButton(
        onClick = {
            val capture = images.newCapture() ?: return@IconButton
            pendingPath = capture.file.absolutePath
            pendingUri = capture.uri.toString()
            try {
                camera.launch(capture.uri)
            } catch (_: ActivityNotFoundException) {
                capture.file.delete()
                pendingPath = null
                pendingUri = null
            }
        },
        enabled = enabled,
    ) {
        Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.note_take_photo))
    }
}

/** A picked image as it will go out: full width, up to a height. */
@Composable
fun PickedImagePreview(uri: String) {
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
    )
}

/**
 * The image attached to a draft, drawn by [image], with buttons to describe it and to remove it in
 * a row below: laid over the image they would hide part of it, and a quote card has words at every edge.
 * [hasAlt] null hides the description button, for an image that isn't ready yet.
 */
@Composable
fun AttachedImage(
    hasAlt: Boolean?,
    enabled: Boolean,
    onEditAlt: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    image: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)) { image() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            if (hasAlt != null) {
                val altLabel = stringResource(if (hasAlt) R.string.note_alt_edit else R.string.note_alt_add)
                FilledTonalButton(
                    onClick = onEditAlt,
                    enabled = enabled,
                    contentPadding = ButtonDefaults.SmallContentPadding,
                    modifier = Modifier.semantics { contentDescription = altLabel },
                ) {
                    if (hasAlt) {
                        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.SmallIconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(stringResource(R.string.note_alt_badge))
                }
            }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onClick = onRemove, enabled = enabled) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.note_remove_image))
            }
        }
    }
}

/** Writing the description of an attached image, for people who can't see it. */
@Composable
fun AltTextDialog(altText: String, onAltTextChange: (String) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.note_alt_title)) },
        text = {
            OutlinedTextField(
                value = altText,
                onValueChange = onAltTextChange,
                placeholder = { Text(stringResource(R.string.note_alt_hint)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.note_alt_done)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
