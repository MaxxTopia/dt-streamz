package com.dt.streamz.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dt.streamz.BuildConfig
import com.dt.streamz.DtApplication
import com.dt.streamz.adblock.HostBlocker
import com.dt.streamz.diag.DebugLog
import com.dt.streamz.updater.ApkInstaller
import com.dt.streamz.updater.UpdateChecker
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    val scope = rememberCoroutineScope()
    val continueEntries by app.continueWatching.entries.collectAsState(initial = emptyList())
    val pinnedChannels by app.pinnedChannels.channels.collectAsState(initial = emptyList())

    var blockerEnabled by remember { mutableStateOf(app.hostBlocker.enabled()) }
    var telemetryEnabled by remember { mutableStateOf(com.dt.streamz.diag.Telemetry.isEnabled()) }
    var debugLogOpen by remember { mutableStateOf(false) }

    val items = buildList<SettingItem> {
        add(
            SettingItem(
                title = "App version",
                subtitle = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                action = null,
            ),
        )
        add(
            SettingItem(
                title = "Block ads in player",
                subtitle = if (blockerEnabled)
                    "ON · ${app.hostBlocker.size()} hosts blocked. Turn OFF if streams white-screen."
                else
                    "OFF · letting all requests through. Turn ON for ad-blocking.",
                action = {
                    val next = !blockerEnabled
                    app.hostBlocker.setEnabled(next)
                    blockerEnabled = next
                    Toast.makeText(
                        ctx,
                        if (next) "Adblock ON" else "Adblock OFF — all requests pass through",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                actionLabel = "Toggle",
            ),
        )
        add(
            SettingItem(
                title = "Ad-host blocklist",
                subtitle = "${app.hostBlocker.size()} hosts loaded",
                action = {
                    scope.launch {
                        val ok = app.hostBlocker.refreshFromUrl(HostBlocker.UPSTREAM_HOSTS_URL)
                        Toast.makeText(
                            ctx,
                            if (ok) "Blocklist refreshed · ${app.hostBlocker.size()} hosts"
                            else "Refresh failed — keeping existing list",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                actionLabel = "Refresh now",
            ),
        )
        add(
            SettingItem(
                title = "Continue watching",
                subtitle = "${continueEntries.size} entr${if (continueEntries.size == 1) "y" else "ies"} stored",
                action = {
                    scope.launch {
                        app.continueWatching.clear()
                        Toast.makeText(ctx, "Continue watching cleared", Toast.LENGTH_SHORT).show()
                    }
                },
                actionLabel = "Clear",
            ),
        )
        add(
            SettingItem(
                title = "Pinned Twitch channels",
                subtitle = if (pinnedChannels.isEmpty()) "None (using defaults)"
                else pinnedChannels.joinToString(", "),
                action = null,
            ),
        )
        add(
            SettingItem(
                title = "Check for updates",
                subtitle = "Poll GitHub Releases and install the newer APK if present",
                action = {
                    scope.launch {
                        val update = UpdateChecker().checkForUpdate()
                        if (update == null) {
                            Toast.makeText(ctx, "No update available", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                ctx,
                                "Downloading ${update.tagName}…",
                                Toast.LENGTH_SHORT,
                            ).show()
                            val ok = ApkInstaller.downloadAndInstall(ctx, update.apkUrl)
                            if (!ok) Toast.makeText(
                                ctx,
                                "Couldn't install — check REQUEST_INSTALL_PACKAGES in system settings",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                actionLabel = "Check + install",
            ),
        )
        add(
            SettingItem(
                title = "Scraper providers",
                subtitle = app.providerRegistry.all.joinToString(", ") { it.displayName },
                action = null,
            ),
        )
        add(
            SettingItem(
                title = "Auto error reports",
                subtitle = if (telemetryEnabled)
                    "ON · failed streams are reported automatically so dead sources can be fixed. No viewing data."
                else
                    "OFF · nothing is sent. Use 'View debug log' + screenshot to report issues instead.",
                action = {
                    val next = !telemetryEnabled
                    com.dt.streamz.diag.Telemetry.setEnabled(ctx, next)
                    telemetryEnabled = next
                    Toast.makeText(
                        ctx,
                        if (next) "Auto error reports ON" else "Auto error reports OFF",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                actionLabel = "Toggle",
            ),
        )
        add(
            SettingItem(
                title = "View debug log",
                subtitle = "Last ${DebugLog.snapshot().size} lines from WebPlayer / mirror walker. Screenshot to report a stream failure.",
                action = { debugLogOpen = true },
                actionLabel = "Open",
            ),
        )
        add(
            SettingItem(
                title = "Send debug log to cloud",
                subtitle = "Upload the last ${DebugLog.snapshot().size} log lines for remote diagnosis. Failure trace only — no viewing data. Do this right after a stream/anime/movie fails.",
                action = {
                    val snapshot = DebugLog.snapshot()
                    if (snapshot.isEmpty()) {
                        Toast.makeText(ctx, "Log empty — reproduce the failure first", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "Uploading ${snapshot.size} lines…", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val ok = com.dt.streamz.diag.Telemetry.sendDebugLog(snapshot)
                            Toast.makeText(
                                ctx,
                                if (ok) "Log uploaded ✓" else "Upload failed — check the box's connection",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                actionLabel = "Upload",
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.title }) { item ->
                SettingRow(item)
            }
        }
    }

    if (debugLogOpen) {
        DebugLogDialog(onDismiss = { debugLogOpen = false })
    }
}

@Composable
private fun DebugLogDialog(onDismiss: () -> Unit) {
    val lines = remember { DebugLog.snapshot() }
    val scrollState = rememberScrollState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(24.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF101216)),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Debug log · ${lines.size} lines",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = "Press BACK to close. Screenshot anything red/W. Most-recent at the bottom.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9AA3D9),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (lines.isEmpty()) {
                    Text(
                        text = "(no lines yet — try playing a stream first, then come back)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFAAB0BC),
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        lines.forEach { line ->
                            val tint = when {
                                line.contains(" W/") -> Color(0xFFFFB74D)
                                line.contains(" E/") -> Color(0xFFEF5350)
                                line.contains(" I/") -> Color(0xFF81D4FA)
                                else -> Color(0xFFB0BEC5)
                            }
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class SettingItem(
    val title: String,
    val subtitle: String,
    val action: (() -> Unit)?,
    val actionLabel: String = "Run",
)

@Composable
private fun SettingRow(item: SettingItem) {
    var focused by remember { mutableStateOf(false) }
    val clickable = item.action != null
    val content = @Composable {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .border(
                    1.dp,
                    if (focused && clickable) Color.White else Color.Transparent,
                    RoundedCornerShape(6.dp),
                )
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Transparent),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused && clickable)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                if (clickable) {
                    Text(
                        text = "OK · ${item.actionLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (clickable) {
        Surface(
            onClick = { item.action?.invoke() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { content() }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    RoundedCornerShape(6.dp),
                ),
        ) { content() }
    }
}
