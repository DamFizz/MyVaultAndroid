package com.damfizz.myvault.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.damfizz.myvault.R
import com.damfizz.myvault.ui.glass.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.text.DateFormat
import java.util.Date

private const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

/**
 * Returns a function that asks Google for an access token limited to the app's own hidden Drive
 * folder (drive.appdata). The first time, Google shows its consent screen; later calls are silent.
 * The user signs in on Google's own UI: this app never sees the Google password.
 */
@Composable
internal fun rememberDriveAuth(vm: VaultViewModel, onToken: () -> Unit = {}): () -> Unit {
    val activity = LocalContext.current as Activity
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(result.data) }
            .onSuccess { r ->
                val token = r.accessToken
                if (token != null) { vm.onDriveToken(token); onToken() } else vm.onDriveAuthFailed("no token")
            }
            .onFailure { vm.onDriveAuthFailed(it.message ?: "cancelled") }
    }
    return remember(activity) {
        {
            val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()
            Identity.getAuthorizationClient(activity).authorize(request)
                .addOnSuccessListener { r ->
                    val pending = r.pendingIntent
                    val token = r.accessToken
                    when {
                        r.hasResolution() && pending != null -> launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                        token != null -> { vm.onDriveToken(token); onToken() }
                        else -> vm.onDriveAuthFailed("no token")
                    }
                }
                .addOnFailureListener { vm.onDriveAuthFailed(it.message ?: it.javaClass.simpleName) }
        }
    }
}

/** Sync — port of the web's SyncView. */
@Composable
internal fun SyncScreen(vm: VaultViewModel) {
    val c = LocalGlassColors.current
    val rev = vm.revision
    val prefs = vm.prefs
    val connect = rememberDriveAuth(vm)
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var ask by remember { mutableStateOf("") }
    fun back() { vm.tab = 3; vm.go(Screen.Vault) }
    BackHandler(onBack = ::back)
    val conflicts = remember(rev) { vm.conflicts() }
    val last = remember(rev, vm.syncing) { vm.lastSyncAt() }

    GlassScaffold(topBar = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), ::back, Modifier.align(Alignment.CenterStart)) }) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeTitle(stringResource(R.string.sync_title), Modifier.padding(start = 6.dp))
            Body(stringResource(R.string.sync_subtitle), Modifier.padding(horizontal = 6.dp))

            InsetGroup {
                ListRow(
                    stringResource(R.string.sync_drive),
                    subtitle = when {
                        vm.syncing -> stringResource(R.string.sync_status_syncing)
                        !prefs.driveConnected -> null
                        last != null -> stringResource(R.string.sync_last, date.format(Date(last)))
                        else -> stringResource(R.string.sync_never)
                    },
                    leading = { IconTile(Icons.Filled.CloudSync, 34.dp) },
                )
                if (prefs.driveConnected && prefs.driveEmail != null) {
                    GroupDivider(64.dp)
                    ListRow(stringResource(R.string.sync_account, prefs.driveEmail!!), leading = { IconTile(Icons.Filled.AccountCircle, 34.dp) })
                }
            }
            vm.syncError?.let { Footnote(it, Modifier.padding(horizontal = 16.dp), color = c.danger) }

            if (!prefs.driveConnected) {
                GlassButton(stringResource(R.string.sync_connect), connect, Modifier.fillMaxWidth(), icon = Icons.Filled.Cloud)
            } else {
                if (!vm.hasDriveToken() || vm.needsDriveAuth) {
                    GlassButton(stringResource(R.string.sync_reconnect), connect, Modifier.fillMaxWidth(), icon = Icons.Filled.Cloud)
                } else {
                    GlassButton(stringResource(R.string.sync_now), { vm.syncNow(quiet = false) }, Modifier.fillMaxWidth(), enabled = !vm.syncing, icon = Icons.Filled.Sync)
                }
                if (vm.syncMismatch) {
                    SectionHeader(stringResource(R.string.sync_mismatch_title))
                    GlassButton(stringResource(R.string.sync_keep_local), { ask = "keepLocal" }, Modifier.fillMaxWidth(), prominent = false)
                    GlassButton(stringResource(R.string.sync_use_cloud), { ask = "useCloud" }, Modifier.fillMaxWidth(), prominent = false, destructive = true)
                }
                InsetGroup { ToggleRow(stringResource(R.string.sync_auto), prefs.autoSync, prefs::updateAutoSync, subtitle = stringResource(R.string.sync_auto_help)) }
                if (conflicts.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.sync_conflicts))
                    InsetGroup {
                        conflicts.forEachIndexed { i, n ->
                            ListRow(n.title, subtitle = n.message, leading = { IconTile(Icons.Filled.CallMerge, 34.dp, c.star) }, onClick = n.keptId?.let { id -> { vm.go(Screen.Detail(id)) } })
                            if (i != conflicts.lastIndex) GroupDivider(64.dp)
                        }
                    }
                    TextLink(stringResource(R.string.sync_conflicts_dismiss), vm::dismissConflicts)
                }
                Spacer(Modifier.height(4.dp))
                InsetGroup {
                    ListRow(stringResource(R.string.sync_disconnect), onClick = { ask = "disconnect" })
                    GroupDivider()
                    ListRow(stringResource(R.string.sync_delete_cloud), titleColor = c.danger, onClick = { ask = "deleteCloud" })
                }
            }
            Footnote(stringResource(R.string.sync_note), Modifier.padding(horizontal = 16.dp))
        }
    }

    GlassAlert(ask == "disconnect", stringResource(R.string.sync_disconnect), stringResource(R.string.sync_disconnect_confirm), stringResource(R.string.sync_disconnect), { ask = ""; vm.disconnectDrive() }, stringResource(R.string.cancel), { ask = "" })
    GlassAlert(ask == "deleteCloud", stringResource(R.string.sync_delete_cloud), stringResource(R.string.sync_delete_cloud_confirm), stringResource(R.string.org_delete), { ask = ""; vm.deleteCloudCopy() }, stringResource(R.string.cancel), { ask = "" }, destructive = true)
    GlassAlert(ask == "keepLocal", stringResource(R.string.sync_mismatch_title), stringResource(R.string.sync_keep_local), stringResource(R.string.import_replace), { ask = ""; vm.keepLocalVault() }, stringResource(R.string.cancel), { ask = "" }, destructive = true)
    GlassAlert(ask == "useCloud", stringResource(R.string.sync_mismatch_title), stringResource(R.string.sync_use_cloud), stringResource(R.string.import_replace), { ask = ""; vm.downloadFromDrive() }, stringResource(R.string.cancel), { ask = "" }, destructive = true)
}

/** After unlocking, quietly refresh the Google token and sync (only when Drive is already connected). */
@Composable
internal fun AutoSyncOnUnlock(vm: VaultViewModel) {
    val connect = rememberDriveAuth(vm)
    LaunchedEffect(vm.session) {
        if (vm.session != null && vm.prefs.driveConnected && vm.prefs.autoSync && !vm.hasDriveToken()) connect()
    }
}
