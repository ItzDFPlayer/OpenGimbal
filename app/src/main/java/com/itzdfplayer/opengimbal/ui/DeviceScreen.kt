package com.itzdfplayer.opengimbal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.gimbal.GimbalBleClient
import com.itzdfplayer.opengimbal.gimbal.GimbalModel
import com.itzdfplayer.opengimbal.gimbal.GimbalState
import com.itzdfplayer.opengimbal.mapping.MappingStore

/**
 * Main screen: connect a gimbal and watch the raw input it reports.
 *
 * The accessibility service lives on the Mappings and Settings screens.
 */
@Composable
fun DeviceScreen(
    client: GimbalBleClient,
    bluetoothGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.nav_gimbal),
            subtitle = stringResource(R.string.device_subtitle),
        )

        if (!bluetoothGranted) {
            PermissionsSection(onRequest = onRequestPermissions)
        }

        ConnectionSection(client, notificationsGranted)
        if (client.devices.isNotEmpty()) {
            FoundGimbalsSection(client)
        }
        client.state?.let { InputsSection(it) }
        client.state?.let { RawFieldsSection(it) }
        LogSection(client)
    }
}

@Composable
private fun PermissionsSection(onRequest: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.permissions_title),
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            stringResource(R.string.permissions_bluetooth_required),
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.permissions_versions_explanation),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onRequest) { Text(stringResource(R.string.action_grant_permission)) }
    }
}

@Composable
private fun ConnectionSection(client: GimbalBleClient, notificationsGranted: Boolean) {
    val context = LocalContext.current
    var autoConnect by remember { mutableStateOf(MappingStore.autoConnect(context)) }

    SectionCard(title = stringResource(R.string.connection_title)) {
        InfoRow(
            stringResource(R.string.label_name),
            client.connectedName ?: stringResource(R.string.value_not_connected),
        )
        InfoRow(
            label = stringResource(R.string.label_model),
            value = if (client.connectedModel == GimbalModel.NONE) {
                stringResource(R.string.value_dash)
            } else {
                stringResource(client.connectedModel.labelRes)
            },
            emphasize = client.connectedModel != GimbalModel.NONE,
        )
        InfoRow(
            stringResource(R.string.label_address),
            client.connectedAddress ?: stringResource(R.string.value_dash),
        )
        InfoRow(
            stringResource(R.string.label_status),
            stringResource(client.statusRes),
        )
        InfoRow(
            stringResource(R.string.label_mtu),
            if (client.mtu > 0) client.mtu.toString() else stringResource(R.string.value_dash),
        )
        InfoRow(stringResource(R.string.label_packets), client.packetCount.toString())

        HorizontalDivider(Modifier.padding(vertical = 2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.auto_connect_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.auto_connect_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = autoConnect,
                onCheckedChange = {
                    autoConnect = it
                    MappingStore.setAutoConnect(context, it)
                },
            )
        }

        if (!notificationsGranted) {
            Text(
                stringResource(R.string.notifications_blocked_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(4.dp))
        if (client.isConnected) {
            OutlinedButton(onClick = { client.disconnect() }) {
                Text(stringResource(R.string.action_disconnect))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (client.scanning) client.stopScan() else client.startScan() },
                ) {
                    Text(
                        stringResource(
                            if (client.scanning) {
                                R.string.action_stop_scan
                            } else {
                                R.string.action_scan_for_gimbal
                            },
                        ),
                    )
                }
                OutlinedButton(onClick = { client.refreshKnownDevices() }) {
                    Text(stringResource(R.string.action_refresh_paired))
                }
            }
        }
    }
}

@Composable
private fun FoundGimbalsSection(client: GimbalBleClient) {
    SectionCard(title = stringResource(R.string.found_gimbals_title)) {
        client.devices.forEach { found ->
            val modelLabel = stringResource(found.model.labelRes)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(found.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append(modelLabel)
                            append("  •  ")
                            append(stringResource(found.sourceLabelRes))
                            if (found.rssi != 0) append("  •  ${found.rssi} dBm")
                            append("  •  ")
                            append(found.address)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { client.connect(found.device) }) {
                    Text(stringResource(R.string.action_connect))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun InputsSection(state: GimbalState) {
    SectionCard(title = stringResource(R.string.inputs_title)) {
        ButtonRow(
            stringResource(R.string.label_trigger),
            state.triggerButton,
            stringResource(state.triggerLabelRes, state.triggerButton),
        )
        ButtonRow(
            stringResource(R.string.label_shutter),
            state.captureButton,
            stringResource(state.captureLabelRes, state.captureButton),
        )
        ButtonRow(
            stringResource(R.string.label_m_button),
            state.modelButton,
            stringResource(state.modelButtonLabelRes, state.modelButton),
        )
        ButtonRow(
            stringResource(R.string.label_knob),
            state.knob,
            stringResource(if (state.knob != 0) R.string.value_turning else R.string.value_idle),
        )
        ButtonRow(
            label = stringResource(R.string.label_zoom_slider),
            value = state.zoomValue,
            description = when {
                state.zoomValue > 0 ->
                    stringResource(R.string.value_zoom_in, (state.zoomValue / 100.0f).toString())
                state.zoomValue < 0 ->
                    stringResource(R.string.value_zoom_out, (state.zoomValue / 100.0f).toString())
                else -> stringResource(R.string.value_idle)
            },
            signed = true,
        )
        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        val modeLabel = state.modeEnum?.labelRes
        InfoRow(
            stringResource(R.string.label_mode),
            if (modeLabel != null) {
                stringResource(modeLabel)
            } else {
                stringResource(R.string.value_mode_code, state.mode)
            },
        )
        InfoRow(
            stringResource(R.string.label_joystick),
            stringResource(R.string.value_joystick, state.direction, state.directionState),
        )
        InfoRow(
            stringResource(R.string.label_battery),
            stringResource(R.string.value_battery, state.voltage),
        )
    }
}

@Composable
private fun RawFieldsSection(state: GimbalState) {
    SectionCard(title = stringResource(R.string.raw_fields_title)) {
        InfoRow(stringResource(R.string.field_capture_button), state.captureButton.toString())
        InfoRow(stringResource(R.string.field_model_button), state.modelButton.toString())
        InfoRow(stringResource(R.string.field_trigger_button), state.triggerButton.toString())
        InfoRow(stringResource(R.string.field_knob), state.knob.toString())
        InfoRow(stringResource(R.string.field_zoom_value), state.zoomValue.toString())
        InfoRow(stringResource(R.string.field_mode), state.mode.toString())
        InfoRow(stringResource(R.string.field_direction_state), state.directionState.toString())
        InfoRow(stringResource(R.string.field_direction), state.direction.toString())
        InfoRow(stringResource(R.string.field_voltage), state.voltage.toString())
    }
}

@Composable
private fun LogSection(client: GimbalBleClient) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) client.log else client.log.take(COLLAPSED_LOG_LINES)

    SectionCard(title = stringResource(R.string.log_title)) {
        InfoRow(stringResource(R.string.label_last_packet), client.lastPacketName)
        Text(
            client.lastPacketHex.ifEmpty { stringResource(R.string.value_dash) },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        if (client.log.isEmpty()) {
            Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            visible.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (client.log.size > COLLAPSED_LOG_LINES) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.action_show_less)
                        } else {
                            stringResource(R.string.action_show_all_entries, client.log.size)
                        },
                    )
                }
            }
        }
    }
}

private const val COLLAPSED_LOG_LINES = 6
