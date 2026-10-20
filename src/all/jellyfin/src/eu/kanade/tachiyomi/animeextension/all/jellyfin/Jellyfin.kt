package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import aniext.core.network.SourceException
import aniext.core.network.withRetry
import aniext.core.parsing.parseAs
import aniext.core.prefs.sourcePreferences
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.UUID

/**
 * A personal Jellyfin media server (https://jellyfin.org), configured by the user in the
 * extension settings, accessed through the documented Jellyfin REST API. Plays the original files
 * the user's own server provides; no transcoding is requested.
 */
class Jellyfin :
    AnimeHttpSource(),
    ConfigurableAnimeSource,
    UnmeteredSource {

    override val name = "Jellyfin"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy { sourcePreferences() }

    /** Empty until configured, as for other user-configured sources in the catalog. */
    override val baseUrl: String
        get() = server()?.toString()?.trimEnd('/').orEmpty()

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addInterceptor(AuthInterceptor()).build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Accept", "application/json")

    // ============================== Catalog ===============================

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page, JellyfinApi.Sort.NAME, JellyfinApi.Kind.ALL)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, JellyfinApi.Sort.RECENTLY_ADDED, JellyfinApi.Kind.ALL)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalog(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = catalogRequest(page, filters.selectedSort(), filters.selectedKind(), query)

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    override fun getFilterList(): AnimeFilterList = filterList()

    private fun catalogRequest(page: Int, sort: JellyfinApi.Sort, kind: JellyfinApi.Kind, query: String? = null): Request = GET(JellyfinApi.catalogUrl(requireServer(), page, sort, kind, query), headers)

    private fun parseCatalog(response: Response): AnimesPage {
        val page = JellyfinParser.parseCatalog(response.parseAs<ItemsPageDto>(jellyfinJson), requireServer())
        return AnimesPage(
            page.entries.map { entry ->
                SAnime.create().apply {
                    url = JellyfinApi.itemPath(entry.id)
                    title = entry.title
                    thumbnail_url = entry.thumbnailUrl
                }
            },
            page.hasNextPage,
        )
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = JellyfinApi.idFromPath(anime.url)
        val server = requireServer()
        val details = JellyfinParser.parseDetails(fetchItem(server, id, JellyfinApi.DETAIL_FIELDS), id, server)
        return SAnime.create().apply {
            url = anime.url
            title = details.title
            description = details.description
            genre = details.genres.joinToString().ifEmpty { null }
            author = details.studios
            thumbnail_url = details.thumbnailUrl
            status = if (details.type == "Movie") SAnime.COMPLETED else SAnime.UNKNOWN
            initialized = true
        }
    }

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/web/#/details?id=${JellyfinApi.idFromPath(anime.url)}"

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = JellyfinApi.idFromPath(anime.url)
        val server = requireServer()
        val item = fetchItem(server, id, JellyfinApi.DETAIL_FIELDS)
        val entries = when (item.type) {
            "Movie" -> listOf(JellyfinParser.movieEpisode(item, server))
            "Series" -> JellyfinParser.parseEpisodes(fetch(JellyfinApi.episodesUrl(server, id)), server)
            else -> emptyList()
        }
        return entries.map { entry ->
            SEpisode.create().apply {
                url = JellyfinApi.itemPath(entry.itemId)
                name = entry.name
                episode_number = entry.number
                date_upload = entry.dateUpload
                preview_url = entry.previewUrl
            }
        }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = "$baseUrl/web/#/details?id=${JellyfinApi.idFromPath(episode.url)}"

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Hosters/Videos ==========================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val server = requireServer()
        val item = fetchItem(server, JellyfinApi.idFromPath(episode.url), JellyfinApi.VIDEO_FIELDS)
        return JellyfinParser.parseVideos(item, server, playbackHeaders()).toHosterList()
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    /** The player fetches streams itself, so it needs the token as a header (never in the URL). */
    private fun playbackHeaders(): Headers {
        val token = preferences.getString(PREF_TOKEN, null) ?: throw SourceException("Not logged in to the Jellyfin server")
        return headers.newBuilder().set("Authorization", JellyfinApi.authorizationHeader(deviceId(), token)).build()
    }

    // ============================== Network ===============================

    private suspend fun fetchItem(server: HttpUrl, id: String, fields: String): ItemDto = fetch(JellyfinApi.itemUrl(server, id, fields))

    private suspend inline fun <reified T> fetch(url: HttpUrl): T = withRetry {
        client.newCall(GET(url, headers)).awaitSuccess().parseAs<T>(jellyfinJson)
    }

    private fun server(): HttpUrl? = JellyfinApi.parseServerUrl(preferences.getString(PREF_SERVER, null))

    private fun requireServer(): HttpUrl = server() ?: throw SourceException("Set the Jellyfin server address in the extension settings")

    private fun deviceId(): String = preferences.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(PREF_DEVICE_ID, it).apply()
    }

    /**
     * Adds the access token to requests for the configured server, logging in when there is no
     * token or the server answers 401. Errors are IOExceptions so OkHttp reports them normally.
     */
    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val server = server() ?: return chain.proceed(request)
            if (request.url.host != server.host || request.url.port != server.port) return chain.proceed(request)

            val token = preferences.getString(PREF_TOKEN, null) ?: login(chain, server)
            val response = chain.proceed(request.withToken(token))
            if (response.code != 401) return response
            response.close()
            return chain.proceed(request.withToken(login(chain, server)))
        }

        private fun Request.withToken(token: String) = newBuilder().header("Authorization", JellyfinApi.authorizationHeader(deviceId(), token)).build()

        @Synchronized
        private fun login(chain: Interceptor.Chain, server: HttpUrl): String {
            val username = preferences.getString(PREF_USERNAME, null)?.takeIf(String::isNotBlank) ?: throw JellyfinAuthException("Set your Jellyfin username in the extension settings")
            val password = preferences.getString(PREF_PASSWORD, null).orEmpty()
            val request = JellyfinApi.loginRequest(server, headers, deviceId(), username, password)
            val (_, token) = chain.proceed(request).use { response ->
                when {
                    response.code == 401 -> throw JellyfinAuthException("Jellyfin rejected the username or password")
                    !response.isSuccessful -> throw JellyfinAuthException("Jellyfin login failed with HTTP ${response.code}")
                    else -> JellyfinParser.parseLogin(response.parseAs<AuthResponseDto>(jellyfinJson))
                }
            }
            preferences.edit().putString(PREF_TOKEN, token).apply()
            return token
        }
    }

    private class JellyfinAuthException(message: String) : IOException(message)

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            textPreference(screen, PREF_SERVER, "Server address", "e.g. https://jellyfin.example.org or http://192.168.1.10:8096") { value ->
                JellyfinApi.parseServerUrl(value) != null
            },
        )
        screen.addPreference(textPreference(screen, PREF_USERNAME, "Username", "Jellyfin account name") { it.isNotBlank() })
        screen.addPreference(
            textPreference(screen, PREF_PASSWORD, "Password", "Stored on this device only", password = true) { true },
        )
    }

    private fun textPreference(
        screen: PreferenceScreen,
        key: String,
        title: String,
        summary: String,
        password: Boolean = false,
        isValid: (String) -> Boolean,
    ) = EditTextPreference(screen.context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        dialogTitle = title
        if (password) {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        }
        // Any change of server or account invalidates the stored session token.
        setOnPreferenceChangeListener { _, newValue ->
            val accepted = isValid(newValue as String)
            if (accepted) preferences.edit().remove(PREF_TOKEN).apply()
            accepted
        }
    }

    private companion object {
        const val PREF_SERVER = "server_url"
        const val PREF_USERNAME = "username"
        const val PREF_PASSWORD = "password"
        const val PREF_TOKEN = "access_token"
        const val PREF_DEVICE_ID = "device_id"
    }
}
