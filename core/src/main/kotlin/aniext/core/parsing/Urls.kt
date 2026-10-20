package aniext.core.parsing

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val ALLOWED_SCHEMES = setOf("http", "https")
private val SCHEME_REGEX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/**
 * Resolves [href] (absolute, protocol-relative `//host/path`, root-relative or relative) against
 * [base]. Returns null for blank input, non-HTTP(S) schemes such as `javascript:`/`data:`, URLs
 * carrying credentials, and anything OkHttp cannot parse.
 */
fun resolveUrl(base: HttpUrl, href: String?): HttpUrl? {
    val candidate = href?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    SCHEME_REGEX.find(candidate)?.let { match ->
        if (match.groupValues[1].lowercase() !in ALLOWED_SCHEMES) return null
    }
    val resolved = base.resolve(candidate) ?: return null
    if (resolved.scheme !in ALLOWED_SCHEMES || resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
    return resolved
}

/** [resolveUrl] for string bases; null if [base] itself is not a valid HTTP(S) URL. */
fun resolveUrl(base: String, href: String?): String? = base.toHttpUrlOrNull()?.let { resolveUrl(it, href) }?.toString()

/** Parses [this] as an absolute HTTPS URL without credentials, or returns null. */
fun String.toSafeHttpsUrlOrNull(): HttpUrl? = trim().toHttpUrlOrNull()
    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
