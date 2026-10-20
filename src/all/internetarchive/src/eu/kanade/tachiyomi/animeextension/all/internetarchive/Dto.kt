package eu.kanade.tachiyomi.animeextension.all.internetarchive

import aniext.core.parsing.StringOrListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /advancedsearch.php?output=json`. On failure the API returns only [error]. */
@Serializable
class SearchResponseDto(
    val response: SearchResultsDto? = null,
    val error: String? = null,
)

@Serializable
class SearchResultsDto(
    val numFound: Int,
    val start: Int,
    val docs: List<SearchDocDto>,
)

@Serializable
class SearchDocDto(
    val identifier: String? = null,
    @Serializable(with = StringOrListSerializer::class)
    val title: List<String> = emptyList(),
)

/**
 * `GET /metadata/{identifier}`. Unknown identifiers return `{}`, so every field is optional.
 * Field shapes vary between items (a string in one, an array in another).
 */
@Serializable
class MetadataResponseDto(
    val metadata: ItemMetadataDto? = null,
    val files: List<FileDto> = emptyList(),
    @SerialName("is_dark")
    val isDark: Boolean = false,
)

@Serializable
class ItemMetadataDto(
    val identifier: String? = null,
    @Serializable(with = StringOrListSerializer::class)
    val title: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val description: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val creator: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val director: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val subject: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val date: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val licenseurl: List<String> = emptyList(),
    val publicdate: String? = null,
    @SerialName("access-restricted-item")
    val accessRestrictedItem: String? = null,
)

@Serializable
class FileDto(
    val name: String,
    val source: String? = null,
    val format: String? = null,
    val original: String? = null,
    val height: String? = null,
    val title: String? = null,
    val mtime: String? = null,
    val private: String? = null,
)
