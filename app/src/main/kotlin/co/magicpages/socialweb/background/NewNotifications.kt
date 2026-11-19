package co.magicpages.socialweb.background

import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.activitypub.Notification

/**
 * The notifications of a newest-first page that arrived after the one with id [marker]. Ids are
 * the service's autoincrement row ids, so they compare numerically; anything else falls back to
 * "everything above the marker".
 */
internal fun List<Notification>.newerThan(marker: String): List<Notification> {
    val markerNumber = marker.toLongOrNull()
    return takeWhile { notification ->
        val number = notification.id.toLongOrNull()
        if (markerNumber != null && number != null) number > markerNumber else notification.id != marker
    }
}

/**
 * What one background check does with a newest-first page.
 *
 * @property marker the id to store as "seen", or null to leave the stored one alone.
 * @property announce the notifications to post as system notifications, newest first.
 */
internal data class CheckOutcome(val marker: String?, val announce: List<Notification>)

/**
 * Decides a background check. The marker advances over every notification that arrived, whatever
 * its type, so switching a type back on later never announces old ones. Only types in [announced]
 * are announced. Without a [marker] (the first check for an account) everything so far is old news.
 */
internal fun List<Notification>.check(marker: String?, announced: Set<Notification.Type>): CheckOutcome {
    if (marker == null) return CheckOutcome(marker = firstOrNull()?.id ?: "0", announce = emptyList())
    val newer = newerThan(marker)
    if (newer.isEmpty()) return CheckOutcome(marker = null, announce = emptyList())
    return CheckOutcome(
        marker = newer.first().id,
        announce = newer.filter { it.type != Notification.Type.Unknown && it.type in announced },
    )
}

/**
 * One account's background check of its newest-first page: decides with the account's own marker
 * and advances that marker. Accounts never see each other's markers, so one account's new
 * notifications neither hide nor reveal another's.
 *
 * The marker moves before anything is announced, so a crash while posting can't announce the same
 * items twice.
 */
internal fun List<Notification>.checkFor(
    account: StoredAccount,
    markers: LastSeenMarkers,
    announced: Set<Notification.Type>,
): CheckOutcome {
    val outcome = check(markers.get(account), announced)
    outcome.marker?.let { markers.set(account, it) }
    return outcome
}
