package eu.kanade.tachiyomi.animeextension.all.internetarchive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiTest {

    @Test
    fun `default query lists public animation only`() {
        assertEquals(
            "mediatype:(movies) AND collection:(animationandcartoons) AND NOT access-restricted-item:(true)",
            ArchiveApi.buildQuery(ArchiveApi.SearchParams()),
        )
    }

    @Test
    fun `user terms are escaped and cannot alter the query`() {
        val query = ArchiveApi.buildQuery(
            ArchiveApi.SearchParams(
                terms = " felix) OR collection:(anime ",
                collection = ArchiveApi.Collection.CLASSIC,
                openLicenseOnly = true,
            ),
        )
        assertEquals(
            "mediatype:(movies) AND collection:(classic_cartoons) AND NOT access-restricted-item:(true) AND licenseurl:* AND (felix\\) OR collection\\:\\(anime)",
            query,
        )
    }

    @Test
    fun `search url carries paging, fields and sort`() {
        val url = ArchiveApi.searchUrl(ArchiveApi.SearchParams(sort = ArchiveApi.Sort.TITLE), page = 3)
        assertEquals("archive.org", url.host)
        assertEquals("/advancedsearch.php", url.encodedPath)
        assertEquals("3", url.queryParameter("page"))
        assertEquals("50", url.queryParameter("rows"))
        assertEquals("json", url.queryParameter("output"))
        assertEquals(listOf("identifier", "title"), url.queryParameterValues("fl[]"))
        assertEquals("titleSorter asc", url.queryParameter("sort[]"))
        assertNull(ArchiveApi.searchUrl(ArchiveApi.SearchParams(sort = ArchiveApi.Sort.RELEVANCE), 1).queryParameter("sort[]"))
    }

    @Test
    fun `file paths round-trip names with spaces, unicode and reserved characters`() {
        val name = "Popeye The Sailor - A Clean Shaven Man #1? (100%).mp4"
        val path = ArchiveApi.filePath("popeye-collection", name)
        assertEquals("/details/popeye-collection/Popeye%20The%20Sailor%20-%20A%20Clean%20Shaven%20Man%20%231%3F%20(100%25).mp4", path)
        assertEquals("popeye-collection" to name, ArchiveApi.parsePath(path))

        val nested = ArchiveApi.filePath("item", "disc 1/骄傲的将军.mp4")
        assertEquals("item" to "disc 1/骄傲的将军.mp4", ArchiveApi.parsePath(nested))
        assertEquals("ElephantsDream" to null, ArchiveApi.parsePath(ArchiveApi.itemPath("ElephantsDream")))
        assertEquals(
            "https://archive.org/download/item/disc%201/a%23b.mp4",
            ArchiveApi.downloadUrl("item", "disc 1/a#b.mp4"),
        )
    }

    @Test
    fun `identifiers are validated before building URLs`() {
        assertTrue(ArchiveApi.isValidIdentifier("Popeye_forPresident"))
        assertTrue(ArchiveApi.isValidIdentifier("mr.-men-1974-78"))
        for (bad in listOf("", "../etc", "a/b", "a b", "-leading", "x".repeat(101))) {
            assertFalse("'$bad' must be rejected", ArchiveApi.isValidIdentifier(bad))
        }
        for (path in listOf("/details/../metadata", "/search?q=x", "/details")) {
            try {
                ArchiveApi.parsePath(path)
                fail("expected rejection of $path")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
