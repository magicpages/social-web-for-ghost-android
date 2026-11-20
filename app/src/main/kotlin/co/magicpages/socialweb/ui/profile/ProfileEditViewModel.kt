package co.magicpages.socialweb.ui.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.magicpages.socialweb.data.account.AccountStore
import co.magicpages.socialweb.data.account.Profile as StoredProfile
import co.magicpages.socialweb.data.activitypub.ActivityPubClient
import co.magicpages.socialweb.data.activitypub.ImageRejection
import co.magicpages.socialweb.data.activitypub.ImageRejectedException
import co.magicpages.socialweb.data.activitypub.ImageUploads
import co.magicpages.socialweb.data.activitypub.Profile
import co.magicpages.socialweb.data.activitypub.SocialWebException
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.media.LocalImages
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProfileImageSlot { Avatar, Banner }

enum class ProfileEditFailure { ImageTooLarge, ImageUnsupported, ImageFailed, SaveFailed }

@Immutable
data class ProfileEditState(
    /** The profile as the form opened, to tell whether anything changed. */
    val original: ProfileDraft,
    /** The account's handle as the form opened, for the domain a new username goes with. */
    val originalHandle: String,
    val draft: ProfileDraft,
    /** The pictures being uploaded. */
    val uploading: Set<ProfileImageSlot> = emptySet(),
    val saving: Boolean = false,
    val failure: ProfileEditFailure? = null,
) {
    /** Whether closing would throw away a change. */
    val dirty: Boolean get() = draft.normalized() != original.normalized()

    /** A form with no changes can be saved too: that just closes it, as Ghost's does. */
    val canSave: Boolean get() = draft.isValid && uploading.isEmpty() && !saving

    /** The handle the account gets, when the username changed. */
    val usernameChanged: Boolean get() = draft.username.trim() != original.username.trim()
}

/**
 * The edit-profile form for the signed-in site's own account: its draft, the pictures uploading for
 * it, and saving. Closed while [state] is null. A saved profile is written to the stored account too,
 * so the account menu and composers show it at once.
 */
@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val activityPub: ActivityPubClient,
    private val images: LocalImages,
    private val uploads: ImageUploads,
    private val avatars: AvatarImage,
) : ViewModel() {
    private val _state = MutableStateFlow<ProfileEditState?>(null)
    val state: StateFlow<ProfileEditState?> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once the site has the new profile. */
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val imageJobs = HashMap<ProfileImageSlot, Job>()

    /** Opens the form on [profile], unless it is open already (a rotation keeps the draft). */
    fun begin(profile: Profile) {
        if (_state.value != null) return
        val draft = ProfileDraft.of(profile)
        _state.value = ProfileEditState(original = draft, originalHandle = profile.handle, draft = draft)
    }

    /** Closes the form and drops the draft. Ignored while saving. */
    fun close() {
        if (_state.value?.saving == true) return
        imageJobs.values.forEach { it.cancel() }
        imageJobs.clear()
        _state.value = null
    }

    fun setName(name: String) = edit { it.copy(name = name) }

    fun setUsername(username: String) = edit { it.copy(username = username) }

    fun setBio(bio: String) = edit { it.copy(bio = bio) }

    fun removeImage(slot: ProfileImageSlot) {
        val current = _state.value ?: return
        if (current.saving) return
        imageJobs.remove(slot)?.cancel()
        _state.value = current.copy(uploading = current.uploading - slot, draft = current.draft.withImage(slot, ""))
    }

    /** Uploads the picture the writer picked (a `content://` Uri) and shows it once the site has it. */
    fun pickImage(slot: ProfileImageSlot, uri: String) {
        val current = _state.value ?: return
        if (current.saving) return
        imageJobs[slot]?.cancel()
        _state.value = current.copy(uploading = current.uploading + slot, failure = null)
        imageJobs[slot] = viewModelScope.launch {
            val outcome = upload(slot, uri)
            _state.update { state ->
                if (state == null) return@update null
                val uploading = state.uploading - slot
                when (outcome) {
                    is Upload.Done -> state.copy(uploading = uploading, draft = state.draft.withImage(slot, outcome.url))
                    is Upload.Failed -> state.copy(uploading = uploading, failure = outcome.failure)
                }
            }
        }
    }

    fun save() {
        val current = _state.value ?: return
        if (!current.canSave) return
        if (!current.dirty) {
            close()
            return
        }
        _state.value = current.copy(saving = true, failure = null)
        viewModelScope.launch {
            val failed = try {
                push(current.draft.normalized())
                false
            } catch (_: SocialWebException) {
                true
            } catch (_: GhostException) {
                true
            }
            if (failed) {
                _state.update { it?.copy(saving = false, failure = ProfileEditFailure.SaveFailed) }
            } else {
                _state.value = null
                _saved.emit(Unit)
            }
        }
    }

    private fun edit(change: (ProfileDraft) -> ProfileDraft) {
        _state.update { if (it == null || it.saving) it else it.copy(draft = change(it.draft)) }
    }

    /** Sends the profile and then remembers what the site now says about the account. */
    private suspend fun push(draft: ProfileDraft) {
        val account = accounts.account.first() ?: throw GhostException.Unauthorized(null)
        activityPub.updateAccount(
            account.site,
            account.credential,
            name = draft.name,
            username = draft.username,
            bio = draft.bio,
            avatarUrl = draft.avatarUrl,
            bannerImageUrl = draft.bannerUrl,
        )
        // The save went through: failing to read the result back must not read as a failed save.
        val stored = try {
            activityPub.currentAccount(account.site, account.credential).let { StoredProfile(it.name, it.handle, it.avatarUrl) }
        } catch (_: SocialWebException) {
            localProfile(account.profile, draft)
        } catch (_: GhostException) {
            localProfile(account.profile, draft)
        }
        accounts.updateProfile(account.id, stored)
    }

    private fun localProfile(before: StoredProfile, draft: ProfileDraft) = StoredProfile(
        name = draft.name,
        handle = handleWithUsername(before.handle, draft.username),
        avatarUrl = draft.avatarUrl.ifBlank { null },
    )

    private sealed interface Upload {
        class Done(val url: String) : Upload
        class Failed(val failure: ProfileEditFailure) : Upload
    }

    private suspend fun upload(slot: ProfileImageSlot, uri: String): Upload {
        val account = accounts.account.first() ?: return Upload.Failed(ProfileEditFailure.ImageFailed)
        // A phone photo is far over what the site takes; the avatar is cut down to a square before it goes.
        val limit = if (slot == ProfileImageSlot.Avatar) AvatarSourceMaxBytes else ActivityPubClient.MAX_IMAGE_BYTES
        val loaded = when (val result = images.load(uri, limit)) {
            is LocalImages.Result.Loaded -> result.image
            LocalImages.Result.TooLarge -> return Upload.Failed(ProfileEditFailure.ImageTooLarge)
            LocalImages.Result.UnsupportedType -> return Upload.Failed(ProfileEditFailure.ImageUnsupported)
            LocalImages.Result.Unreadable -> return Upload.Failed(ProfileEditFailure.ImageFailed)
        }
        val file = if (slot == ProfileImageSlot.Avatar) avatars.square(loaded) ?: return Upload.Failed(ProfileEditFailure.ImageFailed) else loaded
        return try {
            Upload.Done(uploads.upload(account.site, account.credential, file.bytes, file.fileName, file.mimeType))
        } catch (e: ImageRejectedException) {
            Upload.Failed(
                when (e.reason) {
                    ImageRejection.TooLarge -> ProfileEditFailure.ImageTooLarge
                    ImageRejection.UnsupportedType -> ProfileEditFailure.ImageUnsupported
                    ImageRejection.Unreadable -> ProfileEditFailure.ImageFailed
                },
            )
        } catch (_: SocialWebException) {
            Upload.Failed(ProfileEditFailure.ImageFailed)
        } catch (_: GhostException) {
            Upload.Failed(ProfileEditFailure.ImageFailed)
        }
    }

    private fun ProfileDraft.withImage(slot: ProfileImageSlot, url: String) = when (slot) {
        ProfileImageSlot.Avatar -> copy(avatarUrl = url)
        ProfileImageSlot.Banner -> copy(bannerUrl = url)
    }

    private companion object {
        const val AvatarSourceMaxBytes = 25L * 1024 * 1024
    }
}
