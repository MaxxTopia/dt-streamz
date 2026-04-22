package com.dt.streamz.scraper.megacloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Response shape from MegaCloud's getSources endpoint.
 *
 * When `encrypted = true`, `sources` is a base64 OpenSSL-compatible blob
 * (Salted__ prefix + salt + ciphertext) that AES-256-CBC decrypts to
 * `List<SourceFile>`.
 *
 * When `encrypted = false`, `sources` deserializes directly to
 * `List<SourceFile>`.
 */
@Serializable
internal data class MegaSourcesResponse(
    val sources: JsonElement? = null,
    val encrypted: Boolean = true,
    val tracks: List<MegaTrack> = emptyList(),
    val intro: MegaSegment? = null,
    val outro: MegaSegment? = null,
    val server: Int = 0,
)

@Serializable
internal data class MegaSourceFile(
    val file: String = "",
    val type: String? = null,
)

@Serializable
internal data class MegaTrack(
    val file: String = "",
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean = false,
    @SerialName("forced") val forced: Boolean = false,
)

@Serializable
internal data class MegaSegment(
    val start: Int = 0,
    val end: Int = 0,
)
