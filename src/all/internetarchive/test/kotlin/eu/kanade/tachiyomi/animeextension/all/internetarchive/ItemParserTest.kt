package eu.kanade.tachiyomi.animeextension.all.internetarchive

import aniext.core.network.ContentUnavailableException
import aniext.core.network.InvalidResponseException
import aniext.core.network.MissingFieldException
import aniext.core.parsing.parseAs
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixtures are sanitized recordings of the live API (see test/resources/fixtures/README.md). */
class ItemParserTest {

    private val headers = Headers.headersOf("User-Agent", "test")

    // ============================== Search ===============================

    @Test
    fun `search page maps entries and paging`() {
        val page = ItemParser.parseSearch(Fixtures.text("search_page1.json").parseAs<SearchResponseDto>(), page = 1)
        assertEquals(listOf("ElephantsDream", "Popeye_forPresident", "popeye_patriotic_popeye", "Sintel"), page.entries.map { it.identifier })
        assertEquals("Popeye for President", page.entries[1].title)
        assertEquals("/details/ElephantsDream", page.entries[0].path)
        assertEquals("https://archive.org/services/img/ElephantsDream", page.entries[0].thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `entries without identifier or title are skipped, not invented`() {
        val dto = """{"response":{"numFound":3,"start":0,"docs":[{"identifier":"ok","title":["Kept"]},{"title":"No id"},{"identifier":"no_title"},{"identifier":"../x","title":"Bad id"}]}}"""
            .parseAs<SearchResponseDto>()
        val page = ItemParser.parseSearch(dto, page = 1)
        assertEquals(listOf("ok"), page.entries.map { it.identifier })
        assertEquals("Kept", page.entries.single().title)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `paging stops at the end of results and at the deep paging window`() {
        val last = """{"response":{"numFound":101,"start":100,"docs":[{"identifier":"a","title":"A"}]}}""".parseAs<SearchResponseDto>()
        assertFalse(ItemParser.parseSearch(last, page = 3).hasNextPage)

        val deep = """{"response":{"numFound":15884,"start":9950,"docs":[{"identifier":"a","title":"A"}]}}""".parseAs<SearchResponseDto>()
        assertFalse(ItemParser.parseSearch(deep, page = 200).hasNextPage)

        val empty = """{"response":{"numFound":0,"start":0,"docs":[]}}""".parseAs<SearchResponseDto>()
        val emptyPage = ItemParser.parseSearch(empty, page = 1)
        assertTrue(emptyPage.entries.isEmpty())
        assertFalse(emptyPage.hasNextPage)
    }

    @Test
    fun `api errors are surfaced instead of an empty page`() {
        val dto = """{"error":"[DEEP_PAGING] Requested results would exceed the deep paging limit"}""".parseAs<SearchResponseDto>()
        try {
            ItemParser.parseSearch(dto, page = 201)
            fail("expected InvalidResponseException")
        } catch (e: InvalidResponseException) {
            assertTrue(e.message!!.contains("DEEP_PAGING"))
        }
        try {
            ItemParser.parseSearch("{}".parseAs<SearchResponseDto>(), page = 1)
            fail("expected MissingFieldException")
        } catch (e: MissingFieldException) {
            assertEquals("response", e.field)
        }
    }

    // ============================== Details ==============================

    @Test
    fun `details carry real metadata, plain-text description and license`() {
        val details = ItemParser.parseDetails(Fixtures.metadata("metadata_single_film.json"), "Popeye_forPresident")
        assertEquals("Popeye for President", details.title)
        assertEquals("Paramount Pictures", details.creator)
        assertEquals("Seymour Kneitel", details.director)
        assertEquals(listOf("Classic cartoon", "1950s", "Popeye"), details.genres)
        assertEquals(
            "Popeye and Bluto are both running for president. They are tied with exactly the same number of votes, " +
                "but Miss Olive Oyl has yet to cast her ballot. Which candidate will be able to impress her the most and earn her precious vote?\n\n" +
                "Date: 1956\n\nLicense: http://creativecommons.org/licenses/publicdomain/",
            details.description,
        )
    }

    @Test
    fun `html descriptions are converted to text`() {
        val details = ItemParser.parseDetails(Fixtures.metadata("metadata_elephants_dream.json"), "ElephantsDream")
        assertEquals("Elephants Dream", details.title)
        assertTrue(details.description!!.startsWith("From the official website:\n\n\"Elephants Dream is the story of"))
        assertFalse(details.description.contains("<a"))
        assertEquals(listOf("open", "movie", "surreal", "animation"), details.genres)
    }

    @Test
    fun `missing, dark and restricted items are reported as unavailable`() {
        val cases = mapOf(
            "{}" to "does not exist",
            """{"is_dark":true,"metadata":{"title":"x"}}""" to "not publicly available",
            Fixtures.text("metadata_restricted.json") to "access-restricted",
        )
        for ((json, expected) in cases) {
            try {
                ItemParser.parseDetails(json.parseAs<MetadataResponseDto>(), "TheHunt")
                fail("expected ContentUnavailableException for $expected")
            } catch (e: ContentUnavailableException) {
                assertTrue(e.message!!, e.message!!.contains(expected))
            }
        }
    }

    @Test
    fun `a missing title is an error, not a placeholder`() {
        try {
            ItemParser.parseDetails("""{"metadata":{"identifier":"x"},"files":[]}""".parseAs<MetadataResponseDto>(), "x")
            fail("expected MissingFieldException")
        } catch (e: MissingFieldException) {
            assertEquals("title", e.field)
        }
    }

    // ============================== Episodes =============================

    @Test
    fun `single film yields one episode named after the file title or stem`() {
        val episodes = ItemParser.parseEpisodes(Fixtures.metadata("metadata_single_film.json"), "Popeye_forPresident")
        val episode = episodes.single()
        assertEquals("Popeye for President", episode.name)
        assertEquals("/details/Popeye_forPresident/Popeye_forPresident.mpeg", episode.path)
        assertEquals(1f, episode.number)
        assertTrue("upload date comes from the file mtime", episode.dateUpload > 1_000_000_000_000L)

        val titled = ItemParser.parseEpisodes(Fixtures.metadata("metadata_subtitled.json"), "the_conceited_general").single()
        assertEquals("The Conceited General", titled.name)
    }

    @Test
    fun `collections group renditions per work and sort descending in natural order`() {
        val episodes = ItemParser.parseEpisodes(Fixtures.metadata("metadata_collection.json"), "popeye-collection")
        assertEquals(
            listOf(
                "Popeye The Sailor - Blow Me Down",
                "Popeye The Sailor - Baby Wants a Battleship",
                "Popeye The Sailor - A Clean Shaven Man",
            ),
            episodes.map { it.name },
        )
        assertEquals(listOf(3f, 2f, 1f), episodes.map { it.number })
        // .mkv and .mp4 originals of the same cartoon form one episode, keyed by the first original.
        assertEquals("popeye-collection" to "Popeye The Sailor - Baby Wants a Battleship.mkv", ArchiveApi.parsePath(episodes[1].path))
    }

    @Test
    fun `restricted items expose no episodes`() {
        try {
            ItemParser.parseEpisodes(Fixtures.metadata("metadata_restricted.json"), "TheHunt")
            fail("expected ContentUnavailableException")
        } catch (_: ContentUnavailableException) {
        }
    }

    @Test
    fun `private files are never listed`() {
        val dto = """{"metadata":{"title":"t"},"files":[
            {"name":"a.mp4","source":"original","format":"MPEG4","private":"true"},
            {"name":"b.mp4","source":"original","format":"MPEG4"},
            {"name":"notes.txt","source":"original","format":"Text"}]}""".parseAs<MetadataResponseDto>()
        assertEquals(listOf("b"), ItemParser.parseEpisodes(dto, "x").map { it.name })
        assertTrue(ItemParser.parseVideos(dto, "x", "a.mp4", headers).isEmpty())
    }

    // ============================== Videos ===============================

    @Test
    fun `videos list every public rendition, best first, with direct download URLs`() {
        val videos = ItemParser.parseVideos(Fixtures.metadata("metadata_collection.json"), "popeye-collection", "Popeye The Sailor - Baby Wants a Battleship.mkv", headers)
        assertEquals(
            // Same height: IA's normalized h.264 derivative is preferred over the uploaded MPEG4.
            listOf("h.264 IA · 1440p", "MPEG4 · 1440p", "Matroska · 480p"),
            videos.map { it.videoTitle },
        )
        assertEquals(listOf(1440, 1440, 480), videos.map { it.resolution })
        assertEquals(
            "https://archive.org/download/popeye-collection/Popeye%20The%20Sailor%20-%20Baby%20Wants%20a%20Battleship.ia.mp4",
            videos.first().videoUrl,
        )
        assertTrue(videos.all { it.initialized && it.headers == headers })
    }

    @Test
    fun `derivatives are matched to their original and non-video files are ignored`() {
        val videos = ItemParser.parseVideos(Fixtures.metadata("metadata_elephants_dream.json"), "ElephantsDream", "ed_hd.avi", headers)
        assertEquals(listOf("ed_hd.avi", "ed_hd.mp4", "ed_hd.ogv", "ed_hd_512kb.mp4"), videos.map { it.videoUrl.substringAfterLast('/') })
        assertEquals(listOf(1080, 360, 304, 240), videos.map { it.resolution })
    }

    @Test
    fun `subtitles are attached by file name, including auto-generated captions`() {
        val manual = ItemParser.parseVideos(Fixtures.metadata("metadata_subtitled.json"), "the_conceited_general", "the_conceited_general.mpeg", headers)
        assertEquals(3, manual.size)
        val track = manual.first().subtitleTracks.single()
        assertEquals("https://archive.org/download/the_conceited_general/the_conceited_general.srt", track.url)
        assertEquals("Subtitles", track.lang)

        val auto = ItemParser.parseVideos(Fixtures.metadata("metadata_asr_subtitles.json"), "OldToonTheater", "congoJazz.mp4", headers)
        // The 300p Ogg derivative outranks the 240p MPEG4 original.
        assertEquals(listOf("congoJazz.ogv", "congoJazz.mp4"), auto.map { it.videoUrl.substringAfterLast('/') })
        assertEquals(listOf("Subtitles (auto-generated)"), auto.first().subtitleTracks.map { it.lang })
        assertTrue(auto.first().subtitleTracks.single().url.endsWith("/congoJazz.asr.srt"))
    }

    @Test
    fun `unknown episode file yields no videos`() {
        assertTrue(ItemParser.parseVideos(Fixtures.metadata("metadata_single_film.json"), "Popeye_forPresident", "missing.mp4", headers).isEmpty())
        assertNull(ItemParser.parseVideos(Fixtures.metadata("metadata_single_film.json"), "Popeye_forPresident", "Popeye_forPresident.mpeg", null).first().headers)
    }
}
