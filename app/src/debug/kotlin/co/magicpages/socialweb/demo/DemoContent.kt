package co.magicpages.socialweb.demo

import co.magicpages.socialweb.data.activitypub.Attachment
import co.magicpages.socialweb.data.activitypub.Author
import co.magicpages.socialweb.data.activitypub.Post

/**
 * The fictional world of the demo mode: every person and publication is made up. The signed-in site
 * is Seaside Notes, the sample site of Magic Pages' feature pages and help screenshots, on
 * `seasidenotes.com` (a domain Magic Pages holds); everyone else uses the reserved `.example` TLD.
 * Pictures are the placeholders in `assets/demo/`, served by [DemoApi] under [image]. Content is
 * English only.
 *
 * Posts are built here without a time ([publishedAt] is set per request from [ageMinutes], so
 * "14m" stays "14m" however long the app runs) and without per-session state (likes, follows):
 * [DemoApi] layers both on top.
 */
internal object DemoContent {
    private const val ImageBase = "https://demo.example/images/"

    /** The address of the bundled placeholder `assets/demo/<name>.webp`. */
    fun image(name: String) = "$ImageBase$name.webp"

    /** The asset name behind [url] when it is one of [image]'s addresses. */
    fun imageName(url: String): String? = url.removePrefix(ImageBase).takeIf { it != url }?.removeSuffix(".webp")

    class Persona(
        val key: String,
        val name: String,
        val user: String,
        val domain: String,
        val bio: String,
        val isGhost: Boolean = false,
        val followedByMe: Boolean = true,
        val followers: Int = 2_310,
        val following: Int = 180,
        val posts: Int = 342,
        val banner: String = "banner-default",
    ) {
        val handle = "@$user@$domain"

        /** Ghost sites' social web accounts live under `/.ghost/activitypub/`; other servers use the usual `users/` path. */
        val apId = if (isGhost) "https://$domain/.ghost/activitypub/users/$user" else "https://$domain/users/$user"
        val url = if (isGhost) "https://$domain/" else "https://$domain/@$user"
        val avatar = image("avatar-$key")
        val bannerUrl = image(banner)

        fun author(followedByMe: Boolean = this.followedByMe) =
            Author(id = apId, handle = handle, name = name, avatarUrl = avatar, url = url, followedByMe = followedByMe)

        /** The mention link the social web service writes into a note's HTML. */
        fun mention() =
            """<a href="$url" data-profile="$handle" class="u-url mention">@<span>${if (user == "index") domain else user}</span></a>"""
    }

    // The signed-in site.
    val me = Persona(
        key = "seaside", name = "Seaside Notes", user = "index", domain = "seasidenotes.com",
        bio = "<p>A letter from a small harbour town: the ferry, the weather, the gardens and the people who keep it all going. Every Sunday morning.</p>",
        isGhost = true, followedByMe = false, followers = 4_812, following = 236, posts = 186, banner = "banner-seaside",
    )
    val elena = Persona(
        key = "elena", name = "Elena Marsh", user = "elena", domain = "elenamarsh.example",
        bio = "<p>Landscape photographer. Early mornings, high places, long exposures. Prints and workshops on my site.</p>",
        isGhost = true, followedByMe = false, followers = 12_480, following = 312, posts = 864, banner = "banner-elena",
    )
    val kofi = Persona(
        key = "kofi", name = "Kofi Mensah", user = "kofi", domain = "urbanist.example",
        bio = "<p>Urbanist. Writing about streets, benches, buses and the people who use them.</p>", banner = "banner-kofi",
    )
    val ines = Persona(
        key = "ines", name = "Inês Duarte", user = "ines", domain = "fieldnotes.example",
        bio = "<p>Travel writer. Field notes from coastlines and small towns.</p>", isGhost = true,
    )
    val sam = Persona(
        key = "sam", name = "Sam Okafor", user = "sam", domain = "screentalk.example",
        bio = "<p>Film and TV, minus the hot takes.</p>",
    )
    val hana = Persona(
        key = "hana", name = "Hana Sato", user = "hana", domain = "pixelfed.example",
        bio = "<p>Street photography after dark. Tokyo, mostly.</p>",
    )
    val kerning = Persona(
        key = "kerning", name = "The Kerning", user = "index", domain = "thekerning.example",
        bio = "<p>A weekly newsletter about type, layout and the craft of reading.</p>", isGhost = true,
    )
    val byteSized = Persona(
        key = "bytesized", name = "Byte Sized", user = "index", domain = "bytesized.example",
        bio = "<p>Small tools, big ideas. A newsletter for people who make software.</p>", isGhost = true,
    )
    val harbour = Persona(
        key = "harbour", name = "Harbour Gazette", user = "index", domain = "harbourgazette.example",
        bio = "<p>Independent local news for the harbour towns.</p>", isGhost = true,
    )
    val openKitchen = Persona(
        key = "openkitchen", name = "Open Kitchen", user = "index", domain = "openkitchen.example",
        bio = "<p>Home cooking, honestly. New recipes every Thursday.</p>", isGhost = true,
    )
    val priya = Persona(key = "priya", name = "Priya Raman", user = "priya", domain = "mastodon.example", bio = "", followedByMe = false)
    val lucas = Persona(key = "lucas", name = "Lucas Weber", user = "lucas", domain = "social.example", bio = "", followedByMe = false)
    val noor = Persona(key = "noor", name = "Noor Haddad", user = "noor", domain = "mas.example", bio = "", followedByMe = false)

    val everyone = listOf(me, elena, kofi, ines, sam, hana, kerning, byteSized, harbour, openKitchen, priya, lucas, noor)
    val byHandle = everyone.associateBy { it.handle }
    val byDomain = everyone.associateBy { it.domain }

    /** The accent colour the demo site's Ghost Admin reports; it themes the app. */
    const val AccentColor = "#0b7285"

    // MARK: Posts

    private val ages = HashMap<String, Long>()
    private val all = LinkedHashMap<String, Post>()

    /** Every post, by id. */
    val posts: Map<String, Post> get() = all

    /** Minutes before the app started that [postId] was published. */
    fun ageMinutes(postId: String): Long = ages[postId] ?: 0

    /** Replies by parent id, in the order they are shown. */
    val replies = HashMap<String, List<String>>()

    private fun noteId(by: Persona, key: String) =
        if (by.isGhost) "https://${by.domain}/.ghost/activitypub/note/$key" else "https://${by.domain}/notes/$key"

    private fun photo(name: String, alt: String, width: Int, height: Int) =
        Attachment(type = "Image", mediaType = "image/webp", name = alt, url = image(name), width = width, height = height)

    private fun note(
        key: String,
        by: Persona,
        minutesAgo: Long,
        html: String,
        images: List<Attachment> = emptyList(),
        likes: Int = 0,
        reposts: Int = 0,
        replies: Int = 0,
        liked: Boolean = false,
        reposted: Boolean = false,
        warning: String? = null,
    ): Post {
        val id = noteId(by, key)
        ages[id] = minutesAgo
        return Post(
            id = id, content = html, url = id, sensitive = warning != null, contentWarning = warning,
            likeCount = likes, likedByMe = liked, replyCount = replies, attachments = images,
            author = by.author(), authoredByMe = by === me, repostCount = reposts, repostedByMe = reposted,
        ).also { all[id] = it }
    }

    private fun article(
        slug: String,
        by: Persona,
        minutesAgo: Long,
        title: String,
        excerpt: String,
        cover: String,
        body: String,
        likes: Int,
        reposts: Int,
        replies: Int,
        liked: Boolean = false,
    ): Post {
        val url = "https://${by.domain}/$slug/"
        ages[url] = minutesAgo
        // About 275 words a minute, like the app's own estimate.
        val minutes = (body.replace(Regex("<[^>]+>"), " ").split(Regex("\\s+")).size / 275).coerceAtLeast(1)
        return Post(
            id = url, type = Post.TYPE_ARTICLE, title = title, excerpt = excerpt, summary = excerpt, content = body, url = url,
            featureImageUrl = image(cover), likeCount = likes, likedByMe = liked, replyCount = replies, readingTimeMinutes = minutes,
            author = by.author(), authoredByMe = by === me, repostCount = reposts,
        ).also { all[url] = it }
    }

    private fun tag(name: String, on: Persona) = """<a href="https://${on.domain}/tags/$name" rel="tag">#$name</a>"""

    private fun figure(name: String, alt: String, caption: String?, width: Int, height: Int, wide: Boolean = false) =
        """<figure class="kg-card kg-image-card${if (caption != null) " kg-card-hascaption" else ""}${if (wide) " kg-width-wide" else ""}">""" +
            """<img src="${image(name)}" class="kg-image" alt="$alt" loading="lazy" width="$width" height="$height">""" +
            (caption?.let { "<figcaption>$it</figcaption>" } ?: "") + "</figure>"

    private fun bookmark(url: String, title: String, description: String, site: String, author: String, icon: String, thumbnail: String) =
        """<figure class="kg-card kg-bookmark-card"><a class="kg-bookmark-container" href="$url">""" +
            """<div class="kg-bookmark-content"><div class="kg-bookmark-title">$title</div>""" +
            """<div class="kg-bookmark-description">$description</div>""" +
            """<div class="kg-bookmark-metadata"><img class="kg-bookmark-icon" src="${image(icon)}" alt=""><span class="kg-bookmark-author">$site</span>""" +
            """<span class="kg-bookmark-publisher">$author</span></div></div>""" +
            """<div class="kg-bookmark-thumbnail"><img src="${image(thumbnail)}" alt=""></div></a></figure>"""

    /** Ridge, bench, coast and the rest: the notes the Notes feed shows, in feed order below. */
    val ridge = note(
        "ridge", elena, 14,
        "<p>Up at 4:40 for this. The ridge caught fire for about ninety seconds, then the clouds rolled back in. Worth every step of the climb.</p>",
        listOf(photo("note-ridge", "A mountain ridge glowing orange at sunrise above a sea of clouds", 1200, 800)),
        likes = 214, reposts = 38, replies = 3, liked = true, reposted = true,
    )
    val bench = note(
        "bench", kofi, 31,
        "<p>Unpopular opinion: the most important piece of urban design is a bench in the shade. Cities that get this right tend to get everything else right too.</p>" +
            "<p>${tag("urbanism", kofi)} ${tag("cities", kofi)}</p>",
        likes = 87, reposts = 21, replies = 34,
    )
    val coast = note(
        "coast", ines, 82,
        "<p>Three days on the coast path, 61 km, one very good fish stew. My favourite frames:</p>",
        listOf(
            photo("note-coast-1", "Sea cliffs above turquoise water", 800, 600),
            photo("note-coast-2", "A whitewashed village on a hillside by the sea", 800, 600),
            photo("note-coast-3", "Fishing boats in a small harbour", 800, 600),
            photo("note-coast-4", "The sun setting over a calm sea", 800, 600),
        ),
        likes = 342, reposts = 61, replies = 28,
    )
    val huts = note(
        "huts", sam, 100,
        """<p>The best hut-to-hut guide I have read this year, and it is free: <a href="https://alpinelight.example/winter-hut-guide/">alpinelight.example/winter-hut-guide</a></p>""",
        likes = 52, reposts = 16, replies = 5,
    )
    val rain = note(
        "rain", hana, 120,
        "<p>Rain in Shinjuku tonight. The whole city turns into a mirror.</p>",
        listOf(
            photo("note-rain-1", "A neon-lit street at night, reflected in the rain", 900, 600),
            photo("note-rain-2", "A quiet alley lit by lanterns", 900, 600),
        ),
        likes = 198, reposts = 33, replies = 12, liked = true,
    )
    val finale = note(
        "finale", sam, 170,
        "<p>That finale. I did not see the lighthouse twist coming, and I love that the show trusted us to put it together ourselves.</p>",
        likes = 64, replies = 19, warning = "Spoilers: Harbour Lights season finale",
    )
    val winterFerries = note(
        "winter-ferries", me, 240,
        "<p>This Sunday's letter: winter ferries, explained. From November the crossing runs twice a day, not six times. Thank you ${ines.mention()} for the photos from the harbour wall.</p>",
        likes = 56, reposts = 14, replies = 2,
    )
    val tools = note(
        "tools", byteSized, 300,
        "<p>We tried 23 note-taking apps so you don't have to. The winner is still the one you actually open.</p>",
        likes = 121, reposts = 27, replies = 40,
    )

    // Notes by people that only show on their profiles.
    val storm = note(
        "storm", me, 1_440,
        "<p>Down at the harbour at six, before the first ferry had left. The nets were still knotted on the wall from the storm.</p>",
        likes = 48, replies = 6,
    )
    val desk = note(
        "desk", me, 4_320,
        "<p>This week's office: the window seat at the harbour café. Sunday's letter is coming together nicely.</p>",
        listOf(photo("note-desk", "A cup of coffee and an open notebook on a café table", 800, 600)),
        likes = 77, reposts = 6, replies = 11,
    )
    val dusk = note(
        "dusk", elena, 2_900,
        "<p>New print in the shop: the range at dusk, printed on cotton rag. Only 30 of them.</p>",
        listOf(photo("note-dusk", "A mountain range in blue dusk light", 1000, 640)),
        likes = 302, reposts = 44, replies = 21, liked = true,
    )
    /** The note behind the mention notification. */
    val samMention = note(
        "mention", sam, 400,
        "<p>Lovely letter from ${me.mention()} this week. I had no idea the ferry ran six times a day.</p>",
        likes = 9, replies = 0,
    )
    val workshop = note(
        "workshop", elena, 7_000,
        "<p>Spring workshop dates are up. Small groups, early starts, the best light of the year.</p>",
        likes = 129, reposts = 17, replies = 9,
    )

    // MARK: Articles

    val serif = article(
        "newsletters-serif", kerning, 55,
        "Why every newsletter is quietly switching to serif",
        "Screens got sharper, inboxes got louder, and a 500-year-old idea about reading made a comeback.",
        "article-kerning",
        "<p>Open almost any newsletter you subscribed to this year and you will notice something: the headline is set in a serif. Not a showy one, either. A calm, slightly old-fashioned face with sturdy strokes and a little warmth in its curves.</p>" +
            "<h2>Screens finally caught up</h2>" +
            "<p>For two decades the advice was to use sans-serif type on screens, because low-resolution displays chewed up the fine details of serifs. That reason is gone. Phones now draw text at three times the density of the monitors that gave us the rule, and a well-drawn serif looks crisp at almost any size.</p>" +
            figure("note-coast-2", "A page of text set in a serif typeface", "The same paragraph, set in a serif at 18 pixels.", 800, 600) +
            "<p>There is also the matter of attention. An inbox is a wall of identical grotesques. A serif headline says, quietly, that this one is meant to be read rather than scanned.</p>" +
            "<blockquote><p>Type is the voice you hear when you read silently.</p></blockquote>" +
            "<h2>What to try this week</h2>" +
            "<ul><li>Set body text between 17 and 20 pixels.</li><li>Keep lines between 60 and 75 characters.</li><li>Leave a generous gap between paragraphs.</li></ul>" +
            "<p>None of this needs a redesign. Change one font, read the result on your phone, and see whether it feels like a letter again.</p>" +
            bookmark(
                "https://harbourgazette.example/the-ferry-is-back/", "The ferry is back: what the new timetable means for you",
                "Earlier crossings, a late boat on Fridays and, finally, room for bikes.", "Harbour Gazette", "Harbour Gazette",
                "avatar-harbour", "article-harbour",
            ) +
            "<p>That is the whole trick: type that gets out of the way, with just enough character to be remembered.</p>",
        likes = 156, reposts = 42, replies = 23,
    )
    val ferry = article(
        "the-ferry-is-back", harbour, 95,
        "The ferry is back: what the new timetable means for you",
        "Earlier crossings, a late boat on Fridays and, finally, room for bikes.",
        "article-harbour",
        "<p>After eleven weeks of repairs, the harbour ferry is running again, and the new timetable changes more than the engine room did.</p>" +
            "<h2>What changes</h2>" +
            "<ol><li>The first crossing leaves at 6:45, fifteen minutes earlier than before.</li><li>On Fridays a late boat leaves at 22:30.</li><li>Bikes travel on the lower deck, free of charge.</li></ol>" +
            figure("note-coast-3", "The ferry tied up at the harbour wall", "The refitted ferry at its berth on Monday morning.", 800, 600) +
            "<p>The operator says the extra crossings were the most requested change in this spring's survey, ahead even than a working coffee machine. Both are now on board.</p>" +
            "<h2>What stays the same</h2>" +
            "<p>Fares are unchanged for the rest of the year, and the monthly pass still covers both harbours. Timetables are posted at each landing and, for the first time, on the town's website.</p>" +
            "<p>The Gazette will keep tracking how the new schedule holds up through the winter.</p>",
        likes = 73, reposts = 18, replies = 11,
    )
    val myArticle = article(
        "winter-ferries-explained", me, 245,
        "Winter ferries, explained",
        "From November the crossing runs twice a day, not six times.",
        "article-seaside",
        "<p>From the first week of November the harbour ferry runs twice a day, not six times. If you cross for work, for school or for the Saturday market, here is what changes.</p>" +
            "<h2>The new sailings</h2>" +
            "<ul><li>Every day, the first boat leaves at 07:00.</li><li>On weekdays, the second leaves at 16:30.</li><li>At weekends, the second leaves at 13:00.</li></ul>" +
            "<h2>Planning around it</h2>" +
            "<p>The 07:00 is the one for school and work, so expect it to be full. The market stalls have agreed to pack up by 12:30 on Saturdays, and the café by the landing opens early for anyone waiting.</p>" +
            "<p>Questions about tickets or bikes go to Hamish at the ferry office, who has promised to answer every one of them before Christmas.</p>",
        likes = 96, reposts = 22, replies = 15,
    )
    val sourdough = article(
        "easy-sourdough", openKitchen, 600,
        "A beginner's sourdough, without the fuss",
        "No scales to the gram, no 3 a.m. folds. Just a loaf you'll want to bake again.",
        "article-openkitchen",
        "<p>Sourdough has a reputation for being fussy. It does not have to be. This is the loaf we bake every week, with a starter that lives on the kitchen counter and tolerates a lot of neglect.</p>" +
            "<h2>What you need</h2>" +
            "<ul><li>500 g strong white flour</li><li>350 g lukewarm water</li><li>100 g active starter</li><li>10 g fine salt</li></ul>" +
            figure("article-openkitchen", "A golden loaf of sourdough on a wooden board", "Out of the oven, still crackling.", 1200, 675, wide = true) +
            "<h2>Method</h2>" +
            "<ol><li>Mix everything in a big bowl until no dry flour is left. Cover and rest for an hour.</li><li>Fold the dough over itself four times, once every thirty minutes.</li><li>Shape it, put it in a floured bowl and chill it overnight.</li><li>Bake in a hot, lidded pot: 20 minutes covered, 25 uncovered.</li></ol>" +
            "<p>The crust should sing a little as it cools. If it does, you did it right.</p>",
        likes = 233, reposts = 58, replies = 46, liked = true,
    )
    val tinyTools = article(
        "tiny-tools", byteSized, 1_200,
        "The tiny-tools manifesto",
        "Software that does one thing, starts instantly and never asks you to sign up.",
        "article-bytesized",
        "<p>The best tools we use are small. They do one thing, they open instantly, and they never ask for an account.</p>" +
            "<h2>Three rules</h2>" +
            "<ol><li>Start in under a second.</li><li>Keep your data where you can see it.</li><li>Do one job, and do it well.</li></ol>" +
            "<p>Small tools are easier to finish, easier to trust and, it turns out, easier to love.</p>",
        likes = 188, reposts = 49, replies = 31,
    )
    val marketDay = article(
        "market-day", openKitchen, 2_000,
        "Market day: what to cook in early November",
        "Squash, chestnuts and the last of the good tomatoes.",
        "article-fieldkitchen",
        "<p>November markets smell of woodsmoke and roasted chestnuts. Squash is everywhere, the last tomatoes are sweeter than they have any right to be, and the leeks are enormous.</p>" +
            "<h2>This week's basket</h2>" +
            "<ul><li>One large squash, roasted with sage</li><li>A bag of chestnuts</li><li>The last of the good tomatoes</li></ul>" +
            "<p>Roast the squash until the edges catch, spoon over the tomatoes, and eat it warm with bread.</p>",
        likes = 41, reposts = 9, replies = 6,
    )

    /** The ordering of a feed: a post and who reposted it. */
    class Entry(val postId: String, val repostedBy: Persona? = null)

    val notesFeed = listOf(
        Entry(ridge.id, kofi), Entry(bench.id), Entry(serif.id, elena), Entry(coast.id, kofi), Entry(huts.id), Entry(rain.id),
        Entry(finale.id), Entry(winterFerries.id), Entry(tools.id),
    )
    val readerFeed = listOf(
        Entry(serif.id, kofi), Entry(ferry.id), Entry(myArticle.id), Entry(sourdough.id), Entry(tinyTools.id), Entry(marketDay.id),
    )

    // MARK: Threads

    private val replyPool = listOf(
        priya to "This is exactly what I needed today. Thank you for sharing it.",
        lucas to "Saving this for later. Great points all round.",
        noor to "Well said. I would love to read more on this.",
        hana to "Couldn't agree more.",
    )

    init {
        val hanaReply = note("ridge-r1", hana, 9, "<p>Ninety seconds of perfect light. This is why we do it.</p>", likes = 12, replies = 1)
        val elenaReply = note("ridge-r2", elena, 6, "<p>Exactly. And the coffee at the top tasted better than usual.</p>", likes = 8)
        val inesReply = note("ridge-r3", ines, 7, "<p>Adding this hike to my list for next spring.</p>", likes = 5)
        replies[ridge.id] = listOf(hanaReply.id, inesReply.id)
        replies[hanaReply.id] = listOf(elenaReply.id)

        val inesOnFerries = note(
            "winter-ferries-r1", ines, 18,
            "<p>So glad this one is out! I'm printing the new timetable for the café wall.</p>", likes = 3,
        )
        val priyaOnFerries = note("winter-ferries-r2", priya, 11, "<p>Twice a day is going to hurt. Thank you for explaining it so clearly.</p>", likes = 2)
        replies[winterFerries.id] = listOf(inesOnFerries.id, priyaOnFerries.id)

        // Every other post with replies gets a couple, so each thread has something to show.
        var index = 0
        for (parent in all.values.toList()) {
            if (parent.replyCount == 0 || parent.id in replies) continue
            val key = parent.id.trimEnd('/').substringAfterLast('/')
            val chosen = (0 until 2).map { replyPool[(index + it) % replyPool.size] }
            replies[parent.id] = chosen.mapIndexed { n, (by, text) ->
                note("$key-reply-$n", by, ageMinutes(parent.id) / 2 + 3L + n, "<p>$text</p>", likes = 1 + n).id
            }
            index++
        }
    }

    /** Child id to parent id. */
    val parents: Map<String, String> = replies.flatMap { (parent, children) -> children.map { it to parent } }.toMap()

    // MARK: Explore

    class ExploreTopic(val slug: String, val name: String, val people: List<Persona>)

    val topics = listOf(
        ExploreTopic("food", "Food & Drink", listOf(openKitchen, lucas)),
        ExploreTopic("news", "News", listOf(harbour, kofi, sam)),
        ExploreTopic("photography", "Photography", listOf(elena, hana, ines)),
        ExploreTopic("technology", "Technology", listOf(byteSized, kerning, priya)),
        ExploreTopic("top", "Top", listOf(elena, kerning, byteSized, harbour, openKitchen, kofi, hana, sam)),
        ExploreTopic("travel", "Travel", listOf(ines, elena, noor)),
    )

    // MARK: Recommendations (the pages other sites publish)

    class Website(val domain: String, val name: String)

    val quietPress = Website("quietpress.example", "Quiet Press")
    val websites = listOf(Website("alpinelight.example", "Alpine Light"), Website("trailjournal.example", "Trail Journal"))

    /** What every Ghost publication in the demo recommends: four with a social web account, one Ghost site without it, two plain websites. */
    val defaultRecommendations = listOf(
        kerning.url, byteSized.url, "https://${quietPress.domain}/", harbour.url, "https://${websites[0].domain}/", ines.url,
        "https://${websites[1].domain}/",
    )

    /** The link card pages notes link to: host plus path to title, description and picture. */
    class LinkPage(val title: String, val description: String, val image: String, val site: String)

    val linkPages = mapOf(
        "alpinelight.example/winter-hut-guide/" to LinkPage(
            "The winter hut guide: eleven mountain huts you can reach on foot",
            "Where to sleep, what to carry and how to read the snow, from a team that has walked every route twice.",
            "link-alpine", "Alpine Light",
        ),
    )
}
