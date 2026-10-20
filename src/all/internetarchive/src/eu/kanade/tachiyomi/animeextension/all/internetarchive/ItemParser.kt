package eu.kanade.tachiyomi.animeextension.all.internetarchive

import aniext.core.network.ContentUnavailableException
import aniext.core.network.InvalidResponseException
import aniext.core.network.MissingFieldException
import aniext.core.parsing.htmlToText
import aniext.core.parsing.naturalOrder
import aniext.core.parsing.tryParse
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A title in a result page. */
internal class CatalogEntry(val identifier: String, val title: String) {
    val path: String get() = ArchiveApi.itemPath(identifier)
    val thumbnailUrl: String get() = ArchiveApi.thumbnailUrl(identifier)
}

internal class CatalogPage(val entries: List<CatalogEntry>, val hasNextPage: Boolean)

internal class ItemDetails(
    val title: String,
    val description: String?,
    val creator: String?,
    val director: String?,
    val genres: List<String>,
)

/** One playable work in an item, possibly available as several files/renditions. */
internal class EpisodeEntry(
    val path: String,
    val name: String,
    val number: Float,
    val dateUpload: Long,
)

/**
 * Interprets Internet Archive API responses. Kept free of extensions-lib stubs (except the plain
 * `Video`/`Track` data classes) so it is unit-testable against recorded fixtures.
 */
internal object ItemParser {

    /** IA `format` values of video renditions, best-supported first. */
    private val VIDEO_FORMATS = listOf(
        "h.264 HD", "h.264", "h.264 IA", "MPEG4", "512Kb MPEG4", "WebM", "Matroska", "Ogg Video",
        "QuickTime", "MPEG2", "MPEG1", "DivX", "AVI", "Windows Media", "Cinepack",
    )
    private val SUBTITLE_FORMATS = setOf("SubRip", "Web Video Text Tracks")
    private val SUBTITLE_EXTENSIONS = listOf(".srt", ".vtt")
    private const val AUTO_CAPTION_SUFFIX = ".asr"
    private val SUBJECT_SEPARATORS = Regex("[;,]")

    private val publicDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ============================== Search ===============================

    fun parseSearch(dto: SearchResponseDto, page: Int): CatalogPage {
        dto.error?.let { throw InvalidResponseException("Internet Archive search failed: ${it.take(200)}") }
        val results = dto.response ?: throw MissingFieldException("response", "search result")
        val entries = results.docs.mapNotNull { doc ->
            val identifier = doc.identifier?.takeIf(ArchiveApi::isValidIdentifier) ?: return@mapNotNull null
            val title = doc.title.firstOrNull() ?: return@mapNotNull null
            CatalogEntry(identifier, title)
        }
        val seen = results.start + results.docs.size
        val hasNext = results.docs.isNotEmpty() &&
            seen < minOf(results.numFound, ArchiveApi.MAX_RESULT_WINDOW) &&
            page * ArchiveApi.PAGE_SIZE < ArchiveApi.MAX_RESULT_WINDOW
        return CatalogPage(entries, hasNext)
    }

    // ============================== Details ==============================

    /** Returns the item metadata, or throws if the item is unknown, dark or access-restricted. */
    fun requireAvailable(dto: MetadataResponseDto, identifier: String): ItemMetadataDto {
        val metadata = dto.metadata ?: throw ContentUnavailableException("Item '$identifier' does not exist or was removed")
        if (dto.isDark) throw ContentUnavailableException("Item '$identifier' is not publicly available")
        if (metadata.accessRestrictedItem.equals("true", ignoreCase = true)) {
            throw ContentUnavailableException("Item '$identifier' is access-restricted (lending library) and cannot be streamed")
        }
        return metadata
    }

    fun parseDetails(dto: MetadataResponseDto, identifier: String): ItemDetails {
        val metadata = requireAvailable(dto, identifier)
        val title = metadata.title.firstOrNull() ?: throw MissingFieldException("title", "item '$identifier'")
        val description = buildString {
            metadata.description.joinToString("\n\n").let(::htmlToText).takeIf(String::isNotEmpty)?.let { append(it) }
            metadata.date.firstOrNull()?.let { appendSection("Date: $it") }
            metadata.licenseurl.firstOrNull()?.let { appendSection("License: $it") }
        }.ifEmpty { null }
        val genres = metadata.subject
            .flatMap { it.split(SUBJECT_SEPARATORS) }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
        return ItemDetails(
            title = title,
            description = description,
            creator = metadata.creator.joinToString().ifEmpty { null },
            director = metadata.director.joinToString().ifEmpty { null },
            genres = genres,
        )
    }

    private fun StringBuilder.appendSection(line: String) {
        if (isNotEmpty()) append("\n\n")
        append(line)
    }

    // ============================== Episodes =============================

    /**
     * Groups an item's public video files into works. A work is identified by the file-name stem
     * of its original upload, so `X.mp4`, `X.mkv` and the derivative `X.ia.mp4` (original `X.mp4`)
     * form one episode. Returned in descending order, as the app expects.
     */
    fun parseEpisodes(dto: MetadataResponseDto, identifier: String): List<EpisodeEntry> {
        val metadata = requireAvailable(dto, identifier)
        val byName = dto.files.associateBy(FileDto::name)
        val groups = playableFiles(dto)
            .groupBy { stemOf(it.rootName) }
            .mapValues { (_, files) -> files.map { it.rootName }.distinct().sortedWith(naturalOrder) }
            .toSortedMap(naturalOrder)

        val fallbackDate = publicDateFormat.tryParse(metadata.publicdate)
        return groups.entries.mapIndexed { index, (stem, roots) ->
            val representative = roots.first()
            val original = byName[representative]
            EpisodeEntry(
                path = ArchiveApi.filePath(identifier, representative),
                name = original?.title?.trim()?.takeIf(String::isNotEmpty) ?: stem,
                number = (index + 1).toFloat(),
                dateUpload = original?.mtime?.toLongOrNull()?.times(1000) ?: fallbackDate,
            )
        }.reversed()
    }

    // ============================== Videos ===============================

    /** Every public rendition of the work [fileName] belongs to, best quality first. */
    fun parseVideos(dto: MetadataResponseDto, identifier: String, fileName: String, headers: Headers?): List<Video> {
        requireAvailable(dto, identifier)
        val stem = stemOf(fileName)
        val subtitles = subtitleTracks(dto, identifier, stem)
        return playableFiles(dto)
            .filter { stemOf(it.rootName) == stem }
            .sortedWith(
                compareByDescending<FileDto> { it.heightOrNull ?: 0 }
                    .thenBy { VIDEO_FORMATS.indexOf(it.format) }
                    .thenComparing(FileDto::name, naturalOrder),
            )
            .map { file ->
                val height = file.heightOrNull
                Video(
                    videoUrl = ArchiveApi.downloadUrl(identifier, file.name),
                    videoTitle = listOfNotNull(file.format, height?.let { "${it}p" }).joinToString(" · "),
                    resolution = height,
                    headers = headers,
                    subtitleTracks = subtitles,
                    initialized = true,
                )
            }
    }

    private fun subtitleTracks(dto: MetadataResponseDto, identifier: String, stem: String): List<Track> = dto.files
        .filter { it.format in SUBTITLE_FORMATS && !it.isPrivate }
        .mapNotNull { file ->
            val base = SUBTITLE_EXTENSIONS.firstOrNull { file.name.endsWith(it, ignoreCase = true) }
                ?.let { file.name.dropLast(it.length) } ?: return@mapNotNull null
            val automatic = base.endsWith(AUTO_CAPTION_SUFFIX, ignoreCase = true)
            if (base.removeSuffix(AUTO_CAPTION_SUFFIX) != stem) return@mapNotNull null
            val label = file.title?.trim()?.takeIf(String::isNotEmpty) ?: if (automatic) "Subtitles (auto-generated)" else "Subtitles"
            Track(ArchiveApi.downloadUrl(identifier, file.name), label)
        }
        .sortedBy { it.lang.contains("auto-generated") }

    // ============================== Helpers ==============================

    private fun playableFiles(dto: MetadataResponseDto): List<FileDto> = dto.files.filter { it.format in VIDEO_FORMATS && !it.isPrivate }

    private val FileDto.isPrivate: Boolean get() = private.equals("true", ignoreCase = true)

    private val FileDto.heightOrNull: Int? get() = height?.toIntOrNull()?.takeIf { it > 0 }

    /** The uploaded file a derivative was generated from (or the file itself). */
    private val FileDto.rootName: String get() = original?.takeIf { source == "derivative" } ?: name

    private fun stemOf(fileName: String): String {
        val leaf = fileName.substringAfterLast('/')
        val dot = leaf.lastIndexOf('.')
        return if (dot > 0) fileName.dropLast(leaf.length - dot) else fileName
    }
}
