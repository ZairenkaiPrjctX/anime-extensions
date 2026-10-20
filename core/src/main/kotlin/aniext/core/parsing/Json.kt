package aniext.core.parsing

import aniext.core.network.InvalidResponseException
import aniext.core.network.redacted
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response

/** Tolerant decoder for third-party APIs: unknown keys are ignored, absent optionals stay null. */
val sourceJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Decodes [this] as [T]. Malformed or mismatching JSON becomes an [InvalidResponseException]
 * naming [context] instead of a bare serialization stack trace.
 */
inline fun <reified T> String.parseAs(context: String = "response", json: Json = sourceJson): T = try {
    json.decodeFromString<T>(this)
} catch (e: SerializationException) {
    throw InvalidResponseException("Unexpected data in $context: ${e.message?.lineSequence()?.firstOrNull()}", e)
} catch (e: IllegalArgumentException) {
    throw InvalidResponseException("Unexpected data in $context", e)
}

/** Reads and closes the body, decoding it as [T]. The error context is the redacted request URL. */
inline fun <reified T> Response.parseAs(json: Json = sourceJson): T = use {
    it.body.string().parseAs<T>(it.request.url.redacted(), json)
}

/**
 * Accepts a JSON string, an array of scalars, or null and always yields a list of non-blank
 * strings. Many catalog APIs (e.g. Internet Archive metadata) switch between the two shapes.
 */
object StringOrListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return when (val element = input.decodeJsonElement()) {
            is JsonNull -> emptyList()
            is JsonPrimitive -> listOf(element.content)
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
            is JsonObject -> throw SerializationException("Expected a string or an array, found an object")
        }.map(String::trim).filter(String::isNotEmpty)
    }

    override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)
}
