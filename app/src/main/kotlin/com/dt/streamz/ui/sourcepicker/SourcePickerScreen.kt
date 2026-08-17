package com.dt.streamz.ui.sourcepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.data.StreamKind
import com.dt.streamz.data.StreamSource
import com.dt.streamz.ui.pointerClickable
import kotlinx.coroutines.delay

private enum class AudioGroup {
    Dub,
    Sub,
    Other,
}

private fun audioGroup(source: StreamSource): AudioGroup {
    val label = source.serverLabel.orEmpty()
    val url = source.url
    return when {
        Regex("\\bdub\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
            Regex("/(dub)(?:/|$|[?&#])", RegexOption.IGNORE_CASE).containsMatchIn(url) -> AudioGroup.Dub
        Regex("\\bsub(?:titles?)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
            Regex("/(sub)(?:/|$|[?&#])", RegexOption.IGNORE_CASE).containsMatchIn(url) -> AudioGroup.Sub
        else -> AudioGroup.Other
    }
}

private fun isPreferredSource(source: StreamSource): Boolean =
    source.serverLabel.orEmpty().contains("megaplay", ignoreCase = true) ||
        source.kind == StreamKind.Hls

@Composable
fun SourcePickerScreen(
    title: String,
    sources: List<StreamSource>,
    onPick: (StreamSource) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val dubSources = sources.filter { audioGroup(it) == AudioGroup.Dub }
    val subSources = sources.filter { audioGroup(it) == AudioGroup.Sub }
    val hasAudioVariants = dubSources.isNotEmpty() || subSources.isNotEmpty()
    val initialGroup = when {
        dubSources.isNotEmpty() -> AudioGroup.Dub
        subSources.isNotEmpty() -> AudioGroup.Sub
        else -> AudioGroup.Other
    }
    var selectedGroup by remember(sources) { mutableStateOf(initialGroup) }
    val displaySources = remember(selectedGroup, sources) {
        val candidates = when (selectedGroup) {
            AudioGroup.Dub -> dubSources
            AudioGroup.Sub -> subSources
            AudioGroup.Other -> sources
        }
        candidates.sortedWith(
            compareByDescending<StreamSource> { isPreferredSource(it) }
                .thenBy { it.serverLabel.orEmpty() },
        )
    }

    // Delay until the selected list is composed. Requesting focus in the
    // first frame races LazyColumn item creation on slower TV boxes and was
    // the reason the remote sometimes landed on the wrong server or nowhere.
    LaunchedEffect(selectedGroup, displaySources) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (hasAudioVariants) "Choose audio and server" else "Pick a source",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (hasAudioVariants) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dubSources.isNotEmpty()) {
                    AudioTab(
                        text = "English Dub (${dubSources.size})",
                        selected = selectedGroup == AudioGroup.Dub,
                        onClick = { selectedGroup = AudioGroup.Dub },
                    )
                }
                if (subSources.isNotEmpty()) {
                    AudioTab(
                        text = "Japanese + Sub (${subSources.size})",
                        selected = selectedGroup == AudioGroup.Sub,
                        onClick = { selectedGroup = AudioGroup.Sub },
                    )
                }
            }
            Text(
                text = if (selectedGroup == AudioGroup.Dub) {
                    "English-Dub servers only · recommended server is listed first"
                } else {
                    "Subtitle servers only · recommended server is listed first"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(displaySources, key = { _, s -> "${s.url}:${s.kind}" }) { index, source ->
                SourceRow(
                    source = source,
                    position = index + 1,
                    recommended = index == 0,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onPick(source) },
                )
            }
        }
    }
}

@Composable
private fun AudioTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SourceRow(
    source: StreamSource,
    position: Int,
    recommended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .pointerClickable(onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .border(
                    1.dp,
                    if (focused) Color.White else Color.Transparent,
                    RoundedCornerShape(6.dp),
                )
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Transparent),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val label = buildString {
                    append(position)
                    append(". ")
                    append(source.serverLabel ?: "Server")
                    if (!source.quality.isNullOrBlank()) {
                        append(" · ")
                        append(source.quality)
                    }
                    append(" · ")
                    append(
                        when (source.kind) {
                            StreamKind.Hls -> "HLS"
                            StreamKind.Mp4 -> "MP4"
                            StreamKind.Dash -> "DASH"
                            StreamKind.DirectEmbed -> "Web player"
                        },
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        if (recommended) append("Recommended · ")
                        append(
                            when (source.kind) {
                                StreamKind.Hls -> "Native HLS"
                                StreamKind.Mp4 -> "Native MP4"
                                StreamKind.Dash -> "Native DASH"
                                StreamKind.DirectEmbed -> "Web player backup"
                            },
                        )
                        val host = runCatching { android.net.Uri.parse(source.url).host }
                            .getOrNull()
                        if (!host.isNullOrBlank()) {
                            append(" · ")
                            append(host)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
        }
    }
}
