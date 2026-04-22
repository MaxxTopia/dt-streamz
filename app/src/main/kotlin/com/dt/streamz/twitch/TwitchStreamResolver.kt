package com.dt.streamz.twitch

import android.util.Log
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Resolves a Twitch channel name to a playable HLS manifest URL using the
 * Twire pattern: fetch PlaybackAccessToken via GQL with the same persisted
 * query Twitch's web player uses, then compose the Usher URL by hand. No
 * Twitch player JS executes, so server-side ad-stitching paths that check
 * for the web player fingerprint don't fire — result is ad-free or very
 * close to it in most channels most of the time.
 *
 * Gracefully returns null on any error (offline channel, network blip,
 * Twitch-side persisted-query rotation, etc.) so the caller can toast.
 */
class TwitchStreamResolver(
    private val json: Json = Http.json,
) {

    suspend fun resolveHls(channel: String): String? = withContext(Dispatchers.IO) {
        val normalized = channel.trim().lowercase().ifEmpty { return@withContext null }

        val tokenBody = """
            {
              "operationName": "PlaybackAccessToken",
              "variables": {
                "isLive": true,
                "login": "$normalized",
                "isVod": false,
                "vodID": "",
                "playerType": "site"
              },
              "extensions": {
                "persistedQuery": {
                  "version": 1,
                  "sha256Hash": "${TwitchConfig.PLAYBACK_ACCESS_TOKEN_HASH}"
                }
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(TwitchConfig.GQL_URL)
            .header("Client-ID", TwitchConfig.CLIENT_ID)
            .header("Content-Type", "application/json")
            .post(tokenBody.toRequestBody(JSON_MEDIA))
            .build()

        val responseBody = runCatching {
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GQL PlaybackAccessToken -> HTTP ${resp.code} for $normalized")
                    return@use null
                }
                resp.body?.string()
            }
        }.onFailure { Log.w(TAG, "PAT fetch failed for $normalized", it) }.getOrNull()
            ?: return@withContext null

        val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObject }
            .getOrElse {
                Log.w(TAG, "PAT response parse failed", it)
                return@withContext null
            }
        val pat = parsed["data"]?.jsonObject
            ?.get("streamPlaybackAccessToken") as? JsonObject
            ?: run {
                Log.w(TAG, "no streamPlaybackAccessToken in PAT response: $responseBody")
                return@withContext null
            }

        val token = pat["value"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val signature = pat["signature"]?.jsonPrimitive?.contentOrNull ?: return@withContext null

        val usher = "${TwitchConfig.USHER_BASE}/$normalized.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", TwitchConfig.CLIENT_ID)
            .addQueryParameter("token", token)
            .addQueryParameter("sig", signature)
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("allow_spectre", "false")
            .addQueryParameter("fast_bread", "true")
            .addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("supported_codecs", "avc1")
            .addQueryParameter("p", (Math.random() * 9_999_999).toInt().toString())
            .build()
            .toString()

        Log.i(TAG, "resolved $normalized -> usher manifest (token len=${token.length})")
        usher
    }

    @Suppress("unused")
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "TwitchResolver"
        private val JSON_MEDIA = "application/json".toMediaTypeOrNull()
    }
}
