package eu.kanade.tachiyomi.animeextension.all.jellyfin

import aniext.core.parsing.sourceJson
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** Decodes Jellyfin's PascalCase JSON into camelCase properties. */
internal val jellyfinJson: Json = Json(sourceJson) {
    namingStrategy = object : JsonNamingStrategy {
        override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String) = serialName.replaceFirstChar(Char::uppercaseChar)
    }
}

/**
 * Request construction for the documented Jellyfin REST API (https://api.jellyfin.org).
 * Every endpoint used here was exercised against a Jellyfin 12.2 server.
 */
internal object JellyfinApi {
    const val PAGE_SIZE = 50
    const val CLIENT_NAME = "Anime Source Extensions"
    const val DETAIL_FIELDS = "Overview,Genres,Studios,ProductionYear,DateCreated"
    const val EPISODE_FIELDS = "DateCreated,Overview"
    const val VIDEO_FIELDS = "MediaSources,DateCreated"

    private val ITEM_ID = Regex("[0-9a-fA-F]{32}")
    private val CATALOG_TYPES = setOf("Movie", "Series")
    private val TEXT_SUBTITLE_CODECS = setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text")

    enum class Sort(val label: String, val sortBy: String, val order: String) {
        NAME("Name", "SortName", "Ascending"),
        RECENTLY_ADDED("Recently added", "DateCreated,SortName", "Descending"),
        RELEASE("Release date", "PremiereDate,ProductionYear,SortName", "Descending"),
        RATING("Community rating", "CommunityRating,SortName", "Descending"),
    }

    enum class Kind(val label: String, val includeItemTypes: String) {
        ALL("Movies and series", "Movie,Series"),
        MOVIES("Movies", "Movie"),
        SERIES("Series", "Series"),
    }

    /**
     * Normalizes a user-entered server address. Plain HTTP is accepted because personal servers
     * commonly run on a LAN; URLs with credentials, queries or fragments are rejected.
     */
    fun parseServerUrl(raw: String?): HttpUrl? {
        val url = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return url
    }

    fun isItemId(id: String?): Boolean = id != null && ITEM_ID.matches(id)

    /** `MediaBrowser` authorization header; values are URL-encoded as the official SDKs do. */
    fun authorizationHeader(deviceId: String, token: String? = null): String = listOfNotNull(
        "Client" to CLIENT_NAME,
        "Device" to "Android",
        "DeviceId" to deviceId,
        "Version" to "1",
        token?.let { "Token" to it },
    ).joinToString(", ", prefix = "MediaBrowser ") { (key, value) -> "$key=\"${URLEncoder.encode(value, "UTF-8")}\"" }

    /** `POST /Users/AuthenticateByName`; the response carries the access token and user. */
    fun loginRequest(server: HttpUrl, baseHeaders: Headers, deviceId: String, username: String, password: String): Request = Request.Builder()
        .url(server.newBuilder().addPathSegments("Users/AuthenticateByName").build())
        .headers(baseHeaders)
        .header("Authorization", authorizationHeader(deviceId))
        .post(jellyfinJson.encodeToString(LoginRequestDto(username, password)).toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    // The user is inferred from the access token (verified), so URLs carry no user id.
    fun catalogUrl(server: HttpUrl, page: Int, sort: Sort, kind: Kind, searchTerm: String? = null): HttpUrl {
        require(page >= 1) { "page is 1-based" }
        return server.newBuilder()
            .addPathSegment("Items")
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", kind.includeItemTypes)
            .addQueryParameter("SortBy", sort.sortBy)
            .addQueryParameter("SortOrder", sort.order)
            .addQueryParameter("StartIndex", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("Limit", PAGE_SIZE.toString())
            .addQueryParameter("EnableImageTypes", "Primary")
            .addQueryParameter("ImageTypeLimit", "1")
            .apply { searchTerm?.trim()?.takeIf(String::isNotEmpty)?.let { addQueryParameter("SearchTerm", it) } }
            .build()
    }

    fun itemUrl(server: HttpUrl, itemId: String, fields: String): HttpUrl = server.newBuilder()
        .addPathSegment("Items")
        .addPathSegment(requireItemId(itemId))
        .addQueryParameter("Fields", fields)
        .build()

    fun episodesUrl(server: HttpUrl, seriesId: String): HttpUrl = server.newBuilder()
        .addPathSegment("Shows")
        .addPathSegment(requireItemId(seriesId))
        .addPathSegment("Episodes")
        .addQueryParameter("Fields", EPISODE_FIELDS)
        .build()

    fun imageUrl(server: HttpUrl, itemId: String, tag: String): String = server.newBuilder()
        .addPathSegments("Items/${requireItemId(itemId)}/Images/Primary")
        .addQueryParameter("tag", tag)
        .addQueryParameter("maxHeight", "600")
        .build()
        .toString()

    /** Original file, no transcoding. Authorization travels in a header, never in the URL. */
    fun streamUrl(server: HttpUrl, itemId: String, mediaSourceId: String): String = server.newBuilder()
        .addPathSegments("Videos/${requireItemId(itemId)}/stream")
        .addQueryParameter("static", "true")
        .addQueryParameter("mediaSourceId", mediaSourceId)
        .build()
        .toString()

    /** Text subtitles converted by the server; ASS/SSA keep their styling. Image subtitles return null. */
    fun subtitleUrl(server: HttpUrl, itemId: String, mediaSourceId: String, stream: MediaStreamDto): String? {
        val codec = stream.codec?.lowercase() ?: return null
        val index = stream.index ?: return null
        if (codec !in TEXT_SUBTITLE_CODECS) return null
        val format = if (codec == "ass" || codec == "ssa") "ass" else "srt"
        return server.newBuilder()
            .addPathSegments("Videos/${requireItemId(itemId)}/$mediaSourceId/Subtitles/$index/0/Stream.$format")
            .build()
            .toString()
    }

    /** Stored in `SAnime.url`/`SEpisode.url`: server-independent, survives address changes. */
    fun itemPath(itemId: String): String = "/Items/${requireItemId(itemId)}"

    fun idFromPath(path: String): String = path.removePrefix("/Items/").also { requireItemId(it) }

    fun isCatalogType(type: String?): Boolean = type in CATALOG_TYPES

    private fun requireItemId(id: String): String {
        require(isItemId(id)) { "Invalid Jellyfin item id" }
        return id
    }
}
