package com.itzdfplayer.opengimbal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.mapping.GimbalAction
import com.itzdfplayer.opengimbal.mapping.GimbalTrigger

/** Screen title plus a one-line explanation. */
@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A titled card. Pass [containerColor] to emphasise a state (warning, active, …),
 * otherwise the default surface colour is used.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (containerColor != null) {
            CardDefaults.cardColors(containerColor = containerColor)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

/**
 * Like [SectionCard], but for list content: rows carry their own padding and
 * dividers run the full width of the card.
 */
@Composable
fun SectionCardList(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            content()
        }
    }
}

/** Label on the left, value on the right. */
@Composable
fun InfoRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** A live input reading: raw value plus what it means. Highlights when non-zero. */
@Composable
fun ButtonRow(
    label: String,
    value: Int,
    description: String,
    signed: Boolean = false,
) {
    val active = value != 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = if (signed) "%4d".format(value) else "%3d".format(value),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One mapping row, in the Material 3 settings-list style: the whole row is the
 * touch target and opens the menu, rather than a button sitting beside a label.
 */
@Composable
fun ActionPickerRow(
    trigger: GimbalTrigger,
    selected: GimbalAction,
    onSelect: (GimbalAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(trigger) { GimbalAction.optionsFor(trigger) }

    Box {
        ListItem(
            headlineContent = { Text(stringResource(trigger.labelRes)) },
            supportingContent = {
                Text(
                    stringResource(selected.labelRes),
                    color = if (selected == GimbalAction.NONE) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            },
            trailingContent = {
                Icon(
                    painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            // The encoding note belongs next to the choice, not in the list itself.
            Text(
                stringResource(trigger.hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(option.labelRes))
                            Text(
                                stringResource(option.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Current state of the accessibility service. Shared by both screens so the panel
 * looks and behaves the same wherever it appears.
 */
@Composable
fun AccessibilityPanel(
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    enabledMessage: String,
    disabledMessage: String,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.accessibility_service_title),
        modifier = modifier,
        containerColor = if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Text(
            stringResource(if (enabled) R.string.state_enabled else R.string.state_disabled),
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (enabled) enabledMessage else disabledMessage,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onOpenSettings) {
            Text(
                stringResource(
                    if (enabled) {
                        R.string.action_open_accessibility_settings
                    } else {
                        R.string.action_enable_accessibility_service
                    },
                ),
            )
        }
    }
}

/** Permissions needed to scan for, and talk to, the gimbal. */
fun requiredBluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun Context.hasBluetoothPermissions(permissions: Array<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/** Notifications became a runtime permission in Android 13; `null` below that. */
fun postNotificationPermission(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

fun Context.canPostNotifications(): Boolean {
    val permission = postNotificationPermission() ?: return true
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
