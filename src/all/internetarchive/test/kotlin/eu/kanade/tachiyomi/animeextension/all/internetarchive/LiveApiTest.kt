package eu.kanade.tachiyomi.animeextension.all.internetarchive

import aniext.core.network.requireSuccess
import aniext.core.parsing.parseAs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Checks the live API contract with the same parser. Opt-in, so ordinary builds never depend on
 * archive.org being reachable:
 *
 *     ASE_LIVE_TESTS=1 ./gradlew :src:all:internetarchive:testDebugUnitTest --tests '*LiveApiTest'
 */
class LiveApiTest {

    private val client = OkHttpClient()

    private fun get(url: String) = client.newCall(Request.Builder().url(url).header("User-Agent", "anime-source-extensions-tests").build()).execute().requireSuccess()

    @Test
    fun `popular page, details, episodes and videos resolve against the live API`() {
        assumeTrue("set ASE_LIVE_TESTS=1 to run", System.getenv("ASE_LIVE_TESTS") == "1")

        val page = ItemParser.parseSearch(get(ArchiveApi.searchUrl(ArchiveApi.SearchParams(), 1).toString()).parseAs<SearchResponseDto>(), 1)
        assertTrue("popular page should not be empty", page.entries.isNotEmpty())
        assertTrue(page.hasNextPage)

        val entry = page.entries.first()
        val metadata = get(ArchiveApi.metadataUrl(entry.identifier).toString()).parseAs<MetadataResponseDto>()
        assertTrue(ItemParser.parseDetails(metadata, entry.identifier).title.isNotEmpty())
        val episodes = ItemParser.parseEpisodes(metadata, entry.identifier)
        assertTrue("top item should have at least one playable work", episodes.isNotEmpty())

        val (_, file) = ArchiveApi.parsePath(episodes.first().path)
        val videos = ItemParser.parseVideos(metadata, entry.identifier, file!!, null)
        assertTrue(videos.isNotEmpty())
        client.newCall(Request.Builder().url(videos.first().videoUrl).head().build()).execute().use {
            assertTrue("video URL should be downloadable, got HTTP ${it.code}", it.isSuccessful)
        }
    }
}
