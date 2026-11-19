package co.magicpages.socialweb.ui.media

import co.magicpages.socialweb.data.activitypub.Attachment
import co.magicpages.socialweb.data.activitypub.Post
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun post(attachments: String) =
        json.decodeFromString<Post>("""{"id": "p", "author": {"id": "a", "handle": "@a@example.social"}, "attachments": $attachments}""")

    private val did = "did:plc:agxhun7wf2fm3fdfj6zrm33y"
    private val cid = "bafkreicjxpyffywf4rk4oqugen2bdjf4nuoffnualbjinkdx6m27rjja3i"
    private val bridgyBlob = "https://inkcap.us-east.host.bsky.network/xrpc/com.atproto.sync.getBlob?did=$did&cid=$cid"
    private val blueskyBase = "https://video.bsky.app/watch/did%3Aplc%3Aagxhun7wf2fm3fdfj6zrm33y/$cid/"

    @Test
    fun `a video media type is a video that plays from its file`() {
        val video = Attachment(type = "Document", mediaType = "video/mp4", url = "https://files.example/a.mp4").toVideoSource()

        assertEquals("https://files.example/a.mp4", video?.playbackUrl)
        assertFalse(video!!.isHls)
        assertNull(video.posterUrl)
    }

    @Test
    fun `media type is read without case or parameters`() {
        val video = Attachment(type = "Document", mediaType = "Video/WebM; codecs=vp9", url = "https://files.example/a.webm").toVideoSource()

        assertNotNull(video)
    }

    @Test
    fun `type Video without a media type is a video`() {
        assertNotNull(Attachment(type = "Video", url = "https://files.example/clip").toVideoSource())
        assertNotNull(Attachment(type = "video", mediaType = "", url = "https://files.example/clip").toVideoSource())
    }

    @Test
    fun `a document without a video media type is not a video`() {
        assertNull(Attachment(type = "Document", url = "https://files.example/a.mp4").toVideoSource())
        assertNull(Attachment(type = "Document", mediaType = "application/pdf", url = "https://files.example/a.pdf").toVideoSource())
        assertNull(Attachment(url = "https://files.example/a").toVideoSource())
    }

    @Test
    fun `images and audio are not videos even when typed as Video`() {
        assertNull(Attachment(type = "Image", mediaType = "image/png", url = "https://files.example/a.png").toVideoSource())
        assertNull(Attachment(type = "Image", url = "https://files.example/a.jpg").toVideoSource())
        assertNull(Attachment(type = "Video", mediaType = "image/gif", url = "https://files.example/a.gif").toVideoSource())
        assertNull(Attachment(type = "Document", mediaType = "audio/mpeg", url = "https://files.example/a.mp3").toVideoSource())
    }

    @Test
    fun `HLS is recognised by media type or by playlist path`() {
        val byMime = Attachment(type = "Document", mediaType = "application/vnd.apple.mpegurl", url = "https://stream.example/live").toVideoSource()
        val byMixedCaseMime = Attachment(type = "Link", mediaType = "application/x-mpegURL", url = "https://stream.example/live").toVideoSource()
        val byPath = Attachment(type = "Document", url = "https://stream.example/a/master.m3u8?token=1").toVideoSource()
        val byPathAndVideoMime = Attachment(mediaType = "video/mp4", url = "https://stream.example/a.M3U8").toVideoSource()

        assertTrue(byMime!!.isHls)
        assertTrue(byMixedCaseMime!!.isHls)
        assertTrue(byPath!!.isHls)
        assertTrue(byPathAndVideoMime!!.isHls)
        assertEquals("https://stream.example/live", byMime.playbackUrl)
    }

    @Test
    fun `only web addresses can be videos`() {
        assertNull(Attachment(type = "Video", mediaType = "video/mp4", url = "file:///sdcard/a.mp4").toVideoSource())
        assertNull(Attachment(type = "Video", mediaType = "video/mp4", url = "content://media/external/video/1").toVideoSource())
        assertNull(Attachment(type = "Video", mediaType = "video/mp4", url = "not a url").toVideoSource())
        assertNull(Attachment(type = "Video", mediaType = "video/mp4", url = "https:///a.mp4").toVideoSource())
        assertNull(Attachment(type = "Video", url = "https://pds.example/xrpc/com.atproto.sync.getBlob?did=did:plc:%zz&cid=bafk").toVideoSource())
    }

    @Test
    fun `a poster that is not a web address is dropped`() {
        val video = Attachment(type = "Video", url = "https://files.example/a.mp4", posterUrl = "javascript:alert(1)").toVideoSource()

        assertNull(video!!.posterUrl)
        assertEquals("https://files.example/p.jpg", Attachment(type = "Video", url = "https://files.example/a.mp4", posterUrl = "https://files.example/p.jpg").toVideoSource()!!.posterUrl)
    }

    @Test
    fun `shape comes from the attachment's size when it has both sides`() {
        assertEquals(16f / 9f, Attachment(type = "Video", url = "https://files.example/a", width = 1920, height = 1080).toVideoSource()!!.aspectRatio!!, 0.0001f)
        assertEquals(0.5f, Attachment(type = "Video", url = "https://files.example/a", width = 540, height = 1080).toVideoSource()!!.aspectRatio!!, 0.0001f)
        assertNull(Attachment(type = "Video", url = "https://files.example/a", width = 1920).toVideoSource()!!.aspectRatio)
        assertNull(Attachment(type = "Video", url = "https://files.example/a").toVideoSource()!!.aspectRatio)
    }

    @Test
    fun `alt text becomes the description unless blank`() {
        assertEquals("A cat", Attachment(type = "Video", url = "https://files.example/a", name = "A cat").toVideoSource()!!.description)
        assertNull(Attachment(type = "Video", url = "https://files.example/a", name = "  ").toVideoSource()!!.description)
    }

    @Test
    fun `a Bluesky video bridged by Bridgy Fed plays from Bluesky's stream with Bluesky's poster`() {
        val video = Attachment(type = "Video", url = bridgyBlob).toVideoSource()

        assertEquals(blueskyBase + "playlist.m3u8", video!!.playbackUrl)
        assertTrue(video.isHls)
        assertEquals(blueskyBase + "thumbnail.jpg", video.posterUrl)
    }

    @Test
    fun `a Bluesky video keeps the poster the attachment names`() {
        val video = Attachment(type = "Video", url = bridgyBlob, posterUrl = blueskyBase + "thumbnail.jpg").toVideoSource()

        assertEquals(blueskyBase + "thumbnail.jpg", video!!.posterUrl)
    }

    @Test
    fun `a Bluesky blob that is not a video stays a plain attachment`() {
        assertNull(Attachment(type = "Image", url = bridgyBlob).toVideoSource())
    }

    @Test
    fun `a Bluesky blob link that looks wrong plays from its own address`() {
        val pds = "https://pds.example/xrpc/com.atproto.sync.getBlob"

        for (url in listOf(
            "$pds?did=$did&cid=../../x",
            "$pds?did=$did&cid=",
            "$pds?cid=$cid",
            "$pds?did=plc:abc&cid=$cid",
            "$pds?did=did:plc:a/b&cid=$cid",
            "https://pds.example/other/path?did=$did&cid=$cid",
        )) {
            val video = Attachment(type = "Video", url = url).toVideoSource()
            assertEquals(url, video?.playbackUrl)
            assertFalse(url, video!!.isHls)
        }
    }

    @Test
    fun `the exact attachment Bridgy Fed sends for a Bluesky video decodes and plays`() {
        val videos = post(
            """[{"image": {"type": "Image", "url": "${blueskyBase}thumbnail.jpg"}, "type": "Video", "url": "$bridgyBlob"}]""",
        ).videos

        assertEquals(1, videos.size)
        assertEquals(blueskyBase + "playlist.m3u8", videos.single().playbackUrl)
        assertEquals(blueskyBase + "thumbnail.jpg", videos.single().posterUrl)
    }

    @Test
    fun `a single attachment written as a bare object still decodes`() {
        val videos = post("""{"type": "Video", "url": "$bridgyBlob"}""").videos

        assertEquals(blueskyBase + "playlist.m3u8", videos.single().playbackUrl)
    }

    @Test
    fun `Mastodon's video attachment decodes with its size`() {
        val videos = post(
            """[{"type": "Document", "mediaType": "video/mp4", "url": "https://files.mastodon.example/a.mp4", "name": "Waves", "width": 1280, "height": 720, "blurhash": "UBL"}]""",
        ).videos

        val video = videos.single()
        assertEquals("https://files.mastodon.example/a.mp4", video.playbackUrl)
        assertEquals("Waves", video.description)
        assertEquals(1280f / 720f, video.aspectRatio!!, 0.0001f)
    }

    @Test
    fun `odd sizes never stop a post from decoding`() {
        val videos = post(
            """[{"type": "Video", "url": "https://a.example/1", "width": "1920", "height": 1080.0},
                {"type": "Video", "url": "https://a.example/2", "width": "wide", "height": null},
                {"type": "Video", "url": "https://a.example/3", "width": {"v": 1}, "height": -4}]""",
        ).videos

        assertEquals(16f / 9f, videos[0].aspectRatio!!, 0.0001f)
        assertNull(videos[1].aspectRatio)
        assertNull(videos[2].aspectRatio)
    }

    @Test
    fun `a post's videos leave out its images and keep their order`() {
        val mixed = post(
            """[{"type": "Image", "mediaType": "image/jpeg", "url": "https://a.example/1.jpg"},
                {"type": "Document", "mediaType": "video/mp4", "url": "https://a.example/first.mp4"},
                {"type": "Document", "mediaType": "image/png", "url": "https://a.example/2.png"},
                {"type": "Video", "url": "https://a.example/second"}]""",
        )

        assertEquals(listOf("https://a.example/first.mp4", "https://a.example/second"), mixed.videos.map { it.playbackUrl })
        assertEquals(listOf("https://a.example/1.jpg", "https://a.example/2.png"), mixed.images.map { it.url })
    }
}
