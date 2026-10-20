package eu.kanade.tachiyomi.animeextension.all.jellyfin

import kotlinx.serialization.Serializable

// Jellyfin answers `Accept: application/json` with PascalCase keys (verified against server
// 12.2); `jellyfinJson` maps them onto these camelCase properties.

@Serializable
class LoginRequestDto(
    val username: String,
    val pw: String,
)

@Serializable
class AuthResponseDto(
    val user: UserDto? = null,
    val accessToken: String? = null,
)

@Serializable
class UserDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
class ItemsPageDto(
    val items: List<ItemDto> = emptyList(),
    val totalRecordCount: Int = 0,
    val startIndex: Int = 0,
)

@Serializable
class ItemDto(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val overview: String? = null,
    val productionYear: Int? = null,
    val genres: List<String> = emptyList(),
    val studios: List<NameDto> = emptyList(),
    val imageTags: Map<String, String> = emptyMap(),
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val seriesName: String? = null,
    val dateCreated: String? = null,
    val premiereDate: String? = null,
    val mediaSources: List<MediaSourceDto> = emptyList(),
)

@Serializable
class NameDto(
    val name: String? = null,
)

@Serializable
class MediaSourceDto(
    val id: String? = null,
    val name: String? = null,
    val container: String? = null,
    val mediaStreams: List<MediaStreamDto> = emptyList(),
)

@Serializable
class MediaStreamDto(
    val type: String? = null,
    val index: Int? = null,
    val codec: String? = null,
    val language: String? = null,
    val displayTitle: String? = null,
    val height: Int? = null,
    val isExternal: Boolean = false,
)
