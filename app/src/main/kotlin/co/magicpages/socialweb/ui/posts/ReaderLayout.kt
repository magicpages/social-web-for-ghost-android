package co.magicpages.socialweb.ui.posts

import androidx.compose.runtime.Immutable

/**
 * How an article shows in the Reader, like a news or magazine app rather than a social timeline:
 * no excerpt and no action buttons (the article's screen has them, and so does a long press).
 */
enum class ReaderStyle {
    /** The cover filling the card, the publication and the title over its foot. */
    Prominent,

    /** The publication and the title, the cover small and square beside them. */
    Compact,
}

/** What one list item of the Reader draws: an article on its own, or two side by side. */
@Immutable
sealed interface ReaderBlock {
    /** One article across the column, at its [style]. */
    data class Single(val item: FeedItem, val style: ReaderStyle) : ReaderBlock

    /** Two tall cards side by side. */
    data class TallPair(val left: FeedItem, val right: FeedItem) : ReaderBlock
}

/**
 * The Reader's articles laid out. The list keeps one item per post, so positions, keys and paging
 * stay those of the posts: a block draws in the item of its first post ([blocks]), and the second
 * post of a pair ([absorbed]) draws nothing in its own. Posts in neither are not articles, shown as rows.
 */
@Immutable
class ReaderMagazine(val blocks: Map<String, ReaderBlock>, val absorbed: Set<String>)

/** Lays out the Reader as a magazine, by the iOS app's rules. */
object ReaderLayout {
    /** Interaction counted as likes, with a repost (sharing it with followers) worth two, and a reply one. */
    fun score(item: FeedItem): Int = item.post.run { likeCount + 2 * repostCount + replyCount }

    /** Articles this lays out: the rest (notes, deleted posts) stay rows. */
    fun isMagazineArticle(item: FeedItem): Boolean = item.post.isArticle && !item.post.isDeleted

    private val FeedItem.hasCover: Boolean get() = !post.featureImageUrl.isNullOrBlank()

    /**
     * Each article's style: prominent when it has a cover and its score is in the top third of the
     * first page's articles with a cover (at least 1), and for the first article with a cover, as the
     * lead story; compact otherwise. The bar comes from the first page only, so loading more doesn't
     * change the cards already shown.
     */
    fun styles(items: List<FeedItem>): Map<String, ReaderStyle> {
        val articles = items.filter(::isMagazineArticle)
        val firstPage = items.take(FirstPage).filter { isMagazineArticle(it) && it.hasCover }.map(::score).sortedDescending()
        val bar = if (firstPage.isEmpty()) Int.MAX_VALUE else maxOf(1, firstPage[(firstPage.size - 1) / 3])
        val lead = articles.firstOrNull { it.hasCover }?.post?.id
        return articles.associate { item ->
            val prominent = item.hasCover && (item.post.id == lead || score(item) >= bar)
            item.post.id to if (prominent) ReaderStyle.Prominent else ReaderStyle.Compact
        }
    }

    /** [items] laid out, keyed by the post each block draws at; with [pairs] off, every article on its own. */
    fun arrange(items: List<FeedItem>, pairs: Boolean = true): ReaderMagazine {
        val blocks = LinkedHashMap<String, ReaderBlock>()
        val absorbed = HashSet<String>()
        for (block in blocks(items, styles(items), pairs)) {
            when (block) {
                is ReaderBlock.Single -> blocks[block.item.post.id] = block
                is ReaderBlock.TallPair -> {
                    blocks[block.left.post.id] = block
                    absorbed += block.right.post.id
                }
            }
        }
        return ReaderMagazine(blocks, absorbed)
    }

    /**
     * Each article on its own, except that in a run of compact articles with a cover, the third and
     * fourth (and the seventh and eighth…) are a pair of tall cards, unless [pairs] is off. Anything
     * else ends the run.
     */
    fun blocks(items: List<FeedItem>, styles: Map<String, ReaderStyle>, pairs: Boolean = true): List<ReaderBlock> {
        fun pairable(item: FeedItem) = pairs && styles[item.post.id] == ReaderStyle.Compact && item.hasCover
        val blocks = mutableListOf<ReaderBlock>()
        var run = 0
        var i = 0
        while (i < items.size) {
            val item = items[i]
            val style = styles[item.post.id]
            when {
                style == null -> run = 0
                !pairable(item) -> {
                    blocks += ReaderBlock.Single(item, style)
                    run = 0
                }
                run % 4 == 2 && i + 1 < items.size && pairable(items[i + 1]) -> {
                    blocks += ReaderBlock.TallPair(item, items[i + 1])
                    run += 2
                    i++
                }
                else -> {
                    blocks += ReaderBlock.Single(item, style)
                    run++
                }
            }
            i++
        }
        return blocks
    }

    /** How many posts make the first page, whose scores set the bar for prominent cards. */
    private const val FirstPage = 20
}
