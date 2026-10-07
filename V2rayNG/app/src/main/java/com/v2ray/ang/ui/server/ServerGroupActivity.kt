package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.viewModels
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.AppConfig.TAG_PROXY
import com.v2ray.ang.R
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.base.EditorOutcomeEffect
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsSwitchItem

class ServerGroupActivity : BaseComponentActivity() {

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    /** PattNG: whether the app runs on the profile edited; read as the screen opens, see onCreate, not first in its composition. */
    private var isRunning = false
    private val subscriptionId by lazy { intent.getStringExtra("subscriptionId") }
    private lateinit var subscriptions: List<PolicyGroupSubscription>
    private lateinit var fallbackSuggestions: List<String>

    private lateinit var initialRemarks: String
    private lateinit var initialFilter: String
    private var initialType: Int = 0
    private lateinit var initialSubscriptionId: String
    private var initialTestOutbounds: Boolean = false
    private lateinit var initialFallbackTag: String

    /** PattNG: the save, which outlives this activity when it is recreated, see [ServerGroupViewModel]. */
    private val viewModel: ServerGroupViewModel by viewModels {
        viewModelFactory {
            initializer { ServerGroupViewModel(application, ProfileEditorRepository(), editGuid, subscriptionId) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRunning = intent.getBooleanExtra("isRunning", false)
                && editGuid.isNotEmpty()
                && editGuid == MmkvManager.getSelectServer()

        val config = MmkvManager.decodeServerConfig(editGuid)
        populateSubscriptionSpinner()
        fallbackSuggestions = (
                BUILTIN_OUTBOUND_TAGS + SettingsManager.getProfileRemarks(
                    excludeConfigTypes = setOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP)
                )
                ).filter { it != TAG_PROXY }

        initialRemarks = config?.remarks ?: ""
        initialFilter = config?.policyGroupFilter ?: ""
        initialType = config?.policyGroupType?.toIntOrNull() ?: 0
        initialTestOutbounds = config == null || config.policyGroupTestOutbounds != false ||
                !BalancerStrategyType.from(config.policyGroupType).supportsObservatory
        initialFallbackTag = config?.policyGroupFallbackTag.orEmpty()
        // PattNG: the subscription is picked by its key: a group of a subscription gone, or a new one, starts on all.
        val picked = if (config != null) config.policyGroupSubscriptionId.orEmpty() else subscriptionId.orEmpty()
        initialSubscriptionId = subscriptions.pick(picked).id
    }

    @Composable
    override fun ScreenContent() {
        EditorOutcomeEffect(
            viewModel = viewModel,
            onSaved = { guid ->
                ProfileEditorResult.run {
                    finishSaved(
                        guid = guid,
                        restartService = isRunning
                    )
                }
            },
            onDeleted = {
                ProfileEditorResult.run {
                    finishDeleted(editGuid)
                }
            }
        )
        ServerGroupScreen(
            editGuid = editGuid,
            isRunning = isRunning,
            subscriptions = subscriptions,
            initialRemarks = initialRemarks,
            initialFilter = initialFilter,
            initialType = initialType,
            initialSubscriptionId = initialSubscriptionId,
            initialTestOutbounds = initialTestOutbounds,
            initialFallbackTag = initialFallbackTag,
            fallbackSuggestions = fallbackSuggestions,
            onBackClick = { finish() },
            onSave = { remarks, filter, typeIdx, subscription, testOutbounds, fallbackTag ->
                saveServer(remarks, filter, typeIdx, subscription, testOutbounds, fallbackTag)
            },
            onDelete = { viewModel.delete() }
        )
    }

    /**
     * PattNG: the screen closes only once the save or the delete that runs has written, telling the screen it returns
     * to what it did, see [com.v2ray.ang.ui.base.EditorViewModel.leaveScreen].
     */
    override fun finish() {
        if (viewModel.leaveScreen()) super.finish()
    }

    private fun saveServer(
        remarks: String,
        filter: String,
        typeIdx: Int,
        subscriptionId: String,
        testOutbounds: Boolean,
        fallbackTag: String,
    ) {
        val subscription = subscriptions.pick(subscriptionId)
        viewModel.save(
            PolicyGroupEdit(
                remarks = remarks,
                filter = filter,
                type = typeIdx,
                typeLabel = stringArrayPolicyGroupType().getOrNull(typeIdx).orEmpty(),
                subscriptionId = subscription.id,
                subscriptionLabel = subscription.label,
                testOutbounds = testOutbounds,
                fallbackTag = fallbackTag,
            )
        )
    }

    private fun populateSubscriptionSpinner() {
        subscriptions = policyGroupSubscriptions(
            all = getString(R.string.filter_config_all),
            subscriptions = MmkvManager.decodeSubscriptions().map { sub -> sub.guid to sub.subscription.remarks.ifBlank { sub.guid } },
            numbered = { name, number -> getString(R.string.label_numbered, name, number) },
        )
    }

    private fun stringArrayPolicyGroupType(): Array<String> =
        resources.getStringArray(R.array.policy_group_type)
}

@Composable
fun ServerGroupScreen(
    editGuid: String,
    isRunning: Boolean,
    subscriptions: List<PolicyGroupSubscription>,
    initialRemarks: String,
    initialFilter: String,
    initialType: Int,
    initialSubscriptionId: String,
    initialTestOutbounds: Boolean,
    initialFallbackTag: String,
    fallbackSuggestions: List<String>,
    onBackClick: () -> Unit,
    onSave: (String, String, Int, String, Boolean, String) -> Unit,
    onDelete: () -> Unit
) {
    val typeEntries = stringArrayResource(R.array.policy_group_type).toList()

    var remarks by rememberSaveable { mutableStateOf(initialRemarks) }
    var isRemarksError by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var typeValue by rememberSaveable { mutableStateOf(typeEntries.getOrNull(initialType).orEmpty()) }
    // PattNG: the subscription picked, by its key; the list shows it by its label.
    var subscriptionId by rememberSaveable { mutableStateOf(initialSubscriptionId) }
    val pickedSubscription = subscriptions.pick(subscriptionId)
    var testOutbounds by rememberSaveable { mutableStateOf(initialTestOutbounds) }
    var fallbackTag by rememberSaveable { mutableStateOf(initialFallbackTag) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val showDelete = editGuid.isNotEmpty() && !isRunning
    val selectedType = typeEntries.indexOf(typeValue).coerceAtLeast(0).toString()
    val supportsObservatory = BalancerStrategyType.from(selectedType).supportsObservatory

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = EConfigType.POLICYGROUP.toString(),
                onBackClick = onBackClick,
                actions = {
                    if (showDelete) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.acc_delete))
                        }
                    }
                    IconButton(onClick = {
                        val remarksErr = remarks.isBlank()
                        isRemarksError = remarksErr

                        val hasError = remarksErr
                        if (!hasError) {
                            val typeIdx = typeEntries.indexOf(typeValue).coerceAtLeast(0)
                            onSave(remarks, filter, typeIdx, pickedSubscription.id, testOutbounds, fallbackTag)
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
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = remarks,
                onValueChange = { remarks = it },
                isError = isRemarksError
            )
            FormDropdownField(
                label = stringResource(R.string.title_policy_group_type),
                value = typeValue,
                options = typeEntries,
                onValueChange = { typeValue = it }
            )
            FormDropdownField(
                label = stringResource(R.string.title_policy_group_subscription_id),
                value = pickedSubscription.label,
                options = subscriptions.map { it.label },
                onValueChange = { label -> subscriptions.firstOrNull { it.label == label }?.let { subscriptionId = it.id } }
            )
            FormTextField(stringResource(R.string.title_policy_group_subscription_filter), filter, { filter = it })
            if (supportsObservatory) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_policy_group_test_outbounds),
                    checked = testOutbounds,
                    onCheckedChange = { testOutbounds = it }
                )
                if (testOutbounds) {
                    FormDropdownField(
                        label = stringResource(R.string.title_policy_group_fallback),
                        value = fallbackTag,
                        options = fallbackSuggestions,
                        onValueChange = { fallbackTag = it },
                        editable = true
                    )
                }
                NavigationBarsSpacer()
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_policy_group),
            itemName = initialRemarks,
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }
}
