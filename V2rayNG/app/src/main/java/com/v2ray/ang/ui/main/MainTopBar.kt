package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.SelectListDialog
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar

@Composable
fun MainTopBar(
    isLoading: Boolean,
    isRunning: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onAction: (MainAction) -> Unit,
    onMoreMenuAction: (MainMoreMenuAction) -> Unit,
) {
    var showImportMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showFragmentDialog by remember { mutableStateOf(false) }
    val importMenuScrollState = rememberScrollState()
    val moreMenuScrollState = rememberScrollState()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val maxMenuHeight = LocalConfiguration.current.screenHeightDp.dp - statusBarHeight - navBarHeight - 20.dp

    // Mode state (same as Settings)
    var mode by rememberMmkvString(AppConfig.PREF_MODE, AppConfig.VPN)
    val modeEntries = stringArrayResource(R.array.mode_entries).toList()
    val modeValues = stringArrayResource(R.array.mode_value).toList()
    val modeOptions = modeEntries.zip(modeValues)
    val selectedModeOption = modeOptions.find { it.second == mode } ?: modeOptions.firstOrNull()

    // Single source of truth for Fragment enabled (toolbar F + dialog share this)
    var fragmentEnabled by rememberMmkvBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
    val fragmentActiveColor = Color(0xFF4CAF50)

    // Re-sync when returning from Settings (or any other screen) so F stays in sync
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fragmentEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppTopBar(
        title = stringResource(R.string.title_server),
        onBackClick = {},
        isLoading = isLoading,
        isSearchActive = showSearch,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onSearchClose = onSearchClose,
        searchPlaceholder = stringResource(R.string.menu_item_search),
        navigationIcon = {
            if (showSearch) {
                IconButton(onClick = onSearchClose) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(R.string.acc_back))
                }
            } else {
                IconButton(onClick = onMenuClick) {
                    Icon(painterResource(R.drawable.ic_menu_24dp), contentDescription = stringResource(R.string.acc_open_menu))
                }
            }
        },
        actions = {
            // Tighter cluster for Mode / F / Search / + (⋮ keeps default end spacing)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((-4).dp),
            ) {
                if (!showSearch) {
                    TextButton(
                        onClick = { showModeDialog = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.title_mode),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Click = toggle fragment; Long-click = fragment settings dialog
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    fragmentEnabled = MmkvManager.decodeSettingsBool(
                                        AppConfig.PREF_FRAGMENT_ENABLED,
                                        false
                                    )
                                    showFragmentDialog = true
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Vector "F" — fixed geometry, ignores system/app font
                        Icon(
                            painter = painterResource(R.drawable.ic_fragment_f_24dp),
                            contentDescription = stringResource(R.string.title_pref_fragment_enabled),
                            modifier = Modifier.size(22.dp),
                            tint = if (fragmentEnabled) {
                                fragmentActiveColor
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    IconButton(
                        onClick = { onSearchToggle(true) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_search_24dp),
                            contentDescription = stringResource(R.string.acc_search),
                        )
                    }
                }
                Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                    IconButton(
                        onClick = { showImportMenu = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_add_24dp),
                            contentDescription = stringResource(R.string.acc_add),
                        )
                    }
                DropdownMenu(
                    expanded = showImportMenu,
                    onDismissRequest = { showImportMenu = false },
                    scrollState = importMenuScrollState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .heightIn(max = maxMenuHeight)
                        .verticalScrollbar(importMenuScrollState)
                ) {
                    ImportMenuContent(
                        onAction = { action ->
                            showImportMenu = false
                            onAction(action)
                        }
                    )
                }
                }
            }
            // ⋮ stays at default TopAppBar end spacing
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(onClick = { showMenu = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert_24dp), contentDescription = stringResource(R.string.acc_more))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    scrollState = moreMenuScrollState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .heightIn(max = maxMenuHeight)
                        .verticalScrollbar(moreMenuScrollState)
                ) {
                    MoreMenuContent { action ->
                        showMenu = false
                        onMoreMenuAction(action)
                    }
                }
            }
        }
    )

    if (showModeDialog) {
        SelectListDialog(
            title = stringResource(R.string.title_mode),
            options = modeOptions,
            optionText = { it.first },
            selectedOption = selectedModeOption,
            onSelected = { option ->
                val newMode = option.second
                if (newMode != mode) {
                    mode = newMode
                    if (isRunning) {
                        onAction(MainAction.RestartService)
                    }
                }
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false },
            showRadio = true
        )
    }

    if (showFragmentDialog) {
        FragmentSettingsDialog(
            fragmentEnabled = fragmentEnabled,
            onFragmentEnabledChange = { enabled ->
                fragmentEnabled = enabled
            },
            onDismiss = { showFragmentDialog = false },
            onRestartIfNeeded = {
                if (isRunning) {
                    onAction(MainAction.RestartService)
                }
            },
        )
    }
}

/**
 * Quick Fragment settings shown on long-press of the F button.
 * Shares Enable state with the toolbar F icon; other fields use the same MMKV keys as Settings.
 */
@Composable
private fun FragmentSettingsDialog(
    fragmentEnabled: Boolean,
    onFragmentEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestartIfNeeded: () -> Unit,
) {
    var fragmentPackets by rememberMmkvString(AppConfig.PREF_FRAGMENT_PACKETS, "tlshello")
    var fragmentLength by rememberMmkvString(AppConfig.PREF_FRAGMENT_LENGTH, "50-100")
    var fragmentInterval by rememberMmkvString(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20")
    var fragmentMaxSplit by rememberMmkvString(AppConfig.PREF_FRAGMENT_MAXSPLIT, "10")

    // Snapshot values at dialog open — restart only when something actually changed
    val initialEnabled = remember { fragmentEnabled }
    val initialPackets = remember { fragmentPackets }
    val initialLength = remember { fragmentLength }
    val initialInterval = remember { fragmentInterval }
    val initialMaxSplit = remember { fragmentMaxSplit }

    val fragmentPacketsEntries = stringArrayResource(R.array.fragment_packets).toList()
    val fragmentPacketsValues = stringArrayResource(R.array.fragment_packets).toList()
    val scrollState = rememberScrollState()

    fun closeDialog() {
        val changed =
            fragmentEnabled != initialEnabled ||
                fragmentPackets != initialPackets ||
                fragmentLength != initialLength ||
                fragmentInterval != initialInterval ||
                fragmentMaxSplit != initialMaxSplit
        if (changed) {
            onRestartIfNeeded()
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { closeDialog() },
        // No title — cleaner quick panel
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScrollbar(scrollState)
                    .verticalScroll(scrollState)
            ) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_fragment_enabled),
                    checked = fragmentEnabled,
                    onCheckedChange = onFragmentEnabledChange
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_fragment_packets),
                    entries = fragmentPacketsEntries,
                    values = fragmentPacketsValues,
                    selectedValue = fragmentPackets,
                    enabled = fragmentEnabled,
                    onSelected = { fragmentPackets = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_length),
                    value = fragmentLength,
                    enabled = fragmentEnabled,
                    onValueChanged = { fragmentLength = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_interval),
                    value = fragmentInterval,
                    enabled = fragmentEnabled,
                    onValueChanged = { fragmentInterval = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_maxsplit),
                    value = fragmentMaxSplit,
                    enabled = fragmentEnabled,
                    keyboardNumber = true,
                    onValueChanged = { fragmentMaxSplit = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { closeDialog() }) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}
