package co.magicpages.socialweb.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.magicpages.socialweb.R
import co.magicpages.socialweb.ui.components.AppIcons
import co.magicpages.socialweb.ui.components.ComposerDialog
import co.magicpages.socialweb.ui.components.ComposerFailure
import co.magicpages.socialweb.ui.theme.Gutter
import coil3.compose.AsyncImage

private val EditBannerHeight = 140.dp
private val EditAvatarSize = 88.dp

/**
 * The full-screen form for the site's own profile: cover image, profile picture, display name,
 * username and bio, with Save in the top bar. Closing with changes asks first.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileEditDialog(state: ProfileEditState, viewModel: ProfileEditViewModel) {
    val draft = state.draft
    val enabled = !state.saving
    val pickAvatar = rememberImagePicker { viewModel.pickImage(ProfileImageSlot.Avatar, it) }
    val pickBanner = rememberImagePicker { viewModel.pickImage(ProfileImageSlot.Banner, it) }
    ComposerDialog(
        title = stringResource(R.string.profile_edit_title),
        sendLabel = stringResource(R.string.profile_edit_save),
        discardTitle = stringResource(R.string.profile_edit_discard_title),
        canSend = state.canSave,
        sending = state.saving,
        hasDraft = state.dirty,
        onSend = viewModel::save,
        onDiscard = viewModel::close,
    ) { padding: PaddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            state.failure?.let { failure ->
                ComposerFailure(failureMessage(failure), Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            }
            Box(Modifier.fillMaxWidth().height(EditBannerHeight + EditAvatarSize / 2 + 4.dp)) {
                EditBanner(
                    url = draft.bannerUrl,
                    uploading = ProfileImageSlot.Banner in state.uploading,
                    enabled = enabled,
                    onPick = pickBanner,
                    onRemove = { viewModel.removeImage(ProfileImageSlot.Banner) },
                )
                EditAvatar(
                    url = draft.avatarUrl,
                    name = draft.name,
                    uploading = ProfileImageSlot.Avatar in state.uploading,
                    enabled = enabled,
                    onPick = pickAvatar,
                    onRemove = { viewModel.removeImage(ProfileImageSlot.Avatar) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = Gutter),
                )
            }
            Column(
                Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.profile_edit_name)) },
                    singleLine = true,
                    enabled = enabled,
                    isError = draft.nameError != null,
                    supportingText = { Text(draft.nameError?.let { fieldMessage(it) } ?: stringResource(R.string.profile_edit_name_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                val handle = state.handleAfterEdit
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = viewModel::setUsername,
                    label = { Text(stringResource(R.string.profile_edit_username)) },
                    prefix = { Text("@") },
                    singleLine = true,
                    enabled = enabled,
                    isError = draft.usernameError != null,
                    supportingText = {
                        Text(
                            draft.usernameError?.let { fieldMessage(it) }
                                ?: if (state.usernameChanged) {
                                    stringResource(R.string.profile_edit_username_change, handle)
                                } else {
                                    stringResource(R.string.profile_edit_username_hint, handle)
                                },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.bio,
                    onValueChange = viewModel::setBio,
                    label = { Text(stringResource(R.string.profile_edit_bio)) },
                    minLines = 3,
                    maxLines = 8,
                    enabled = enabled,
                    isError = draft.bioError != null,
                    supportingText = {
                        Text(
                            draft.bioError?.let { fieldMessage(it) }
                                ?: stringResource(R.string.profile_edit_bio_counter, draft.bio.trim().length, BioMaxLength),
                        )
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The handle as it reads once the username in the form is saved. */
private val ProfileEditState.handleAfterEdit: String
    get() = handleWithUsername(originalHandle, draft.username)

@Composable
private fun rememberImagePicker(onPick: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPick(uri.toString())
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
private fun EditBanner(url: String, uploading: Boolean, enabled: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))
    val change = stringResource(R.string.profile_edit_banner_change)
    Box(
        Modifier
            .fillMaxWidth()
            .height(EditBannerHeight)
            .background(gradient)
            .clickable(enabled = enabled && !uploading, onClickLabel = change, role = Role.Button, onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.image_banner),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!uploading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(AppIcons.AddPhotoAlternate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    stringResource(R.string.profile_edit_banner_add),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (uploading) UploadingScrim()
        if (url.isNotBlank() && !uploading) {
            FilledTonalIconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.profile_edit_banner_remove))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditAvatar(
    url: String,
    name: String,
    uploading: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val change = stringResource(R.string.profile_edit_avatar_change)
    Box(modifier) {
        Box(
            Modifier
                .size(EditAvatarSize + 8.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(EditAvatarSize)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .semantics { contentDescription = change }
                    .clickable(enabled = enabled && !uploading, onClickLabel = change, role = Role.Button, onClick = onPick),
                contentAlignment = Alignment.Center,
            ) {
                if (url.isNotBlank()) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val initial = name.trimStart('@').firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
                    if (initial != null) {
                        Text(initial.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (uploading) {
                    UploadingScrim()
                } else {
                    // A hint that the picture can be changed, small enough to leave the face visible.
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(28.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    }
                }
            }
        }
        if (url.isNotBlank() && !uploading) {
            FilledTonalIconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.profile_edit_avatar_remove))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UploadingScrim() {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(40.dp))
    }
}

@Composable
private fun failureMessage(failure: ProfileEditFailure): String = stringResource(
    when (failure) {
        ProfileEditFailure.ImageTooLarge -> R.string.note_image_too_large
        ProfileEditFailure.ImageUnsupported -> R.string.note_image_unsupported
        ProfileEditFailure.ImageFailed -> R.string.profile_edit_image_failed
        ProfileEditFailure.SaveFailed -> R.string.profile_edit_save_failed
    },
)

@Composable
private fun fieldMessage(error: ProfileFieldError): String = when (error) {
    ProfileFieldError.NameRequired -> stringResource(R.string.profile_error_name_required)
    ProfileFieldError.NameTooLong -> stringResource(R.string.profile_error_name_long, NameMaxLength)
    ProfileFieldError.BioTooLong -> stringResource(R.string.profile_error_bio_long, BioMaxLength)
    ProfileFieldError.UsernameTooShort -> stringResource(R.string.profile_error_username_short, UsernameMinLength)
    ProfileFieldError.UsernameTooLong -> stringResource(R.string.profile_error_username_long, UsernameMaxLength)
    ProfileFieldError.UsernameInvalid -> stringResource(R.string.profile_error_username_invalid)
}
