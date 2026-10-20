package eu.kanade.tachiyomi.animeextension.all.jellyfin

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal class KindFilter : AnimeFilter.Select<String>("Type", JellyfinApi.Kind.entries.map { it.label }.toTypedArray()) {
    val selected: JellyfinApi.Kind get() = JellyfinApi.Kind.entries[state]
}

internal class SortFilter : AnimeFilter.Select<String>("Sort by", JellyfinApi.Sort.entries.map { it.label }.toTypedArray()) {
    val selected: JellyfinApi.Sort get() = JellyfinApi.Sort.entries[state]
}

/** Default state matches the popular listing: movies and series sorted by name. */
internal fun filterList() = AnimeFilterList(KindFilter(), SortFilter())

internal fun AnimeFilterList.selectedKind(): JellyfinApi.Kind = filterIsInstance<KindFilter>().firstOrNull()?.selected ?: JellyfinApi.Kind.ALL

internal fun AnimeFilterList.selectedSort(): JellyfinApi.Sort = filterIsInstance<SortFilter>().firstOrNull()?.selected ?: JellyfinApi.Sort.NAME
