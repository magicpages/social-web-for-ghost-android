package co.magicpages.socialweb.data.fediverse

/** What a featured server's community talks about, which is what its trends are about. */
enum class ServerTopic { General, Technology, OpenSource, Science, Climate, Journalism, GameDevelopment, Music, IndieWeb }

data class TopicServer(val host: String, val topic: ServerTopic)

/** [language] is a BCP 47 tag, named in the user's own language where it's shown. */
data class LanguageServer(val host: String, val language: String)

/**
 * Servers to pick from, each checked to share its trending posts, its trending hashtags and the posts
 * under a hashtag without signing in. Many servers keep hashtag timelines to their own members.
 */
val TopicServers: List<TopicServer> = listOf(
    TopicServer("mastodon.social", ServerTopic.General),
    TopicServer("hachyderm.io", ServerTopic.Technology),
    TopicServer("fosstodon.org", ServerTopic.OpenSource),
    TopicServer("fediscience.org", ServerTopic.Science),
    TopicServer("climatejustice.social", ServerTopic.Climate),
    TopicServer("journa.host", ServerTopic.Journalism),
    TopicServer("mastodon.gamedev.place", ServerTopic.GameDevelopment),
    TopicServer("musician.social", ServerTopic.Music),
    TopicServer("indieweb.social", ServerTopic.IndieWeb),
)

val LanguageServers: List<LanguageServer> = listOf(
    LanguageServer("mastodon.de", "de"),
    LanguageServer("masto.es", "es"),
    LanguageServer("piaille.fr", "fr"),
    LanguageServer("mastodon.uno", "it"),
    LanguageServer("mastodon.nl", "nl"),
)
