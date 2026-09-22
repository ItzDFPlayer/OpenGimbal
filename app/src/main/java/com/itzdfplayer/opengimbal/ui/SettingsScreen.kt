package com.itzdfplayer.opengimbal.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.accessibility.GimbalAccessibilityService
import com.itzdfplayer.opengimbal.accessibility.ShutterStatus
import com.itzdfplayer.opengimbal.camera.CameraUsageMonitor
import com.itzdfplayer.opengimbal.camera.MicrophoneState
import com.itzdfplayer.opengimbal.camera.MicrophoneUsage
import com.itzdfplayer.opengimbal.camera.isRecordingVideo
import com.itzdfplayer.opengimbal.gimbal.GimbalBleClient
import com.itzdfplayer.opengimbal.mapping.MappingStore

/** Third screen: preferences, and a live summary of what everything is doing. */
@Composable
fun SettingsScreen(
    client: GimbalBleClient,
    accessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cameraOnly by remember { mutableStateOf(MappingStore.cameraOnly(context)) }
    var flipRestart by remember { mutableStateOf(MappingStore.flipRestart(context)) }
    var microphone by remember { mutableStateOf(MicrophoneState.UNAVAILABLE) }

    // Keep the monitor live while the user is watching the status rows.
    LaunchedEffect(Unit) {
        CameraUsageMonitor.start(context)
        // Keep the microphone readout current without polling.
        microphone = MicrophoneUsage.read(context)
    }

    DisposableEffect(Unit) {
        val stopWatching = MicrophoneUsage.observe(context) { microphone = it }
        onDispose { stopWatching?.invoke() }
    }

    val versionName = remember {
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "-"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.nav_settings),
            subtitle = stringResource(R.string.settings_subtitle),
        )

        SectionCard(title = stringResource(R.string.status_title)) {
            InfoRow(
                label = stringResource(R.string.label_accessibility),
                value = stringResource(
                    if (accessibilityEnabled) R.string.value_enabled else R.string.value_disabled,
                ),
                emphasize = accessibilityEnabled,
            )
            InfoRow(
                stringResource(R.string.label_gimbal),
                client.connectedName ?: stringResource(R.string.value_not_connected),
            )
            InfoRow(
                stringResource(R.string.label_camera),
                stringResource(cameraStateLabelRes()),
                emphasize = CameraUsageMonitor.cameraInUse,
            )
            if (!accessibilityEnabled) {
                Button(onClick = onOpenAccessibilitySettings) {
                    Text(stringResource(R.string.action_open_accessibility_settings))
                }
            }
        }

        SectionCard(title = stringResource(R.string.camera_detection_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.camera_only_label))
                    Text(
                        stringResource(R.string.camera_only_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = cameraOnly,
                    onCheckedChange = {
                        cameraOnly = it
                        MappingStore.setCameraOnly(context, it)
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            CameraDiagnostics()

            if (cameraOnly && !CameraUsageMonitor.watching) {
                Text(
                    stringResource(R.string.camera_unavailable_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        SectionCard(title = stringResource(R.string.recording_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.flip_restart_label))
                    Text(
                        stringResource(R.string.flip_restart_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = flipRestart,
                    onCheckedChange = {
                        flipRestart = it
                        MappingStore.setFlipRestart(context, it)
                        // The sensor is only registered while the feature is wanted.
                        GimbalAccessibilityService.instanceOrNull()?.applyFlipWatch()
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            InfoRow(
                label = stringResource(R.string.label_camera),
                value = stringResource(
                    if (CameraUsageMonitor.cameraInUse) R.string.value_in_use else R.string.value_free,
                ),
                emphasize = CameraUsageMonitor.cameraInUse,
            )
            InfoRow(
                label = stringResource(R.string.label_microphone),
                value = stringResource(microphone.labelRes),
                emphasize = microphone == MicrophoneState.IN_USE,
            )
            InfoRow(
                label = stringResource(R.string.label_recording_video),
                value = stringResource(recordingLabelRes(microphone)),
                emphasize = isRecordingVideo(CameraUsageMonitor.cameraInUse, microphone),
            )

            if (microphone == MicrophoneState.UNAVAILABLE) {
                Text(
                    stringResource(R.string.microphone_unavailable_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        SectionCard(title = stringResource(R.string.shutter_title)) {
            Text(
                stringResource(R.string.shutter_description),
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text(
                ShutterStatus.lastReport ?: stringResource(R.string.shutter_no_attempt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.about_title)) {
            InfoRow(stringResource(R.string.label_version), versionName)
            InfoRow(
                stringResource(R.string.label_protocol),
                stringResource(R.string.about_protocol),
            )
            Text(
                stringResource(R.string.about_icon_credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            LanguagePicker()
        }
    }
}

/**
 * The app's own language.
 *
 * Only offered where the platform supports it, and said so plainly where it does not, rather
 * than showing a control that would do nothing. The app is not wrapped in AppCompat, whose
 * backport is the alternative, and taking that on for one setting would mean every activity
 * in the app changing shape to suit it.
 */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val current = remember(context) { AppLanguage.current(context) }
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(stringResource(R.string.language_title))
        if (!AppLanguage.isSupported) {
            Text(
                stringResource(R.string.language_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            stringResource(R.string.language_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(stringResource(current.labelRes))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppLanguage.entries.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(stringResource(language.labelRes)) },
                        onClick = {
                            expanded = false
                            setAppLanguage(context, language)
                        },
                    )
                }
            }
        }
    }
}

@StringRes
private fun cameraStateLabelRes(): Int = when {
    !CameraUsageMonitor.watching -> R.string.value_not_reported
    CameraUsageMonitor.cameraInUse -> R.string.value_used_by_another_app
    else -> R.string.value_free
}

@StringRes
private fun recordingLabelRes(microphone: MicrophoneState): Int = when {
    isRecordingVideo(CameraUsageMonitor.cameraInUse, microphone) -> R.string.value_yes
    microphone == MicrophoneState.UNAVAILABLE -> R.string.value_unknown
    CameraUsageMonitor.cameraInUse -> R.string.recording_no_camera_only
    else -> R.string.value_no
}

@Composable
private fun CameraDiagnostics() {
    val unavailable = CameraUsageMonitor.unavailableIds
    when {
        !CameraUsageMonitor.watching -> Text(
            stringResource(R.string.camera_not_reported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        unavailable.isEmpty() -> Text(
            stringResource(R.string.camera_none_held),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Text(
            stringResource(
                R.string.camera_held_by_other,
                unavailable.size,
                CameraUsageMonitor.knownCameraCount,
                unavailable.joinToString(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
