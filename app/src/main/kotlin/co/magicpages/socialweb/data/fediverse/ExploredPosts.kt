package co.magicpages.socialweb.data.fediverse

import android.util.LruCache
import co.magicpages.socialweb.data.activitypub.Post
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The posts Fediverse explore listed this session, by ActivityPub id, as their own server's API
 * described them. The user's site only knows what federated to it, so a thread opened from Explore
 * reads its counts and replies from here and from the post's home server instead.
 */
@Singleton
class ExploredPosts @Inject constructor() {
    private val posts = LruCache<String, Post>(500)

    fun remember(found: List<Post>) {
        for (post in found) posts.put(post.id, post)
    }

    /** The post with ActivityPub id [id] as Explore listed it, or null when Explore never did. */
    operator fun get(id: String): Post? = posts.get(id)
}
