package com.dt.streamz.twitch

/**
 * Twitch integration constants. CLIENT_ID is the publicly known ID used by
 * Twitch's own web player — Twire / TTV-LOL / and every open-source Twitch
 * client ship the same value. It's an unauth'd read-only viewer ID; no
 * personal credentials attached. Override via remote ScraperConfig later
 * if Twitch ever rotates it.
 */
object TwitchConfig {
    const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    const val GQL_URL = "https://gql.twitch.tv/gql"
    const val USHER_BASE = "https://usher.ttvnw.net/api/channel/hls"

    /**
     * persistedQuery sha256 for the PlaybackAccessToken operation. Twitch's
     * Apollo persisted-query registry; updated occasionally when Twitch
     * re-compiles their frontend. If playback starts 404ing one day, this
     * is the first thing to re-check.
     */
    const val PLAYBACK_ACCESS_TOKEN_HASH =
        "0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712"

    /**
     * Starter list of channels pinned into the Twitch tab. User's primary
     * use case is `aussieantics`. More can be added through Settings
     * (Phase 9 proper) or via ScraperConfig in the meantime.
     */
    val PINNED_CHANNELS = listOf(
        "aussieantics",
    )
}
