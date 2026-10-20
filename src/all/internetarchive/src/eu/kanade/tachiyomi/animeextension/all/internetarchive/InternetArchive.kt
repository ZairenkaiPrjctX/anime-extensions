package eu.kanade.tachiyomi.animeextension.all.internetarchive

import aniext.core.network.rateLimit
import aniext.core.network.withRetry
import aniext.core.parsing.parseAs
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Animation from the Internet Archive (https://archive.org), read through its public Advanced
 * Search and Metadata APIs. Only publicly downloadable items are listed; lending-library and
 * withheld items are excluded. Rights are as declared by each item's uploader.
 */
class InternetArchive : AnimeHttpSource() {

    override val name = "Internet Archive"

    override val baseUrl = ArchiveApi.BASE_URL

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4) { it.host == ArchiveApi.HOST }
            .build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = searchRequest(ArchiveApi.SearchParams(), page)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = searchRequest(ArchiveApi.SearchParams(sort = ArchiveApi.Sort.RECENTLY_ADDED), page)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalog(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = searchRequest(filters.toSearchParams(query), page)

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    override fun getFilterList(): AnimeFilterList = filterList()

    private fun searchRequest(params: ArchiveApi.SearchParams, page: Int): Request = GET(ArchiveApi.searchUrl(params, page), headers)

    private fun parseCatalog(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val catalog = ItemParser.parseSearch(response.parseAs<SearchResponseDto>(), page)
        val animes = catalog.entries.map { entry ->
            SAnime.create().apply {
                url = entry.path
                title = entry.title
                thumbnail_url = entry.thumbnailUrl
            }
        }
        return AnimesPage(animes, catalog.hasNextPage)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (identifier, _) = ArchiveApi.parsePath(anime.url)
        val details = ItemParser.parseDetails(fetchMetadata(identifier), identifier)
        return SAnime.create().apply {
            url = anime.url
            title = details.title
            thumbnail_url = ArchiveApi.thumbnailUrl(identifier)
            description = details.description
            author = details.creator
            artist = details.director
            genre = details.genres.joinToString().ifEmpty { null }
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (identifier, _) = ArchiveApi.parsePath(anime.url)
        return ItemParser.parseEpisodes(fetchMetadata(identifier), identifier).map { entry ->
            SEpisode.create().apply {
                url = entry.path
                name = entry.name
                episode_number = entry.number
                date_upload = entry.dateUpload
            }
        }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Hosters/Videos ==========================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (identifier, fileName) = ArchiveApi.parsePath(episode.url)
        requireNotNull(fileName) { "Episode URL does not reference a file" }
        return ItemParser.parseVideos(fetchMetadata(identifier), identifier, fileName, headers).toHosterList()
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ============================== Helpers ===============================

    /** Metadata for one item; transient failures (timeouts, 429, 5xx) are retried with backoff. */
    private suspend fun fetchMetadata(identifier: String): MetadataResponseDto = withRetry {
        client.newCall(GET(ArchiveApi.metadataUrl(identifier), headers)).awaitSuccess().parseAs<MetadataResponseDto>()
    }
}
