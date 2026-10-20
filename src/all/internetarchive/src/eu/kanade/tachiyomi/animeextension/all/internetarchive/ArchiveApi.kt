package eu.kanade.tachiyomi.animeextension.all.internetarchive

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * URL and query construction for the public Internet Archive APIs:
 * - Advanced Search: https://archive.org/advancedsearch.php
 * - Metadata API:    https://archive.org/developers/md-read.html
 * - Item images:     https://archive.org/developers/tutorial-get-ia-item-image.html
 */
internal object ArchiveApi {
    const val BASE_URL = "https://archive.org"
    const val HOST = "archive.org"
    const val PAGE_SIZE = 50

    /** Advanced Search refuses to page past this many results ([DEEP_PAGING] error). */
    const val MAX_RESULT_WINDOW = 10_000

    private val IDENTIFIER_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val LUCENE_SPECIAL = Regex("""[+\-!(){}\[\]^"~*?:\\/&|]""")

    enum class Collection(val label: String, val id: String) {
        ANIMATION("Animation & Cartoons", "animationandcartoons"),
        CLASSIC("Classic Cartoons", "classic_cartoons"),
        MORE_ANIMATION("More Animation", "more_animation"),
    }

    enum class Sort(val label: String, val param: String?) {
        DOWNLOADS("Most downloaded", "downloads desc"),
        RELEVANCE("Relevance", null),
        RECENTLY_ADDED("Recently added", "addeddate desc"),
        TITLE("Title (A-Z)", "titleSorter asc"),
        OLDEST("Oldest first", "date asc"),
    }

    class SearchParams(
        val terms: String = "",
        val collection: Collection = Collection.ANIMATION,
        val sort: Sort = Sort.DOWNLOADS,
        val openLicenseOnly: Boolean = false,
    )

    fun isValidIdentifier(identifier: String): Boolean = identifier.length <= 100 && IDENTIFIER_REGEX.matches(identifier)

    /** Escapes Lucene syntax so user input is always matched as plain words. */
    fun escapeTerms(terms: String): String = terms.trim().replace(LUCENE_SPECIAL) { "\\${it.value}" }

    /** Restricted (lending/withheld) items are excluded server-side and again when parsing. */
    fun buildQuery(params: SearchParams): String = buildString {
        append("mediatype:(movies) AND collection:(").append(params.collection.id).append(')')
        append(" AND NOT access-restricted-item:(true)")
        if (params.openLicenseOnly) append(" AND licenseurl:*")
        val terms = escapeTerms(params.terms)
        if (terms.isNotEmpty()) append(" AND (").append(terms).append(')')
    }

    fun searchUrl(params: SearchParams, page: Int): HttpUrl {
        require(page >= 1) { "page is 1-based" }
        return "$BASE_URL/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", buildQuery(params))
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .apply { params.sort.param?.let { addQueryParameter("sort[]", it) } }
            .addQueryParameter("rows", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("output", "json")
            .build()
    }

    fun metadataUrl(identifier: String): HttpUrl = "$BASE_URL/metadata".toHttpUrl().newBuilder()
        .addPathSegment(requireIdentifier(identifier))
        .build()

    fun thumbnailUrl(identifier: String): String = "$BASE_URL/services/img/${requireIdentifier(identifier)}"

    /** Stable relative URL stored in `SAnime.url`; also the item's public web page. */
    fun itemPath(identifier: String): String = "/details/${requireIdentifier(identifier)}"

    /** Stable relative URL stored in `SEpisode.url`; the web player page for one file. */
    fun filePath(identifier: String, fileName: String): String = "$BASE_URL/details".toHttpUrl().newBuilder()
        .addPathSegment(requireIdentifier(identifier))
        .addPathSegments(fileName)
        .build()
        .encodedPath

    fun downloadUrl(identifier: String, fileName: String): String = "$BASE_URL/download".toHttpUrl().newBuilder()
        .addPathSegment(requireIdentifier(identifier))
        .addPathSegments(fileName)
        .build()
        .toString()

    /** Splits an [itemPath] or [filePath] back into identifier and (optional) decoded file name. */
    fun parsePath(path: String): Pair<String, String?> {
        val segments = "$BASE_URL$path".toHttpUrl().pathSegments
        require(segments.size >= 2 && segments[0] == "details") { "Not an Internet Archive item path: $path" }
        val identifier = requireIdentifier(segments[1])
        val file = segments.drop(2).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { null }
        return identifier to file
    }

    private fun requireIdentifier(identifier: String): String {
        require(isValidIdentifier(identifier)) { "Invalid Internet Archive identifier" }
        return identifier
    }
}
