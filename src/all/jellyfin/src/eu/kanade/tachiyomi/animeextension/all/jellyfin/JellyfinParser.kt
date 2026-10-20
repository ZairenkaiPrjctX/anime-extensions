package eu.kanade.tachiyomi.animeextension.all.jellyfin

import aniext.core.network.ContentUnavailableException
import aniext.core.network.InvalidResponseException
import aniext.core.network.MissingFieldException
import aniext.core.parsing.htmlToText
import aniext.core.parsing.tryParse
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal class CatalogEntry(val id: String, val title: String, val thumbnailUrl: String?)

internal class CatalogPage(val entries: List<CatalogEntry>, val hasNextPage: Boolean)

internal class ItemDetails(
    val title: String,
    val type: String,
    val description: String?,
    val genres: List<String>,
    val studios: String?,
    val thumbnailUrl: String?,
)

internal class EpisodeEntry(val itemId: String, val name: String, val number: Float, val dateUpload: Long, val previewUrl: String?)

/** Interprets Jellyfin responses without touching extensions-lib stubs, so it is unit-testable. */
internal object JellyfinParser {

    // DateCreated looks like 2026-10-20T15:59:33.9510998Z; the first 19 characters are enough.
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun parseLogin(dto: AuthResponseDto): Pair<String, String> {
        val token = dto.accessToken?.takeIf(String::isNotBlank) ?: throw MissingFieldException("AccessToken", "login response")
        val userId = dto.user?.id?.takeIf(JellyfinApi::isItemId) ?: throw MissingFieldException("User.Id", "login response")
        return userId to token
    }

    fun parseCatalog(dto: ItemsPageDto, server: HttpUrl): CatalogPage {
        val entries = dto.items.mapNotNull { item ->
            val id = item.id?.takeIf(JellyfinApi::isItemId) ?: return@mapNotNull null
            val title = item.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (!JellyfinApi.isCatalogType(item.type)) return@mapNotNull null
            CatalogEntry(id, title, item.primaryImage(server))
        }
        val hasNext = dto.items.isNotEmpty() && dto.startIndex + dto.items.size < dto.totalRecordCount
        return CatalogPage(entries, hasNext)
    }

    /**
     * Details of a movie or series. An unknown id can resolve to a library folder instead of a
     * 404 (verified: the all-zero id returns "Media Folders"), so the returned id and type are checked.
     */
    fun parseDetails(dto: ItemDto, expectedId: String, server: HttpUrl): ItemDetails {
        if (!dto.id.equals(expectedId, ignoreCase = true) || !JellyfinApi.isCatalogType(dto.type)) {
            throw ContentUnavailableException("This item is no longer available on the Jellyfin server")
        }
        val title = dto.name?.takeIf(String::isNotBlank) ?: throw MissingFieldException("Name", "item $expectedId")
        val description = buildString {
            dto.overview?.let(::htmlToText)?.takeIf(String::isNotEmpty)?.let { append(it) }
            dto.productionYear?.let {
                if (isNotEmpty()) append("\n\n")
                append("Year: ").append(it)
            }
        }.ifEmpty { null }
        return ItemDetails(
            title = title,
            type = dto.type!!,
            description = description,
            genres = dto.genres.filter(String::isNotBlank),
            studios = dto.studios.mapNotNull { it.name?.takeIf(String::isNotBlank) }.joinToString().ifEmpty { null },
            thumbnailUrl = dto.primaryImage(server),
        )
    }

    /** A movie is its own single episode. */
    fun movieEpisode(dto: ItemDto, server: HttpUrl): EpisodeEntry {
        val id = dto.id?.takeIf(JellyfinApi::isItemId) ?: throw MissingFieldException("Id", "movie")
        return EpisodeEntry(id, dto.name?.takeIf(String::isNotBlank) ?: "Movie", 1f, dateOf(dto), dto.primaryImage(server))
    }

    /**
     * Series episodes across all seasons, numbered in server order (1..n) because season-local
     * numbers repeat; the name keeps the season/episode label. Returned in descending order, as
     * the app expects.
     */
    fun parseEpisodes(dto: ItemsPageDto, server: HttpUrl): List<EpisodeEntry> = dto.items
        .filter { it.type == "Episode" && JellyfinApi.isItemId(it.id) && !it.name.isNullOrBlank() }
        .mapIndexed { index, item ->
            val label = listOfNotNull(
                item.parentIndexNumber?.let { "S$it" },
                item.indexNumber?.let { "E$it" },
            ).joinToString("")
            EpisodeEntry(
                itemId = item.id!!,
                name = if (label.isEmpty()) item.name!! else "$label · ${item.name}",
                number = (index + 1).toFloat(),
                dateUpload = dateOf(item),
                previewUrl = item.primaryImage(server),
            )
        }
        .reversed()

    /** One direct-play video per media source (file/version), with its text subtitles. */
    fun parseVideos(dto: ItemDto, server: HttpUrl, headers: Headers?): List<Video> {
        val itemId = dto.id?.takeIf(JellyfinApi::isItemId) ?: throw InvalidResponseException("Item response without a valid id")
        return dto.mediaSources.mapNotNull { source ->
            val sourceId = source.id?.takeIf(JellyfinApi::isItemId) ?: return@mapNotNull null
            val height = source.mediaStreams.firstOrNull { it.type == "Video" }?.height?.takeIf { it > 0 }
            val subtitles = source.mediaStreams
                .filter { it.type == "Subtitle" }
                .mapNotNull { stream ->
                    JellyfinApi.subtitleUrl(server, itemId, sourceId, stream)?.let { Track(it, stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}") }
                }
            Video(
                videoUrl = JellyfinApi.streamUrl(server, itemId, sourceId),
                videoTitle = listOfNotNull(
                    source.name?.takeIf { dto.mediaSources.size > 1 },
                    height?.let { "${it}p" },
                    source.container?.uppercase(),
                    "Original",
                ).joinToString(" · "),
                resolution = height,
                headers = headers,
                subtitleTracks = subtitles,
                initialized = true,
            )
        }.sortedByDescending { it.resolution ?: 0 }
    }

    private fun ItemDto.primaryImage(server: HttpUrl): String? {
        val id = id?.takeIf(JellyfinApi::isItemId) ?: return null
        return imageTags["Primary"]?.let { JellyfinApi.imageUrl(server, id, it) }
    }

    private fun dateOf(item: ItemDto): Long = isoFormat.tryParse((item.premiereDate ?: item.dateCreated)?.take(19))
}
