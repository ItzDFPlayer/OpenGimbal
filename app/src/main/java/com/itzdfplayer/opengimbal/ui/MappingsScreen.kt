package com.itzdfplayer.opengimbal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.mapping.GimbalAction
import com.itzdfplayer.opengimbal.mapping.GimbalTrigger
import com.itzdfplayer.opengimbal.mapping.MappingStore

/** Second screen: everything about what each trigger does. */
@Composable
fun MappingsScreen(
    accessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var mappings by remember { mutableStateOf(MappingStore.allActions(context)) }
    var pinchStrength by remember { mutableStateOf(MappingStore.pinchStrength(context).toFloat()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.nav_mappings),
            subtitle = stringResource(R.string.mappings_subtitle),
        )

        AccessibilityPanel(
            enabled = accessibilityEnabled,
            onOpenSettings = onOpenAccessibilitySettings,
            enabledMessage = stringResource(R.string.mappings_live_message),
            disabledMessage = stringResource(R.string.mappings_saved_message),
        )

        SectionCardList(title = stringResource(R.string.trigger_mappings_title)) {
            GimbalTrigger.entries.forEachIndexed { index, trigger ->
                if (index > 0) HorizontalDivider()
                ActionPickerRow(
                    trigger = trigger,
                    selected = mappings[trigger] ?: GimbalAction.NONE,
                    onSelect = { action ->
                        MappingStore.setAction(context, trigger, action)
                        mappings = mappings + (trigger to action)
                    },
                )
            }

            // Belongs with the choices above rather than with the zoom slider: it is about
            // what the volume options in this list can and cannot do.
            HorizontalDivider()
            Text(
                stringResource(R.string.volume_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            )
        }

        SectionCard(title = stringResource(R.string.pinch_zoom_title)) {
            Text(
                stringResource(R.string.pinch_zoom_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.pinch_zoom_strength, pinchStrength.toInt()))
            Slider(
                value = pinchStrength,
                onValueChange = { pinchStrength = it },
                onValueChangeFinished = {
                    MappingStore.setPinchStrength(context, pinchStrength.toInt())
                },
                valueRange = MappingStore.MIN_PINCH_STRENGTH.toFloat()..
                    MappingStore.MAX_PINCH_STRENGTH.toFloat(),
            )
        }
    }
}
