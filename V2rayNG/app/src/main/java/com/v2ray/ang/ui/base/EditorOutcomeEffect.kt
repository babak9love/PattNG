package com.v2ray.ang.ui.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess

/**
 * PattNG: acts once on each outcome of [viewModel], in whichever activity shows the editor when it comes: tells a
 * refusal; tells a save, then hands the key it stored as to [onSaved]; hands a delete to [onDeleted].
 */
@Composable
fun EditorOutcomeEffect(
    viewModel: EditorViewModel,
    onSaved: (key: String) -> Unit,
    onDeleted: () -> Unit = {},
) {
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(outcome) {
        when (val result = outcome ?: return@LaunchedEffect) {
            is EditorOutcome.Refused -> context.toast(
                if (result.args.isEmpty()) context.getString(result.message)
                else context.getString(result.message, *result.args.toTypedArray())
            )

            is EditorOutcome.Saved -> {
                context.toastSuccess(R.string.toast_success)
                onSaved(result.key)
            }

            EditorOutcome.Deleted -> onDeleted()
        }
        viewModel.onOutcomeHandled()
    }
}
