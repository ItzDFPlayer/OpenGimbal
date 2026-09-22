package com.itzdfplayer.opengimbal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.itzdfplayer.opengimbal.gimbal.GimbalManager

/**
 * App shell: an adaptive navigation suite switches between the screens.
 *
 * [NavigationSuiteScaffold] renders a bottom bar on a phone and a rail on wider
 * screens, which is the current Material 3 adaptive pattern.
 */
@Composable
fun OpenGimbalApp(
    accessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val context = LocalContext.current
    // Shared with the accessibility service, so the link survives this screen.
    val client = remember { GimbalManager.client(context.applicationContext) }

    DisposableEffect(Unit) {
        // Deliberately not disconnecting: the service keeps using the link.
        onDispose { client.stopScan() }
    }

    // Permission state lives here, not in a screen, so switching tabs never
    // restarts the scan.
    val bluetoothPermissions = remember { requiredBluetoothPermissions() }
    val notificationPermission = remember { postNotificationPermission() }
    var bluetoothGranted by remember {
        mutableStateOf(context.hasBluetoothPermissions(bluetoothPermissions))
    }
    var notificationsGranted by remember { mutableStateOf(context.canPostNotifications()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Re-read the real state instead of trusting the result map.
        bluetoothGranted = context.hasBluetoothPermissions(bluetoothPermissions)
        notificationsGranted = context.canPostNotifications()
    }

    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!bluetoothGranted) addAll(bluetoothPermissions)
            if (!notificationsGranted) notificationPermission?.let { add(it) }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    LaunchedEffect(bluetoothGranted) {
        if (bluetoothGranted) {
            client.refreshAdapterState()
            client.startScan()
        }
    }

    var destination by rememberSaveable { mutableStateOf(AppDestination.DEVICE) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestination.entries.forEach { entry ->
                item(
                    icon = {
                        Icon(
                            painterResource(entry.icon),
                            contentDescription = stringResource(entry.labelRes),
                        )
                    },
                    label = { Text(stringResource(entry.labelRes)) },
                    selected = entry == destination,
                    onClick = { destination = entry },
                )
            }
        },
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (destination) {
                AppDestination.DEVICE -> DeviceScreen(
                    client = client,
                    bluetoothGranted = bluetoothGranted,
                    notificationsGranted = notificationsGranted,
                    onRequestPermissions = {
                        permissionLauncher.launch(bluetoothPermissions)
                    },
                    modifier = contentModifier,
                )

                AppDestination.MAPPINGS -> MappingsScreen(
                    accessibilityEnabled = accessibilityEnabled,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    modifier = contentModifier,
                )

                AppDestination.CONTROL -> ControlScreen(
                    client = client,
                    modifier = contentModifier,
                )

                AppDestination.SETTINGS -> SettingsScreen(
                    client = client,
                    accessibilityEnabled = accessibilityEnabled,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    modifier = contentModifier,
                )
            }
        }
    }
}
