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
import com.dt.streamz.updater.ApkInstaller
import com.dt.streamz.updater.UpdateChecker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DtApplication
    val scope = rememberCoroutineScope()
    val continueEntries by app.continueWatching.entries.collectAsState(initial = emptyList())
    val pinnedChannels by app.pinnedChannels.channels.collectAsState(initial = emptyList())

    var blockerEnabled by remember { mutableStateOf(app.hostBlocker.enabled()) }

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
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.title }) { item ->
                SettingRow(item)
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
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .border(
                    1.dp,
                    if (focused && clickable) Color.White else Color.Transparent,
                    RoundedCornerShape(6.dp),
                )
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Transparent),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused && clickable)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
