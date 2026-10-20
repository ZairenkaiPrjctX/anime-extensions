package eu.kanade.tachiyomi.animeextension.all.internetarchive

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal class CollectionFilter : AnimeFilter.Select<String>("Collection", ArchiveApi.Collection.entries.map { it.label }.toTypedArray()) {
    val selected: ArchiveApi.Collection get() = ArchiveApi.Collection.entries[state]
}

internal class SortFilter : AnimeFilter.Select<String>("Sort by", ArchiveApi.Sort.entries.map { it.label }.toTypedArray()) {
    val selected: ArchiveApi.Sort get() = ArchiveApi.Sort.entries[state]
}

internal class OpenLicenseFilter : AnimeFilter.CheckBox("Only items with a declared license (CC / public domain)")

/** Default state matches the popular listing: Animation & Cartoons, most downloaded. */
internal fun filterList() = AnimeFilterList(
    CollectionFilter(),
    SortFilter(),
    OpenLicenseFilter(),
)

internal fun AnimeFilterList.toSearchParams(query: String): ArchiveApi.SearchParams {
    var params = ArchiveApi.SearchParams(terms = query)
    for (filter in this) {
        params = when (filter) {
            is CollectionFilter -> ArchiveApi.SearchParams(params.terms, filter.selected, params.sort, params.openLicenseOnly)
            is SortFilter -> ArchiveApi.SearchParams(params.terms, params.collection, filter.selected, params.openLicenseOnly)
            is OpenLicenseFilter -> ArchiveApi.SearchParams(params.terms, params.collection, params.sort, filter.state)
            else -> params
        }
    }
    return params
}
