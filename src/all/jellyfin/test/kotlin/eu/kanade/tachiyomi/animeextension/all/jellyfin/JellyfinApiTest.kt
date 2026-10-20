package eu.kanade.tachiyomi.animeextension.all.jellyfin

import okhttp3.Headers
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JellyfinApiTest {

    private val server = Fixtures.server
    private val movieId = "7eff97ff8df4ecfda1c1cfdab3315fb1"

    @Test
    fun `server addresses are normalized and unsafe ones rejected`() {
        assertEquals("https://jf.example.org/jellyfin", JellyfinApi.parseServerUrl(" https://jf.example.org/jellyfin/ ").toString())
        assertEquals("http://192.168.1.10:8096/", JellyfinApi.parseServerUrl("http://192.168.1.10:8096").toString())
        for (bad in listOf(null, "", "jf.example.org", "ftp://jf.example.org", "https://u:p@jf.example.org", "https://jf.example.org/?x=1", "https://jf.example.org/#f")) {
            assertNull("should reject '$bad'", JellyfinApi.parseServerUrl(bad))
        }
    }

    @Test
    fun `catalog URL matches the recorded request`() {
        val url = JellyfinApi.catalogUrl(server, page = 1, sort = JellyfinApi.Sort.NAME, kind = JellyfinApi.Kind.ALL)
        assertEquals(
            "/Items?Recursive=true&IncludeItemTypes=Movie%2CSeries&SortBy=SortName&SortOrder=Ascending&StartIndex=0&Limit=50&EnableImageTypes=Primary&ImageTypeLimit=1",
            url.encodedPath + "?" + url.encodedQuery,
        )
        val search = JellyfinApi.catalogUrl(server, page = 3, sort = JellyfinApi.Sort.RECENTLY_ADDED, kind = JellyfinApi.Kind.SERIES, searchTerm = "  bleach ")
        assertEquals("100", search.queryParameter("StartIndex"))
        assertEquals("Series", search.queryParameter("IncludeItemTypes"))
        assertEquals("DateCreated,SortName", search.queryParameter("SortBy"))
        assertEquals("Descending", search.queryParameter("SortOrder"))
        assertEquals("bleach", search.queryParameter("SearchTerm"))
        assertNull(JellyfinApi.catalogUrl(server, 1, JellyfinApi.Sort.NAME, JellyfinApi.Kind.ALL, "   ").queryParameter("SearchTerm"))
    }

    @Test
    fun `URLs keep a reverse-proxy base path and never contain the token`() {
        val proxied = JellyfinApi.parseServerUrl("https://media.example.org/jellyfin")!!
        assertEquals("https://media.example.org/jellyfin/Items/$movieId?Fields=MediaSources%2CDateCreated", JellyfinApi.itemUrl(proxied, movieId, JellyfinApi.VIDEO_FIELDS).toString())
        assertEquals("https://media.example.org/jellyfin/Videos/$movieId/stream?static=true&mediaSourceId=$movieId", JellyfinApi.streamUrl(proxied, movieId, movieId))
        assertEquals("https://media.example.org/jellyfin/Shows/$movieId/Episodes?Fields=DateCreated%2COverview", JellyfinApi.episodesUrl(proxied, movieId).toString())
    }

    @Test
    fun `item ids are validated in paths and URLs`() {
        assertEquals("/Items/$movieId", JellyfinApi.itemPath(movieId))
        assertEquals(movieId, JellyfinApi.idFromPath("/Items/$movieId"))
        for (bad in listOf("/Items/../System/Info", "/Items/", "/Items/123", "/Users/$movieId")) {
            try {
                JellyfinApi.idFromPath(bad)
                fail("expected rejection of $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `authorization header is quoted and encoded`() {
        assertEquals(
            "MediaBrowser Client=\"Anime+Source+Extensions\", Device=\"Android\", DeviceId=\"dev-1\", Version=\"1\"",
            JellyfinApi.authorizationHeader("dev-1"),
        )
        assertTrue(JellyfinApi.authorizationHeader("dev\"1", "t,k").endsWith("DeviceId=\"dev%221\", Version=\"1\", Token=\"t%2Ck\""))
    }

    @Test
    fun `login request posts PascalCase credentials without a token`() {
        val request = JellyfinApi.loginRequest(server, Headers.headersOf("Accept", "application/json"), "dev-1", "asetest", "p@ss")
        assertEquals("POST", request.method)
        assertEquals("http://192.168.1.10:8096/Users/AuthenticateByName", request.url.toString())
        assertEquals("application/json", request.header("Accept"))
        assertFalse(request.header("Authorization")!!.contains("Token="))
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"Username":"asetest","Pw":"p@ss"}""", body)
    }

    @Test
    fun `only text subtitles get a conversion URL`() {
        fun stream(codec: String?, index: Int? = 0) = MediaStreamDto(type = "Subtitle", codec = codec, index = index)
        assertEquals("http://192.168.1.10:8096/Videos/$movieId/$movieId/Subtitles/0/0/Stream.srt", JellyfinApi.subtitleUrl(server, movieId, movieId, stream("subrip")))
        assertTrue(JellyfinApi.subtitleUrl(server, movieId, movieId, stream("ASS"))!!.endsWith("/Stream.ass"))
        assertNull(JellyfinApi.subtitleUrl(server, movieId, movieId, stream("PGSSUB")))
        assertNull(JellyfinApi.subtitleUrl(server, movieId, movieId, stream("dvd_subtitle")))
        assertNull(JellyfinApi.subtitleUrl(server, movieId, movieId, stream("subrip", index = null)))
    }
}
