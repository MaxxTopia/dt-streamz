package com.dt.streamz.twitch

/** A public Twitch user returned by the channel search surface. */
data class TwitchChannel(
    val login: String,
    val displayName: String,
    val profileImageUrl: String? = null,
)

/** A saved Twitch broadcast (VOD) from a channel's archive. */
data class TwitchVod(
    val id: String,
    val title: String,
    val createdAt: String,
    val lengthSeconds: Int,
    val thumbnailUrl: String? = null,
)

data class TwitchVodPage(
    val vods: List<TwitchVod>,
    val endCursor: String? = null,
    val hasNextPage: Boolean = false,
)
