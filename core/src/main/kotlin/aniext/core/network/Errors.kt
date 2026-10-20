package aniext.core.network

import okhttp3.HttpUrl
import okhttp3.Response

/**
 * Base type for failures a source reports on purpose. Messages are shown to the user by the app,
 * so they must be short, actionable and never contain cookies, tokens or query strings.
 */
open class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server answered with a non-success status. [endpoint] is redacted (no query/credentials). */
class HttpStatusException(val code: Int, val endpoint: String) : SourceException("HTTP $code from $endpoint") {
    val isRateLimited: Boolean get() = code == 429
}

/** The response could not be decoded into the expected structure. */
class InvalidResponseException(message: String, cause: Throwable? = null) : SourceException(message, cause)

/** A field the source cannot work without (title, identifier, URL, …) was absent or blank. */
class MissingFieldException(val field: String, context: String) : SourceException("Required field '$field' is missing in $context")

/** The requested entry exists but is removed, restricted or otherwise not publicly accessible. */
class ContentUnavailableException(message: String) : SourceException(message)

/** `scheme://host[:port]/path` without user info, query or fragment, safe to put in messages. */
fun HttpUrl.redacted(): String = buildString {
    append(scheme).append("://").append(host)
    if (port != HttpUrl.defaultPort(scheme)) append(':').append(port)
    append(encodedPath)
}

/** Returns this response if it is successful, otherwise closes it and throws [HttpStatusException]. */
fun Response.requireSuccess(): Response {
    if (isSuccessful) return this
    close()
    throw HttpStatusException(code, request.url.redacted())
}
