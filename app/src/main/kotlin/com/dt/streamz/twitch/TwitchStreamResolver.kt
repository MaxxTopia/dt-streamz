package com.dt.streamz.twitch

import android.util.Log
import com.dt.streamz.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

        val token = fetchPlaybackToken(normalized, isLive = true, vodId = "")
            ?: return@withContext null
        "${TwitchConfig.USHER_BASE}/$normalized.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", TwitchConfig.CLIENT_ID)
            .addQueryParameter("token", token.value)
            .addQueryParameter("sig", token.signature)
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

            .also { Log.i(TAG, "resolved $normalized -> usher manifest") }
    }

    /** Resolve one archived broadcast to the same native HLS player as live TV. */
    suspend fun resolveVodHls(channel: String, vodId: String): String? =
        withContext(Dispatchers.IO) {
            val normalized = channel.trim().lowercase().ifEmpty { return@withContext null }
            val normalizedVodId = vodId.trim().ifEmpty { return@withContext null }
            val token = fetchPlaybackToken(normalized, isLive = false, vodId = normalizedVodId)
                ?: return@withContext null
            "${TwitchConfig.USHER_VOD_BASE}/$normalizedVodId.m3u8".toHttpUrl().newBuilder()
                .addQueryParameter("nauth", token.value)
                .addQueryParameter("nauthsig", token.signature)
                .addQueryParameter("allow_source", "true")
                .addQueryParameter("allow_audio_only", "true")
                .addQueryParameter("fast_bread", "true")
                .addQueryParameter("player_backend", "mediaplayer")
                .addQueryParameter("playlist_include_framerate", "true")
                .addQueryParameter("p", (Math.random() * 9_999_999).toInt().toString())
                .build()
                .toString()
                .also { Log.i(TAG, "resolved VOD $normalizedVodId for $normalized") }
        }

    /** Search public Twitch channels by login/display name. */
    suspend fun searchChannels(query: String, limit: Int = 10): List<TwitchChannel> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()
            val root = postGraphQl(
                query = SEARCH_QUERY,
                variables = buildJsonObject {
                    put("userQuery", JsonPrimitive(clean))
                    put("platform", JsonPrimitive("web"))
                },
            ) ?: return@withContext emptyList()
            val items = root["data"]?.jsonObject
                ?.get("searchFor")?.jsonObject
                ?.get("channels")?.jsonObject
                ?.get("items") as? JsonArray
                ?: return@withContext emptyList()
            items.mapNotNull { element ->
                val user = element as? JsonObject ?: return@mapNotNull null
                val login = user.string("login") ?: return@mapNotNull null
                TwitchChannel(
                    login = login,
                    displayName = user.string("displayName") ?: login,
                    profileImageUrl = user.string("profileImageURL"),
                )
            }.distinctBy { it.login.lowercase() }.take(limit.coerceIn(1, 25))
        }

    /** Fetch a page of saved broadcasts for one public channel. */
    suspend fun pastBroadcasts(
        channel: String,
        limit: Int = 12,
        cursor: String? = null,
    ): TwitchVodPage =
        withContext(Dispatchers.IO) {
            val login = channel.trim().lowercase()
            if (login.isBlank()) return@withContext TwitchVodPage(emptyList())
            val root = postGraphQl(
                query = PAST_BROADCASTS_QUERY,
                variables = buildJsonObject {
                    put("login", JsonPrimitive(login))
                    put("first", JsonPrimitive(limit.coerceIn(1, 20)))
                    put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
                },
            ) ?: return@withContext TwitchVodPage(emptyList())
            val videos = root["data"]?.jsonObject
                ?.get("user")?.jsonObject
                ?.get("videos")?.jsonObject
                ?: return@withContext TwitchVodPage(emptyList())
            val edges = videos["edges"] as? JsonArray ?: return@withContext TwitchVodPage(emptyList())
            val vods = edges.mapNotNull { element ->
                val node = (element as? JsonObject)?.get("node") as? JsonObject
                    ?: return@mapNotNull null
                val id = node.string("id") ?: return@mapNotNull null
                val thumbs = node["thumbnailURLs"] as? JsonArray
                val thumbnail = thumbs?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.firstOrNull()
                    ?.replace("{width}", "320")
                    ?.replace("{height}", "180")
                TwitchVod(
                    id = id,
                    title = node.string("title") ?: "Past broadcast",
                    createdAt = node.string("createdAt").orEmpty(),
                    lengthSeconds = node["lengthSeconds"]?.jsonPrimitive?.intOrNull ?: 0,
                    thumbnailUrl = thumbnail,
                )
            }
            val pageInfo = videos["pageInfo"] as? JsonObject
            TwitchVodPage(
                vods = vods,
                endCursor = pageInfo?.string("endCursor"),
                hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.contentOrNull == "true",
            )
        }

    /**
     * Direct live-status probe — the PlaybackAccessToken endpoint happily
     * issues tokens for offline channels, so "token != null" is NOT a
     * liveness signal. Ask the GQL `user.stream` field directly: non-null
     * means live, null means offline, user == null means wrong channel name.
     */
    suspend fun isLive(channel: String): Boolean? = withContext(Dispatchers.IO) {
        val login = channel.trim().lowercase().ifEmpty { return@withContext null }
        val root = postGraphQl(
            query = IS_LIVE_QUERY,
            variables = buildJsonObject { put("login", JsonPrimitive(login)) },
        ) ?: return@withContext null
        val user = root["data"]?.jsonObject?.get("user")
            ?: return@withContext null
        if (user is JsonNull) return@withContext null
        (user as? JsonObject)?.get("stream")?.let { it !is JsonNull } ?: false
    }

    private fun fetchPlaybackToken(login: String, isLive: Boolean, vodId: String): PlaybackToken? {
        val body = buildJsonObject {
            put("operationName", JsonPrimitive("PlaybackAccessToken"))
            put("variables", buildJsonObject {
                put("isLive", JsonPrimitive(isLive))
                put("login", JsonPrimitive(login))
                put("isVod", JsonPrimitive(!isLive))
                put("vodID", JsonPrimitive(vodId))
                put("playerType", JsonPrimitive("embed"))
            })
            put("extensions", buildJsonObject {
                put("persistedQuery", buildJsonObject {
                    put("version", JsonPrimitive(1))
                    put("sha256Hash", JsonPrimitive(TwitchConfig.PLAYBACK_ACCESS_TOKEN_HASH))
                })
            })
        }.toString()
        val request = Request.Builder()
            .url(TwitchConfig.GQL_URL)
            .header("Client-ID", TwitchConfig.CLIENT_ID)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val raw = runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GQL PlaybackAccessToken -> HTTP ${response.code} for $login")
                    return@use null
                }
                response.body?.string()
            }
        }.onFailure { Log.w(TAG, "PAT fetch failed for $login", it) }.getOrNull()
            ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .onFailure { Log.w(TAG, "PAT response parse failed", it) }
            .getOrNull()
            ?: return null
        val field = if (isLive) "streamPlaybackAccessToken" else "videoPlaybackAccessToken"
        val pat = root["data"]?.jsonObject?.get(field) as? JsonObject
            ?: run {
                Log.w(TAG, "no $field in PAT response for $login")
                return null
            }
        val value = pat["value"]?.jsonPrimitive?.contentOrNull ?: return null
        val signature = pat["signature"]?.jsonPrimitive?.contentOrNull ?: return null
        return PlaybackToken(value, signature)
    }

    private fun postGraphQl(query: String, variables: JsonObject): JsonObject? {
        val body = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("variables", variables)
        }.toString()
        val request = Request.Builder()
            .url(TwitchConfig.GQL_URL)
            .header("Client-ID", TwitchConfig.CLIENT_ID)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val raw = runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GQL query -> HTTP ${response.code}")
                    return@use null
                }
                response.body?.string()
            }
        }.onFailure { Log.w(TAG, "GQL query failed", it) }.getOrNull() ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }
            .onFailure { Log.w(TAG, "GQL response parse failed", it) }
            .getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private data class PlaybackToken(val value: String, val signature: String)

    @Suppress("unused")
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "TwitchResolver"
        private val JSON_MEDIA = "application/json".toMediaTypeOrNull()
        private const val IS_LIVE_QUERY =
            "query IsLive(\$login: String!) { user(login: \$login) { stream { id } } }"
        private const val SEARCH_QUERY =
            "query SearchChannels(\$userQuery: String!, \$platform: String!) { " +
                "searchFor(userQuery: \$userQuery, platform: \$platform) { " +
                "channels { items { id login displayName profileImageURL(width: 300) } } } }"
        private const val PAST_BROADCASTS_QUERY =
            "query PastBroadcasts(\$login: String!, \$first: Int!, \$after: Cursor) { " +
                "user(login: \$login) { videos(first: \$first, after: \$after, sort: TIME) { " +
                "pageInfo { hasNextPage endCursor } " +
                "edges { node { id title createdAt lengthSeconds thumbnailURLs } } } } }"
    }
}
