package com.v2ray.ang.ui.subscription

import android.os.Bundle
import android.text.TextUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toLongEx
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.ui.server.ProxyChainProblem
import com.v2ray.ang.ui.server.proxyChainProblem
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubEditActivity : BaseComponentActivity() {
    private val editSubId by lazy { intent.getStringExtra("subId").orEmpty() }
    private lateinit var suggestions: List<String>
    private lateinit var subItem: SubscriptionItem

    /** PattNG: the save under way; the subscription is read and written off the main thread, one save at a time. */
    private var saveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        suggestions = SettingsManager.getProfileRemarks(
            excludeConfigTypes = setOf(
                EConfigType.CUSTOM,
                EConfigType.POLICYGROUP,
                EConfigType.PROXYCHAIN,
            )
        )
        subItem = MmkvManager.decodeSubscription(editSubId) ?: SubscriptionItem()
    }

    @Composable
    override fun ScreenContent() {
        SubEditScreen(
            editSubId = editSubId,
            initial = subItem,
            profileSuggestions = suggestions,
            onBackClick = { finish() },
            onSave = { saveServer(it) },
            onDelete = { deleteServer() }
        )
    }

    /**
     * Saves the subscription as stored at this moment with the edits [applyEdits] sets on it, which reads the screen's
     * state, so it runs on the main thread; it tells why, and gives false, when an edit cannot be saved. PattNG: the
     * previous and the next profile are found by their names, as the chain finds them when it runs: a name no profile
     * has, as after a rename or a delete, or several have, is told rather than saved.
     */
    private fun saveServer(applyEdits: (SubscriptionItem) -> Boolean) {
        if (saveJob?.isActive == true) {
            return
        }
        saveJob = lifecycleScope.launch {
            val subItem = withContext(Dispatchers.IO) { MmkvManager.decodeSubscription(editSubId) } ?: SubscriptionItem()
            if (!applyEdits(subItem)) {
                return@launch
            }
            if (TextUtils.isEmpty(subItem.remarks)) {
                return@launch
            }
            if (subItem.url.isNotEmpty()) {
                if (!Utils.isValidUrl(subItem.url)) {
                    return@launch
                }
                if (!Utils.isValidSubUrl(subItem.url) && !subItem.allowInsecureUrl) {
                    return@launch
                }
            }

            if (subItem.autoUpdate && subItem.updateInterval < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES) {
                return@launch
            }

            val neighbors = listOfNotNull(subItem.prevProfile, subItem.nextProfile).map { it.trim() }.filter { it.isNotEmpty() }
            val problem = withContext(Dispatchers.IO) {
                proxyChainProblem(neighbors) { SettingsManager.findServerViaRemarks(it, CoreConfigContextBuilder::takesAsHop) }
            }
            when (problem) {
                is ProxyChainProblem.NotFound -> {
                    toast(getString(R.string.toast_profile_name_not_found, problem.name))
                    return@launch
                }

                is ProxyChainProblem.SameName -> {
                    toast(getString(R.string.toast_profile_name_duplicate, problem.name))
                    return@launch
                }

                // The previous and the next profile chain every profile of the subscription. Either can be Aether,
                // but not both: one core runs, so a chain can have one Aether profile.
                ProxyChainProblem.SecondAether -> {
                    toast(R.string.aether_chain_one_profile)
                    return@launch
                }

                null -> Unit
            }

            withContext(Dispatchers.IO) {
                MmkvManager.encodeSubscription(editSubId, subItem)
                SubscriptionUpdater.syncOne(subId = editSubId)
            }
            SettingsChangeManager.makeSetupGroupTab()
            toastSuccess(R.string.toast_success)
            finish()
        }
    }

    private fun deleteServer(): Boolean {
        if (editSubId.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                SettingsManager.removeSubscriptionWithDefault(editSubId)
                SettingsChangeManager.makeSetupGroupTab()
                launch(Dispatchers.Main) { finish() }
            }
        }
        return true
    }
}

@Composable
fun SubEditScreen(
    editSubId: String,
    initial: SubscriptionItem,
    profileSuggestions: List<String>,
    onBackClick: () -> Unit,
    onSave: ((SubscriptionItem) -> Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var remarks by rememberSaveable { mutableStateOf(initial.remarks.orEmpty()) }
    var isRemarksError by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(initial.url.orEmpty()) }
    var isUrlError by rememberSaveable { mutableStateOf(false) }
    var userAgent by rememberSaveable { mutableStateOf(initial.userAgent.orEmpty()) }
    var requestHeaders by rememberSaveable { mutableStateOf(initial.requestHeaders.orEmpty()) }
    var filter by rememberSaveable { mutableStateOf(initial.filter ?: "") }
    var overrideAddress by rememberSaveable { mutableStateOf(initial.overrideAddress ?: "") }
    var overridePort by rememberSaveable { mutableStateOf(initial.overridePort?.toString() ?: "") }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var autoUpdate by rememberSaveable { mutableStateOf(initial.autoUpdate) }
    var updateInterval by rememberSaveable { mutableStateOf(initial.updateInterval.toString()) }
    var isUpdateIntervalError by rememberSaveable { mutableStateOf(false) }
    var allowInsecureUrl by rememberSaveable { mutableStateOf(initial.allowInsecureUrl) }
    var prevProfile by rememberSaveable { mutableStateOf(initial.prevProfile ?: "") }
    var nextProfile by rememberSaveable { mutableStateOf(initial.nextProfile ?: "") }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val confirmRemove = MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE, false)
    val scrollState = rememberScrollState()

    // Sets what this screen edits on [subItem], the subscription as stored when it is saved; false, with the reason
    // told, when a field cannot be saved. The profiles are read, and the subscription written, by the save.
    fun applyEdits(subItem: SubscriptionItem): Boolean {
        val overridePortText = overridePort.trim()
        val overridePortValue = overridePortText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (overridePortText.isNotEmpty() && overridePortValue == null) {
            context.toast(R.string.toast_invalid_override_port)
            return false
        }
        subItem.remarks = remarks
        subItem.url = url
        subItem.userAgent = userAgent
        subItem.requestHeaders = requestHeaders
        subItem.filter = filter
        subItem.enabled = enabled
        subItem.autoUpdate = autoUpdate
        subItem.updateInterval = updateInterval.toLongEx()
        subItem.prevProfile = prevProfile
        subItem.nextProfile = nextProfile
        subItem.allowInsecureUrl = allowInsecureUrl
        subItem.overrideAddress = overrideAddress.trim().ifEmpty { null }
        subItem.overridePort = overridePortValue
        return true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_sub_setting),
                onBackClick = onBackClick,
                actions = {
                    if (editSubId.isNotEmpty()) {
                        IconButton(onClick = {
                            if (confirmRemove) showDeleteConfirm = true else onDelete()
                        }) {
                            Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.acc_delete))
                        }
                    }
                    IconButton(onClick = {
                        val remarksErr = remarks.isBlank()
                        val urlErr = url.isNotEmpty() && (
                            !Utils.isValidUrl(url) || (!Utils.isValidSubUrl(url) && !allowInsecureUrl)
                        )
                        val intervalErr = autoUpdate && updateInterval.toLongEx() < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES

                        isRemarksError = remarksErr
                        isUrlError = urlErr
                        isUpdateIntervalError = intervalErr

                        val hasError = remarksErr || urlErr || intervalErr
                        if (!hasError) {
                            onSave(::applyEdits)
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_fab_check), contentDescription = stringResource(R.string.acc_save))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(scrollState)
                .verticalScrollbar(scrollState)
                .padding(vertical = 8.dp)
                .padding(bottom = 36.dp)
        ) {
            FormTextField(
                label = stringResource(R.string.sub_setting_remarks),
                value = remarks,
                onValueChange = { remarks = it },
                isError = isRemarksError
            )
            FormTextField(
                label = stringResource(R.string.sub_setting_url),
                value = url,
                onValueChange = { url = it },
                isError = isUrlError,
                supportingText = if (isUrlError) stringResource(R.string.toast_invalid_url) else null
            )
            FormTextField(stringResource(R.string.sub_setting_user_agent), userAgent, { userAgent = it })
            FormTextField(stringResource(R.string.sub_setting_request_headers), requestHeaders, { requestHeaders = it })
            FormTextField(stringResource(R.string.sub_setting_filter), filter, { filter = it })
            FormTextField(
                stringResource(R.string.sub_setting_override_address),
                overrideAddress,
                { overrideAddress = it },
                placeholder = stringResource(R.string.sub_setting_override_tip)
            )
            FormTextField(
                stringResource(R.string.sub_setting_override_port),
                overridePort,
                { overridePort = it },
                keyboardType = KeyboardType.Number,
                placeholder = stringResource(R.string.sub_setting_override_tip)
            )
            SettingsSwitchItem(
                title = stringResource(R.string.sub_setting_enable),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )

            SettingsSwitchItem(
                title = stringResource(R.string.sub_auto_update),
                checked = autoUpdate,
                onCheckedChange = { autoUpdate = it }
            )

            FormTextField(
                label = stringResource(R.string.title_pref_auto_update_interval),
                value = updateInterval,
                onValueChange = { updateInterval = it },
                keyboardType = KeyboardType.Number,
                isError = isUpdateIntervalError,
                supportingText = if (isUpdateIntervalError) stringResource(R.string.toast_invalid_update_interval) else null
            )

            SettingsSwitchItem(
                title = stringResource(R.string.sub_allow_insecure_url),
                checked = allowInsecureUrl,
                onCheckedChange = { allowInsecureUrl = it }
            )
            FormDropdownField(
                label = stringResource(R.string.sub_setting_pre_profile),
                placeholder = stringResource(R.string.sub_setting_pre_profile_tip),
                value = prevProfile,
                options = profileSuggestions,
                onValueChange = { prevProfile = it },
                editable = true,
                supportingText = stringResource(R.string.sub_setting_entry_proxy_tip)
            )
            FormDropdownField(
                label = stringResource(R.string.sub_setting_next_profile),
                placeholder = stringResource(R.string.sub_setting_pre_profile_tip),
                value = nextProfile,
                options = profileSuggestions,
                onValueChange = { nextProfile = it },
                editable = true,
                supportingText = stringResource(R.string.sub_setting_exit_proxy_tip)
            )
            NavigationBarsSpacer()
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_subscription_group),
            itemName = initial.remarks,
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }
}
