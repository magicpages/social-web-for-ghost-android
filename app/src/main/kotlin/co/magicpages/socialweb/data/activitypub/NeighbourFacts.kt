package co.magicpages.socialweb.data.activitypub

import co.magicpages.socialweb.data.account.StoredAccount
import co.magicpages.socialweb.data.ghost.GhostException
import co.magicpages.socialweb.data.ghost.GhostSiteClient
import co.magicpages.socialweb.data.ghost.isSameSite
import co.magicpages.socialweb.data.reach.DayMillis
import co.magicpages.socialweb.data.reach.timestampMillis
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How often an account publishes, as far as its recent posts show. */
enum class PublishingRhythm { Daily, SeveralTimesAWeek, Weekly, EveryTwoWeeks, Monthly }

/** What a neighbour's recent posts say: the title of their newest article, and how often they publish. */
data class NeighbourActivity(val latestArticleTitle: String?, val rhythm: PublishingRhythm?)

/**
 * The rhythm [posts] (newest first, as the service lists them) show, or null when they don't show one:
 * fewer than [MinPostsForRhythm] dated posts of the account's own, the newest one older than
 * [StaleAfterDays] (a pace they've dropped isn't theirs anymore), or slower than monthly. Articles are what a
 * writer publishes, so they set the pace when there are enough of them; notes alone do for accounts without.
 * The middle gap between posts is used, so one burst or one long break doesn't move it.
 */
fun publishingRhythm(posts: List<Post>, nowMillis: Long): PublishingRhythm? {
    val own = posts.filter { it.repostedBy == null && !it.isDeleted }
    val articles = own.filter { it.isArticle }
    val basis = if (articles.size >= MinPostsForRhythm) articles else own
    val times = basis.mapNotNull { timestampMillis(it.publishedAt) }.sortedDescending().take(RhythmSample)
    if (times.size < MinPostsForRhythm) return null
    if (nowMillis - times.first() > StaleAfterDays * DayMillis) return null
    val gaps = times.zipWithNext { newer, older -> (newer - older).toDouble() / DayMillis }.sorted()
    val middle = if (gaps.size % 2 == 1) gaps[gaps.size / 2] else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2
    return when {
        middle < 1.5 -> PublishingRhythm.Daily
        middle <= 4 -> PublishingRhythm.SeveralTimesAWeek
        middle <= 10 -> PublishingRhythm.Weekly
        middle <= 20 -> PublishingRhythm.EveryTwoWeeks
        middle <= 45 -> PublishingRhythm.Monthly
        else -> null
    }
}

private const val MinPostsForRhythm = 3
private const val RhythmSample = 10
private const val StaleAfterDays = 60

/**
 * The facts the Neighbours tab shows about an account, each read once and kept for the app's session, or for
 * the day where it comes from another site: what [activity] and [recommends] answer don't change by the minute,
 * and the tab only asks as cards come into view.
 */
@Singleton
class NeighbourFacts @Inject constructor(
    private val activityPub: ActivityPubClient,
    private val ghostSites: GhostSiteClient,
) {
    private val activities = ConcurrentHashMap<String, NeighbourActivity>()
    private val recommendationFiles = ConcurrentHashMap<String, RecommendationFile>()

    // Up to 20 cards come into view together; a few requests at a time are kinder to their sites and to the phone.
    private val gate = Semaphore(MaxParallel)

    /**
     * What the newest posts of [handle] say, from one request. Null when they can't be read; that isn't kept, so
     * the next ask tries again.
     */
    suspend fun activity(account: StoredAccount, handle: String): NeighbourActivity? {
        val key = handle.lowercase()
        activities[key]?.let { return it }
        val posts = gate.withPermit {
            try {
                activityPub.postsBy(account.site, account.credential, handle).posts
            } catch (_: SocialWebException) {
                return null
            } catch (_: GhostException) {
                return null
            }
        }
        val own = posts.filter { it.repostedBy == null && !it.isDeleted }
        return NeighbourActivity(
            latestArticleTitle = own.firstOrNull { it.isArticle && it.title.isNotBlank() }?.title?.trim(),
            rhythm = publishingRhythm(posts, System.currentTimeMillis()),
        ).also { activities[key] = it }
    }

    /**
     * Whether the Ghost site at [theirSiteUrl] lists [mySiteUrl] in its `recommendations.json`. Null when the file
     * can't be read, which says nothing either way. Their file is read once a day.
     */
    suspend fun recommends(theirSiteUrl: String, mySiteUrl: String): Boolean? {
        val today = LocalDate.now().toEpochDay()
        val key = theirSiteUrl.lowercase()
        val cached = recommendationFiles[key]?.takeIf { it.day == today }
        val urls = cached?.urls ?: gate.withPermit {
            try {
                ghostSites.recommendedUrls(theirSiteUrl)
            } catch (_: IOException) {
                return null
            }
        }.also { recommendationFiles[key] = RecommendationFile(today, it) }
        return urls.any { isSameSite(it, mySiteUrl) }
    }

    private class RecommendationFile(val day: Long, val urls: List<String>)

    private companion object {
        const val MaxParallel = 4
    }
}
