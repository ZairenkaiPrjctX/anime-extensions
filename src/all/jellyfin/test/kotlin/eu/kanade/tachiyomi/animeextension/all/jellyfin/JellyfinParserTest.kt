package eu.kanade.tachiyomi.animeextension.all.jellyfin

import aniext.core.network.ContentUnavailableException
import aniext.core.network.MissingFieldException
import aniext.core.parsing.parseAs
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixtures were recorded from a Jellyfin 12.2 server; see test/resources/fixtures/README.md. */
class JellyfinParserTest {

    private val server = Fixtures.server
    private val headers = Headers.headersOf("Authorization", "MediaBrowser Token=\"x\"")
    private val movieId = "7eff97ff8df4ecfda1c1cfdab3315fb1"
    private val seriesId = "e719a78f051a1d4e47fe2faf2774e684"

    @Test
    fun `login yields user id and token`() {
        assertEquals("328290fb6f9f433d8a11e4c866db48f3" to "REDACTED", JellyfinParser.parseLogin(Fixtures.load("auth_response.json")))
        try {
            JellyfinParser.parseLogin("""{"User":{"Id":"328290fb6f9f433d8a11e4c866db48f3"}}""".parseAs<AuthResponseDto>(json = jellyfinJson))
            fail("expected MissingFieldException")
        } catch (e: MissingFieldException) {
            assertEquals("AccessToken", e.field)
        }
    }

    @Test
    fun `catalog maps movies and series with thumbnails only when an image exists`() {
        val page = JellyfinParser.parseCatalog(Fixtures.load("catalog_popular.json"), server)
        assertEquals(listOf("ASE Test Series", "The Conceited General"), page.entries.map { it.title })
        assertEquals(listOf(seriesId, movieId), page.entries.map { it.id })
        assertNull("the series has no artwork on the server", page.entries[0].thumbnailUrl)
        assertEquals("http://192.168.1.10:8096/Items/$movieId/Images/Primary?tag=", page.entries[1].thumbnailUrl!!.substringBefore("tag=") + "tag=")
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `paging follows StartIndex and TotalRecordCount`() {
        val firstOfTwo = JellyfinParser.parseCatalog(Fixtures.load("catalog_paged.json"), server)
        assertEquals(1, firstOfTwo.entries.size)
        assertTrue(firstOfTwo.hasNextPage)
        val empty = JellyfinParser.parseCatalog(Fixtures.load("catalog_search_empty.json"), server)
        assertTrue(empty.entries.isEmpty())
        assertFalse(empty.hasNextPage)
    }

    @Test
    fun `catalog skips folders, episodes and items without id or name`() {
        val dto = """{"Items":[{"Id":"$movieId","Name":"Ok","Type":"Movie"},{"Id":"$seriesId","Type":"Series"},
            {"Id":"bad","Name":"Bad id","Type":"Movie"},{"Id":"$seriesId","Name":"Folder","Type":"CollectionFolder"},
            {"Id":"$seriesId","Name":"Episode","Type":"Episode"}],"TotalRecordCount":5,"StartIndex":0}""".parseAs<ItemsPageDto>(json = jellyfinJson)
        assertEquals(listOf("Ok"), JellyfinParser.parseCatalog(dto, server).entries.map { it.title })
    }

    @Test
    fun `details of a movie and a series`() {
        val movie = JellyfinParser.parseDetails(Fixtures.load("item_movie.json"), movieId, server)
        assertEquals("The Conceited General", movie.title)
        assertEquals("Movie", movie.type)
        // The overview was read by the server from the file's embedded metadata.
        assertTrue(movie.description!!.startsWith("After a victorious military campaign, a general returns home"))
        assertTrue(movie.description.endsWith("\n\nYear: 1956"))
        assertTrue(movie.thumbnailUrl!!.contains("/Items/$movieId/Images/Primary"))

        val series = JellyfinParser.parseDetails(Fixtures.load("item_series.json"), seriesId, server)
        assertEquals("ASE Test Series", series.title)
        assertEquals("Series", series.type)
        assertTrue(series.genres.isEmpty())
        assertNull(series.studios)
    }

    @Test
    fun `overview html, genres and studios are mapped`() {
        val dto = """{"Id":"$movieId","Name":"T","Type":"Movie","Overview":"Line<br>two &amp; more","Genres":["Animation",""],
            "Studios":[{"Name":"Shanghai Animation Film Studio"},{"Name":""}],"ProductionYear":1956}""".parseAs<ItemDto>(json = jellyfinJson)
        val details = JellyfinParser.parseDetails(dto, movieId, server)
        assertEquals("Line\ntwo & more\n\nYear: 1956", details.description)
        assertEquals(listOf("Animation"), details.genres)
        assertEquals("Shanghai Animation Film Studio", details.studios)
    }

    @Test
    fun `an unknown id resolving to a library folder is reported as unavailable`() {
        try {
            JellyfinParser.parseDetails(Fixtures.load("item_root_folder.json"), "00000000000000000000000000000000", server)
            fail("expected ContentUnavailableException")
        } catch (_: ContentUnavailableException) {
        }
        try {
            JellyfinParser.parseDetails(Fixtures.load("item_movie.json"), seriesId, server)
            fail("a response for another id must not be accepted")
        } catch (_: ContentUnavailableException) {
        }
    }

    @Test
    fun `series episodes span seasons, are numbered uniquely and sorted descending`() {
        val episodes = JellyfinParser.parseEpisodes(Fixtures.load("episodes_series.json"), server)
        assertEquals(
            listOf("S2E2 · ASE Test Series S02E02", "S2E1 · ASE Test Series S02E01", "S1E2 · ASE Test Series S01E02", "S1E1 · ASE Test Series S01E01"),
            episodes.map { it.name },
        )
        assertEquals(listOf(4f, 3f, 2f, 1f), episodes.map { it.number })
        assertTrue("DateCreated is parsed", episodes.all { it.dateUpload > 1_700_000_000_000L })
        assertTrue(episodes.all { it.previewUrl != null })
    }

    @Test
    fun `a movie is a single episode`() {
        val episode = JellyfinParser.movieEpisode(Fixtures.load("item_movie.json"), server)
        assertEquals(movieId, episode.itemId)
        assertEquals("The Conceited General", episode.name)
        assertEquals(1f, episode.number)
    }

    @Test
    fun `movie video is the original file with its external subtitle`() {
        val videos = JellyfinParser.parseVideos(Fixtures.load("item_movie_sources.json"), server, headers)
        val video = videos.single()
        assertEquals("http://192.168.1.10:8096/Videos/$movieId/stream?static=true&mediaSourceId=$movieId", video.videoUrl)
        assertEquals("240p · MP4 · Original", video.videoTitle)
        assertEquals(240, video.resolution)
        assertEquals(headers, video.headers)
        assertTrue(video.initialized)
        val track = video.subtitleTracks.single()
        assertEquals("http://192.168.1.10:8096/Videos/$movieId/$movieId/Subtitles/0/0/Stream.srt", track.url)
        assertEquals("English - SUBRIP - External", track.lang)
    }

    @Test
    fun `episode video without subtitles`() {
        val video = JellyfinParser.parseVideos(Fixtures.load("item_episode_sources.json"), server, headers).single()
        assertEquals(240, video.resolution)
        assertTrue(video.subtitleTracks.isEmpty())
    }

    @Test
    fun `several versions are listed best first and named`() {
        val dto = """{"Id":"$movieId","Name":"T","Type":"Movie","MediaSources":[
            {"Id":"$seriesId","Name":"SD","Container":"mkv","MediaStreams":[{"Type":"Video","Height":480}]},
            {"Id":"$movieId","Name":"HD","Container":"mp4","MediaStreams":[{"Type":"Video","Height":1080}]},
            {"Name":"no id","MediaStreams":[]}]}""".parseAs<ItemDto>(json = jellyfinJson)
        assertEquals(listOf("HD · 1080p · MP4 · Original", "SD · 480p · MKV · Original"), JellyfinParser.parseVideos(dto, server, null).map { it.videoTitle })
    }
}
