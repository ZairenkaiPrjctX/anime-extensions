package eu.kanade.tachiyomi.animeextension.all.internetarchive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {

    @Test
    fun `default filters reproduce the popular listing`() {
        val params = filterList().toSearchParams("")
        assertEquals(ArchiveApi.buildQuery(ArchiveApi.SearchParams()), ArchiveApi.buildQuery(params))
        assertEquals(ArchiveApi.Sort.DOWNLOADS, params.sort)
    }

    @Test
    fun `filter state is serialized into search parameters`() {
        val filters = filterList()
        filters.filterIsInstance<CollectionFilter>().single().state = ArchiveApi.Collection.MORE_ANIMATION.ordinal
        filters.filterIsInstance<SortFilter>().single().state = ArchiveApi.Sort.OLDEST.ordinal
        filters.filterIsInstance<OpenLicenseFilter>().single().state = true

        val params = filters.toSearchParams("betty boop")
        assertEquals(ArchiveApi.Collection.MORE_ANIMATION, params.collection)
        assertEquals(ArchiveApi.Sort.OLDEST, params.sort)
        assertTrue(params.openLicenseOnly)
        assertEquals("betty boop", params.terms)

        val url = ArchiveApi.searchUrl(params, 1)
        assertEquals("date asc", url.queryParameter("sort[]"))
        assertTrue(url.queryParameter("q")!!.contains("collection:(more_animation)"))
        assertTrue(url.queryParameter("q")!!.endsWith("AND licenseurl:* AND (betty boop)"))
    }

    @Test
    fun `labels line up with enum values`() {
        assertEquals(ArchiveApi.Sort.entries.map { it.label }, SortFilter().values.toList())
        assertFalse(OpenLicenseFilter().state)
    }
}
