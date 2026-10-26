package com.dt.streamz.data

import android.content.Context

data class TwitchVodProgress(
    val vodId: String,
    val channel: String,
    val title: String,
    val thumbnail: String?,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long,
)

/** Account-free Twitch VOD resume storage, kept separate from episodic history. */
class TwitchVodProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun resumePosition(vodId: String): Long =
        read().firstOrNull { it.vodId == vodId.trim() }
            ?.takeUnless { ProgressMath.isFinished(it.positionMs, it.durationMs) }
            ?.positionMs
            ?.coerceAtLeast(0L)
            ?: 0L

    fun entries(limit: Int = 8): List<TwitchVodProgress> =
        read()
            .filterNot { ProgressMath.isFinished(it.positionMs, it.durationMs) }
            .sortedByDescending { it.timestamp }
            .take(limit.coerceAtLeast(0))

    fun update(
        vodId: String,
        channel: String,
        title: String,
        thumbnail: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        val cleanId = vodId.trim()
        if (cleanId.isBlank()) return
        val old = read().firstOrNull { it.vodId == cleanId }
        val updated = TwitchVodProgress(
            vodId = cleanId,
            channel = channel.cleanField().ifBlank { old?.channel.orEmpty() },
            title = title.cleanField().ifBlank { old?.title.orEmpty() },
            thumbnail = thumbnail.cleanField().takeIf { it.isNotBlank() } ?: old?.thumbnail,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = if (durationMs > 0) durationMs else old?.durationMs ?: 0L,
            timestamp = System.currentTimeMillis(),
        )
        write((listOf(updated) + read().filterNot { it.vodId == cleanId }).take(MAX_ENTRIES))
    }

    fun complete(vodId: String) {
        val cleanId = vodId.trim()
        write(read().filterNot { it.vodId == cleanId })
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun read(): List<TwitchVodProgress> =
        prefs.getString(KEY_ENTRIES, null)
            ?.split('\n')
            ?.mapNotNull { line ->
                val parts = line.split('\t', limit = 7)
                if (parts.size < 7) return@mapNotNull null
                val id = parts[0].trim()
                if (id.isBlank()) return@mapNotNull null
                TwitchVodProgress(
                    vodId = id,
                    channel = parts[1].trim(),
                    title = parts[2].trim(),
                    thumbnail = parts[3].trim().takeIf { it.isNotBlank() },
                    positionMs = parts[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    durationMs = parts[5].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    timestamp = parts[6].toLongOrNull() ?: 0L,
                )
            }
            .orEmpty()

    private fun write(entries: List<TwitchVodProgress>) {
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_ENTRIES).apply()
            return
        }
        prefs.edit().putString(
            KEY_ENTRIES,
            entries.joinToString("\n") {
                listOf(
                    it.vodId.cleanField(),
                    it.channel.cleanField(),
                    it.title.cleanField().take(160),
                    it.thumbnail.cleanField().take(600),
                    it.positionMs.toString(),
                    it.durationMs.toString(),
                    it.timestamp.toString(),
                ).joinToString("\t")
            },
        ).apply()
    }

    private fun String?.cleanField(): String =
        this.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    companion object {
        private const val PREFS = "twitch_vod_progress"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 20
    }
}

/** Pure progress rules shared by resume stores and unit tests. */
object ProgressMath {
    const val MIN_RESUME_MS = 10_000L
    const val END_GUARD_MS = 20_000L

    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= (durationMs - END_GUARD_MS).coerceAtLeast(0L)

    fun resumePosition(positionMs: Long, durationMs: Long): Long =
        if (positionMs < MIN_RESUME_MS || isFinished(positionMs, durationMs)) 0L
        else positionMs.coerceAtLeast(0L)
}
