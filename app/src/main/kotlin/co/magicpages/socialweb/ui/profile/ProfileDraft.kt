package co.magicpages.socialweb.ui.profile

import co.magicpages.socialweb.data.activitypub.Profile
import co.magicpages.socialweb.ui.quote.htmlToPlainText

/** The limits Ghost's own web client puts on a profile (edit-profile.tsx and utils/social-web-handle.ts). */
const val NameMaxLength = 64
const val BioMaxLength = 250
const val UsernameMinLength = 2

/** Ghost's rule is "fewer than 100". */
const val UsernameMaxLength = 99

private val UsernamePattern = Regex("^[a-zA-Z0-9_]+$")

enum class ProfileFieldError { NameRequired, NameTooLong, BioTooLong, UsernameTooShort, UsernameTooLong, UsernameInvalid }

/**
 * What the edit-profile form holds. The pictures are the addresses of images already uploaded to the
 * site, or "" for none. Compare drafts through [normalized] so stray spaces don't count as a change.
 */
data class ProfileDraft(
    val name: String,
    val username: String,
    val bio: String,
    val avatarUrl: String,
    val bannerUrl: String,
) {
    val nameError: ProfileFieldError?
        get() = name.trim().let {
            when {
                it.isEmpty() -> ProfileFieldError.NameRequired
                it.length > NameMaxLength -> ProfileFieldError.NameTooLong
                else -> null
            }
        }

    val usernameError: ProfileFieldError?
        get() = username.trim().let {
            when {
                it.length < UsernameMinLength -> ProfileFieldError.UsernameTooShort
                it.length > UsernameMaxLength -> ProfileFieldError.UsernameTooLong
                !UsernamePattern.matches(it) -> ProfileFieldError.UsernameInvalid
                else -> null
            }
        }

    val bioError: ProfileFieldError?
        get() = if (bio.trim().length > BioMaxLength) ProfileFieldError.BioTooLong else null

    val isValid: Boolean get() = nameError == null && usernameError == null && bioError == null

    /** The draft as it is sent: no leading or trailing spaces on the text fields. */
    fun normalized() = copy(name = name.trim(), username = username.trim(), bio = bio.trim())

    companion object {
        /** The form for [profile], the site's own account. The service sends the bio as HTML; the form edits plain text, as Ghost's does. */
        fun of(profile: Profile) = ProfileDraft(
            name = profile.name,
            username = usernameOf(profile.handle),
            bio = if (profile.bio.isBlank()) "" else htmlToPlainText(profile.bio),
            avatarUrl = profile.avatarUrl.orEmpty(),
            bannerUrl = profile.bannerImageUrl.orEmpty(),
        )
    }
}

/** `user` of `@user@domain`; the whole text when it has no domain. */
fun usernameOf(handle: String): String = handle.trim().removePrefix("@").substringBefore('@')

/** `domain` of `@user@domain`; empty when there is none. */
fun domainOf(handle: String): String = handle.trim().removePrefix("@").substringAfter('@', "")

/** [handle] with its username replaced, e.g. what the account's handle becomes when its username is [username]. */
fun handleWithUsername(handle: String, username: String): String = "@${username.trim()}@${domainOf(handle)}"
