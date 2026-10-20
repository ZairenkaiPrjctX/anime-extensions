package eu.kanade.tachiyomi.animeextension.all.jellyfin

import aniext.core.network.requireSuccess
import aniext.core.parsing.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the extension's request/parse flow against a real Jellyfin server. Opt-in:
 *
 *     ASE_LIVE_TESTS=1 JELLYFIN_TEST_URL=http://127.0.0.1:8096 JELLYFIN_TEST_USER=… JELLYFIN_TEST_PASSWORD=… \
 *       ./gradlew -Pextensions=all:jellyfin :src:all:jellyfin:testDebugUnitTest --tests '*LiveServerTest'
 *
 * The server needs at least one movie or series with a playable file.
 */
class LiveServerTest {

    private val client = OkHttpClient()

    @Test
    fun `login, catalog, details, episodes and stream against a live server`() {
        assumeTrue("set ASE_LIVE_TESTS=1 and JELLYFIN_TEST_URL/USER/PASSWORD to run", System.getenv("ASE_LIVE_TESTS") == "1" && System.getenv("JELLYFIN_TEST_URL") != null)
        val server = JellyfinApi.parseServerUrl(System.getenv("JELLYFIN_TEST_URL"))!!
        val base = Headers.headersOf("Accept", "application/json")
        val login = JellyfinApi.loginRequest(server, base, "ase-live-test", requireNotNull(System.getenv("JELLYFIN_TEST_USER")), System.getenv("JELLYFIN_TEST_PASSWORD").orEmpty())
        val (_, token) = JellyfinParser.parseLogin(client.newCall(login).execute().requireSuccess().parseAs<AuthResponseDto>(jellyfinJson))
        val auth = base.newBuilder().add("Authorization", JellyfinApi.authorizationHeader("ase-live-test", token)).build()

        fun get(url: HttpUrl) = client.newCall(Request.Builder().url(url).headers(auth).build()).execute().requireSuccess()

        val page = JellyfinParser.parseCatalog(get(JellyfinApi.catalogUrl(server, 1, JellyfinApi.Sort.NAME, JellyfinApi.Kind.ALL)).parseAs<ItemsPageDto>(jellyfinJson), server)
        assertTrue("library should not be empty", page.entries.isNotEmpty())

        for (entry in page.entries) {
            val item = get(JellyfinApi.itemUrl(server, entry.id, JellyfinApi.DETAIL_FIELDS)).parseAs<ItemDto>(jellyfinJson)
            val details = JellyfinParser.parseDetails(item, entry.id, server)
            val episodes = when (details.type) {
                "Movie" -> listOf(JellyfinParser.movieEpisode(item, server))
                else -> JellyfinParser.parseEpisodes(get(JellyfinApi.episodesUrl(server, entry.id)).parseAs<ItemsPageDto>(jellyfinJson), server)
            }
            assertTrue("${details.title} should have episodes", episodes.isNotEmpty())
            val playable = get(JellyfinApi.itemUrl(server, episodes.last().itemId, JellyfinApi.VIDEO_FIELDS)).parseAs<ItemDto>(jellyfinJson)
            val video = JellyfinParser.parseVideos(playable, server, auth).first()
            val ranged = Request.Builder().url(video.videoUrl).headers(video.headers!!).header("Range", "bytes=0-1023").build()
            client.newCall(ranged).execute().use { assertTrue("stream for ${details.title} returned HTTP ${it.code}", it.code == 206 || it.code == 200) }
            video.subtitleTracks.forEach { track ->
                client.newCall(Request.Builder().url(track.url).headers(auth).build()).execute().use { assertTrue("subtitle ${track.url}", it.isSuccessful) }
            }
        }
    }
}
