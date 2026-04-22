package com.dt.streamz.scraper.anicrush

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ApiEnvelope<T>(
    val status: Boolean = false,
    val result: T? = null,
    val message: String? = null,
)

@Serializable
internal data class SearchEnvelope(
    val movies: List<MovieDto> = emptyList(),
    val totalPage: Int = 1,
)

@Serializable
internal data class MovieDto(
    val id: String = "",
    @SerialName("slug") val slug: String? = null,
    @SerialName("name") val name: String = "",
    @SerialName("name_english") val nameEnglish: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("aired_from") val airedFrom: String? = null,
    @SerialName("year") val year: Int? = null,
)

@Serializable
internal data class EpisodeListDto(
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
internal data class EpisodeDto(
    val id: String = "",
    @SerialName("number") val number: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("runtime") val runtime: Int? = null,
)

@Serializable
internal data class SourcesDto(
    val link: String? = null,
    val server: Int? = null,
    val type: String? = null,
)
