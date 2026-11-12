package com.damfizz.myvault.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.damfizz.myvault.R
import com.damfizz.myvault.core.Csv
import com.damfizz.myvault.ui.glass.*
import java.text.DateFormat
import java.util.Date

/** Backup & restore — port of the web's BackupView: export, restore (merge / replace), snapshots, CSV import. */
@Composable
internal fun BackupScreen(vm: VaultViewModel) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val rev = vm.revision
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    fun back() { vm.closeOpenedBackup(); vm.tab = 3; vm.go(Screen.Vault) }
    BackHandler(onBack = ::back)

    // ---- export
    var exportSheet by remember { mutableStateOf(false) }
    var useMaster by rememberSaveable { mutableStateOf(true) }
    var master by rememberSaveable { mutableStateOf("") }
    var separate by rememberSaveable { mutableStateOf("") }
    var pendingText by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val text = pendingText
        pendingText = null
        if (uri != null && text != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            if (ok) vm.backupSaved()
        }
    }

    // ---- restore
    var fileText by remember { mutableStateOf<String?>(null) }
    var backupPw by rememberSaveable { mutableStateOf("") }
    var backupMaster by rememberSaveable { mutableStateOf("") }
    var backupSk by rememberSaveable { mutableStateOf("") }
    var askReplace by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.closeOpenedBackup()
            fileText = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }
    }

    // ---- CSV
    var csv by remember { mutableStateOf<Csv.Preview?>(null) }
    var csvError by remember { mutableStateOf<String?>(null) }
    var csvAck by remember { mutableStateOf(false) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            csvAck = false
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            runCatching { Csv.detect(text.orEmpty()) }.onSuccess { csv = it; csvError = null }.onFailure { csv = null; csvError = it.message }
        }
    }

    var snapshotToRestore by remember { mutableStateOf<String?>(null) }
    val snapshots = remember(rev) { vm.snapshots() }
    val opened = vm.openedBackup

    GlassScaffold(topBar = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), ::back, Modifier.align(Alignment.CenterStart)) }) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeTitle(stringResource(R.string.bk_title).replace("&amp;", "&"), Modifier.padding(start = 6.dp))
            Body(stringResource(R.string.bk_subtitle), Modifier.padding(horizontal = 6.dp))

            SectionHeader(stringResource(R.string.bk_export))
            Footnote(stringResource(R.string.bk_export_help), Modifier.padding(horizontal = 16.dp))
            GlassButton(stringResource(R.string.bk_export), { exportSheet = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Download)
            Footnote(vm.lastBackupAt()?.let { stringResource(R.string.bk_last, date.format(Date(it))) } ?: stringResource(R.string.bk_never), Modifier.padding(horizontal = 16.dp))

            SectionHeader(stringResource(R.string.bk_restore))
            Footnote(stringResource(R.string.bk_restore_help), Modifier.padding(horizontal = 16.dp))
            GlassButton(stringResource(R.string.import_pick), { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.FolderOpen)
            if (fileText != null && opened == null) {
                GlassTextField(backupPw, { backupPw = it }, stringResource(R.string.import_backup_password), secret = true)
                ErrorText(vm.error)
                GlassButton(stringResource(R.string.import_open), { vm.openBackupForRestore(fileText!!, backupPw); backupPw = "" }, Modifier.fillMaxWidth(), enabled = backupPw.isNotEmpty())
            }
            if (opened != null) {
                InsetGroup {
                    ListRow(stringResource(R.string.bk_valid, date.format(Date(opened.createdAt)), opened.records.size), leading = { IconTile(Icons.Filled.Verified, 34.dp, androidx.compose.ui.graphics.Color(0xFF46A758)) })
                }
                Footnote(stringResource(R.string.bk_merge_help), Modifier.padding(horizontal = 16.dp))
                GlassTextField(backupMaster, { backupMaster = it }, stringResource(R.string.bk_backup_master), secret = true)
                if (opened.header.secretKey != null) GlassTextField(backupSk, { backupSk = it.uppercase() }, stringResource(R.string.bk_backup_sk), mono = true)
                ErrorText(vm.error)
                GlassButton(stringResource(R.string.bk_merge), { vm.mergeBackup(backupMaster, backupSk); backupMaster = ""; fileText = null }, Modifier.fillMaxWidth(), enabled = backupMaster.isNotEmpty(), icon = Icons.Filled.MergeType)
                Footnote(stringResource(R.string.bk_replace_help), Modifier.padding(horizontal = 16.dp))
                GlassButton(stringResource(R.string.bk_replace), { askReplace = true }, Modifier.fillMaxWidth(), prominent = false, destructive = true, icon = Icons.Filled.SwapHoriz)
            }

            SectionHeader(stringResource(R.string.bk_snapshots))
            Footnote(stringResource(R.string.bk_snapshots_help), Modifier.padding(horizontal = 16.dp))
            if (snapshots.isEmpty()) Footnote(stringResource(R.string.bk_snapshots_none), Modifier.padding(horizontal = 16.dp))
            else InsetGroup {
                snapshots.forEachIndexed { i, s ->
                    ListRow(stringResource(R.string.bk_snapshot_info, date.format(Date(s.createdAt)), s.records.size), subtitle = s.reason, leading = { IconTile(Icons.Filled.History, 34.dp) }, onClick = { snapshotToRestore = s.id }) {
                        RowIconButton(Icons.Filled.Delete, stringResource(R.string.org_delete), { vm.deleteSnapshot(s.id) }, tint = c.danger)
                    }
                    if (i != snapshots.lastIndex) GroupDivider(64.dp)
                }
            }

            SectionHeader(stringResource(R.string.csv_title))
            Footnote(stringResource(R.string.csv_help), Modifier.padding(horizontal = 16.dp))
            Footnote(stringResource(R.string.csv_warning), Modifier.padding(horizontal = 16.dp), color = c.danger)
            GlassButton(stringResource(R.string.csv_choose), { csvPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.UploadFile)
            csvError?.let { Footnote(it, Modifier.padding(horizontal = 16.dp), color = c.danger) }
            csv?.let { p ->
                InsetGroup {
                    ListRow(stringResource(R.string.csv_detected, p.detectedFormat, p.rows.size), subtitle = p.mapping.keys.joinToString(", "), leading = { IconTile(Icons.Filled.TableChart, 34.dp) })
                    GroupDivider(64.dp)
                    ToggleRow(stringResource(R.string.csv_ack), csvAck, { csvAck = it })
                }
                GlassButton(stringResource(R.string.csv_import, p.rows.size), { vm.importCsv(p); csv = null }, Modifier.fillMaxWidth(), enabled = csvAck)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    GlassSheet(exportSheet, onDismiss = { exportSheet = false; master = ""; separate = ""; vm.clearError() }) {
        Text(stringResource(R.string.bk_export), style = Type.headline, color = c.text)
        GlassTextField(master, { master = it }, stringResource(R.string.master_password), secret = true)
        InsetGroup { ToggleRow(stringResource(R.string.bk_use_master), useMaster, { useMaster = it }) }
        if (!useMaster) {
            GlassTextField(separate, { separate = it }, stringResource(R.string.bk_separate_password), secret = true)
            Footnote(stringResource(R.string.bk_separate_note))
        }
        ErrorText(vm.error)
        GlassButton(
            stringResource(R.string.bk_save),
            {
                vm.exportBackup(master, if (useMaster) null else separate) { text ->
                    pendingText = text
                    exportSheet = false
                    master = ""; separate = ""
                    saver.launch("myvault-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date()) + ".myvault")
                }
            },
            Modifier.fillMaxWidth(),
            enabled = master.isNotEmpty() && (useMaster || separate.isNotEmpty()),
        )
    }
    GlassAlert(
        visible = askReplace,
        title = stringResource(R.string.bk_replace),
        message = stringResource(R.string.bk_replace_help),
        confirm = stringResource(R.string.import_replace),
        onConfirm = { askReplace = false; fileText = null; vm.replaceWithBackup() },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { askReplace = false },
        destructive = true,
    )
    GlassAlert(
        visible = snapshotToRestore != null,
        title = stringResource(R.string.bk_snapshot_restore),
        message = stringResource(R.string.bk_snapshot_confirm),
        confirm = stringResource(R.string.action_restore),
        onConfirm = { val id = snapshotToRestore; snapshotToRestore = null; if (id != null) vm.restoreSnapshot(id) },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { snapshotToRestore = null },
        destructive = true,
    )
}
