package com.damfizz.myvault.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damfizz.myvault.R
import com.damfizz.myvault.core.CustomField
import com.damfizz.myvault.core.Encoding
import com.damfizz.myvault.core.ItemFilter
import com.damfizz.myvault.core.ItemType
import com.damfizz.myvault.core.Items
import com.damfizz.myvault.core.SortBy
import com.damfizz.myvault.core.VaultCollection
import com.damfizz.myvault.core.VaultItem
import com.damfizz.myvault.ui.glass.*
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ helpers

internal fun iconFor(type: String): ImageVector = when (type) {
    ItemType.WIFI -> Icons.Filled.Wifi
    ItemType.NOTE -> Icons.AutoMirrored.Filled.Notes
    ItemType.LICENSE -> Icons.Filled.VpnKey
    ItemType.RECOVERY_CODES -> Icons.Filled.Key
    ItemType.CUSTOM -> Icons.Filled.Tune
    else -> Icons.Filled.Person
}

internal fun typeLabel(type: String): Int = when (type) {
    ItemType.NOTE -> R.string.type_note
    ItemType.WIFI -> R.string.type_wifi
    ItemType.RECOVERY_CODES -> R.string.type_recoveryCodes
    ItemType.LICENSE -> R.string.type_license
    ItemType.CUSTOM -> R.string.type_custom
    else -> R.string.type_login
}

internal fun copySensitive(context: Context, label: String, value: String, clearAfterSeconds: Int = 30) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) else putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    if (clearAfterSeconds <= 0) return
    Handler(Looper.getMainLooper()).postDelayed({
        val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (current == value) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, clearAfterSeconds * 1000L)
}

/** Folders as a tree with depth, like the web's sectionOptions(). */
private fun folderTree(collections: List<VaultCollection>): List<Pair<VaultCollection, Int>> {
    val folders = collections.filter { it.kind == "section" }
    val out = mutableListOf<Pair<VaultCollection, Int>>()
    fun walk(parent: String?, depth: Int) {
        for (f in folders.filter { it.parentId == parent }.sortedWith(compareBy<VaultCollection> { it.order }.thenBy { it.name })) {
            out += f to depth
            if (depth < 8) walk(f.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

@Composable
private fun filterLabel(filter: ItemFilter, collections: List<VaultCollection>): String = when (filter) {
    ItemFilter.All -> stringResource(R.string.vault_all)
    ItemFilter.Favorites -> stringResource(R.string.vault_favorites)
    ItemFilter.RecentAdded -> stringResource(R.string.nav_recent_added)
    ItemFilter.RecentUsed -> stringResource(R.string.nav_recent_used)
    is ItemFilter.Type -> stringResource(when (filter.type) { "login" -> R.string.nav_passwords; "note" -> R.string.nav_notes; else -> R.string.nav_other })
    is ItemFilter.Category -> collections.find { it.id == filter.id }?.name ?: stringResource(R.string.field_category)
    is ItemFilter.Section -> collections.find { it.id == filter.id }?.name ?: stringResource(R.string.field_folder)
    is ItemFilter.Tag -> "#${filter.tag}"
    ItemFilter.Uncategorized -> stringResource(R.string.nav_uncategorized)
    ItemFilter.Archive -> stringResource(R.string.nav_archive)
    ItemFilter.Trash -> stringResource(R.string.nav_trash)
}

/** Spaced column for sheet bodies (the sheet itself scrolls when it is taller than the screen allows). */
@Composable
internal fun SheetList(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
internal fun CheckMark(on: Boolean) {
    if (on) Icon(Icons.Filled.Check, null, tint = LocalGlassColors.current.accent, modifier = Modifier.size(20.dp))
}

// ------------------------------------------------------------------ main (tabs)

@Composable
internal fun MainScreen(vm: VaultViewModel) {
    val tabs = listOf(
        TabItem(Icons.Filled.Shield, stringResource(R.string.tab_vault)),
        TabItem(Icons.Filled.Password, stringResource(R.string.tab_generator)),
        TabItem(Icons.Filled.HealthAndSafety, stringResource(R.string.tab_security)),
        TabItem(Icons.Filled.Settings, stringResource(R.string.settings)),
    )
    val lock = stringResource(R.string.lock)
    val add = stringResource(R.string.action_add)
    var chooseType by remember { mutableStateOf(false) }
    GlassScaffold(
        topBar = {
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.tab == 0) GlassIconButton(Icons.Filled.Add, add, { chooseType = true })
                GlassIconButton(Icons.Filled.Lock, lock, vm::lock)
            }
        },
        bottomBar = { GlassTabBar(tabs, vm.tab, { vm.tab = it }, Modifier.fillMaxWidth()) },
    ) { pad ->
        AnimatedContent(vm.tab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "tab", modifier = Modifier.fillMaxSize()) { tab ->
            when (tab) {
                0 -> VaultList(vm, pad, favouritesOnly = false)
                1 -> GeneratorTab(vm, pad)
                2 -> SecurityTab(vm, pad)
                else -> SettingsTab(vm, pad)
            }
        }
    }
    GlassSheet(visible = chooseType, onDismiss = { chooseType = false }) {
        Text(stringResource(R.string.item_new_title), style = Type.headline, color = LocalGlassColors.current.text)
        InsetGroup {
            Items.TYPES.forEachIndexed { i, type ->
                ListRow(stringResource(typeLabel(type)), leading = { IconTile(iconFor(type)) }, onClick = { chooseType = false; vm.startNew(type) })
                if (i != Items.TYPES.lastIndex) GroupDivider(68.dp)
            }
        }
    }
}

@Composable
private fun VaultList(vm: VaultViewModel, pad: PaddingValues, favouritesOnly: Boolean) {
    val c = LocalGlassColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var showViews by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var askEmpty by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    if (vm.selection.isNotEmpty()) BackHandler(onBack = vm::clearSelection)
    val rev = vm.revision
    val filter = if (favouritesOnly) ItemFilter.Favorites else vm.filter
    val collections = remember(rev) { vm.collections() }
    val categories = remember(collections) { collections.filter { it.kind == "category" } }
    val shown = remember(rev, query, filter, vm.sortBy) { vm.visibleItems(filter, query) }
    val untitled = stringResource(R.string.untitled)

    LazyColumn(contentPadding = pad, modifier = Modifier.fillMaxSize()) {
        item { LargeTitle(if (favouritesOnly) stringResource(R.string.vault_favorites) else "MyVault", Modifier.padding(start = 6.dp, bottom = 12.dp)) }
        item { GlassTextField(query, { query = it }, stringResource(R.string.vault_search), leading = Icons.Filled.Search) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                if (!favouritesOnly) {
                    item { IconChip(Icons.Filled.FilterList, filterLabel(filter, collections), selected = filter != ItemFilter.All) { showViews = true } }
                }
                item { IconChip(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort_title), selected = false) { showSort = true } }
                if (!favouritesOnly) {
                    items(categories, key = { it.id }) { cat ->
                        val f = ItemFilter.Category(cat.id)
                        Chip(cat.name, filter == f) { vm.filter = if (filter == f) ItemFilter.All else f }
                    }
                }
            }
        }
        if (vm.selection.isNotEmpty()) {
            item {
                val ids = vm.selection.toList()
                InsetGroup(Modifier.padding(bottom = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sel_count, ids.size), style = Type.headline, color = c.text, modifier = Modifier.weight(1f))
                        if (filter == ItemFilter.Trash) {
                            RowIconButton(Icons.Filled.RestoreFromTrash, stringResource(R.string.action_restore), { vm.restore(ids); vm.clearSelection() })
                        } else {
                            RowIconButton(Icons.Filled.Star, stringResource(R.string.action_favorite), { vm.favoriteSelected(true) })
                            RowIconButton(Icons.Filled.DriveFileMove, stringResource(R.string.sel_move), { showMove = true })
                            RowIconButton(Icons.Filled.Archive, stringResource(if (filter == ItemFilter.Archive) R.string.action_unarchive else R.string.action_archive), { vm.archive(ids, filter != ItemFilter.Archive); vm.clearSelection() })
                            RowIconButton(Icons.Filled.Delete, stringResource(R.string.action_trash), { vm.trash(ids); vm.clearSelection() }, tint = c.danger)
                        }
                        RowIconButton(Icons.Filled.Close, stringResource(R.string.cancel), vm::clearSelection, tint = c.text2)
                    }
                }
            }
        } else if (filter == ItemFilter.All && query.isBlank()) {
            // Overview: what needs attention (web dashboard)
            val corrupted = vm.corruptedCount()
            val due = vm.backupDue()
            val report = vm.securityReport()
            if (corrupted > 0 || due || (report != null && report.withPassword > 0)) {
                item {
                    InsetGroup(Modifier.padding(bottom = 12.dp)) {
                        if (report != null && report.withPassword > 0) {
                            ListRow(
                                stringResource(R.string.dash_security, report.score),
                                subtitle = stringResource(R.string.dash_security_sub, report.weak.size, report.reused.sumOf { it.size }),
                                leading = { IconTile(Icons.Filled.HealthAndSafety, 34.dp) },
                                onClick = { vm.tab = 2 },
                            ) { Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp)) }
                        }
                        if (due) {
                            GroupDivider(64.dp)
                            ListRow(stringResource(R.string.bk_title).replace("&amp;", "&"), subtitle = stringResource(R.string.dash_backup_due), leading = { IconTile(Icons.Filled.Backup, 34.dp, c.star) }, onClick = { vm.go(Screen.BackupRestore) })
                        }
                        if (corrupted > 0) {
                            GroupDivider(64.dp)
                            ListRow(stringResource(R.string.dash_corrupted, corrupted), leading = { IconTile(Icons.Filled.Warning, 34.dp, c.danger) })
                        }
                    }
                }
            }
        }
        if (filter == ItemFilter.Trash && shown.isNotEmpty()) {
            item { GlassButton(stringResource(R.string.trash_empty), { askEmpty = true }, Modifier.fillMaxWidth().padding(bottom = 12.dp), prominent = false, destructive = true, icon = Icons.Filled.DeleteForever) }
        }
        if (shown.isEmpty()) item { Body(stringResource(R.string.vault_empty), Modifier.padding(16.dp)) }
        itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
            val first = index == 0
            val last = index == shown.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp,
                bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp,
            )
            Column(Modifier.fillMaxWidth().clip(shape).background(c.fill)) {
                val selecting = vm.selection.isNotEmpty()
                val selected = item.id in vm.selection
                ListRow(
                    title = item.title.ifBlank { untitled },
                    subtitle = Items.subtitle(item).ifEmpty { stringResource(typeLabel(item.type)) },
                    leading = { if (selected) IconTile(Icons.Filled.Check, color = c.accent) else IconTile(iconFor(item.type)) },
                    onClick = { if (selecting) vm.toggleSelected(item.id) else vm.go(Screen.Detail(item.id)) },
                    onLongClick = { vm.toggleSelected(item.id) },
                ) {
                    if (item.favorite) Icon(Icons.Filled.Star, null, tint = c.star, modifier = Modifier.size(17.dp))
                    if (!selecting) Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                }
                if (!last) GroupDivider(inset = 68.dp)
            }
        }
    }

    // ---- views
    GlassSheet(visible = showViews, onDismiss = { showViews = false }) {
        Text(stringResource(R.string.views_title), style = Type.headline, color = c.text)
        SheetList {
            @Composable
            fun Row(label: String, icon: ImageVector, f: ItemFilter, inset: Int = 0) {
                ListRow(label, Modifier.padding(start = (inset * 18).dp), leading = { IconTile(icon, 32.dp) }, onClick = { vm.filter = f; showViews = false }) { CheckMark(vm.filter == f) }
            }
            InsetGroup {
                Row(stringResource(R.string.vault_all), Icons.Filled.Shield, ItemFilter.All); GroupDivider(62.dp)
                Row(stringResource(R.string.vault_favorites), Icons.Filled.Star, ItemFilter.Favorites); GroupDivider(62.dp)
                Row(stringResource(R.string.nav_recent_added), Icons.Filled.AddCircle, ItemFilter.RecentAdded); GroupDivider(62.dp)
                Row(stringResource(R.string.nav_recent_used), Icons.Filled.History, ItemFilter.RecentUsed)
            }
            InsetGroup {
                Row(stringResource(R.string.nav_passwords), Icons.Filled.Person, ItemFilter.Type("login")); GroupDivider(62.dp)
                Row(stringResource(R.string.nav_notes), Icons.AutoMirrored.Filled.Notes, ItemFilter.Type("note")); GroupDivider(62.dp)
                Row(stringResource(R.string.nav_other), Icons.Filled.Wifi, ItemFilter.Type("other"))
            }
            val folders = folderTree(collections)
            if (folders.isNotEmpty()) {
                SectionHeader(stringResource(R.string.nav_folders))
                InsetGroup { folders.forEachIndexed { i, (f, depth) -> Row(f.name, Icons.Filled.Folder, ItemFilter.Section(f.id), depth); if (i != folders.lastIndex) GroupDivider(62.dp) } }
            }
            val tags = remember(rev) { vm.allTags() }
            if (tags.isNotEmpty()) {
                SectionHeader(stringResource(R.string.nav_tags))
                InsetGroup { tags.forEachIndexed { i, t -> Row(t, Icons.Filled.Tag, ItemFilter.Tag(t)); if (i != tags.lastIndex) GroupDivider(62.dp) } }
            }
            InsetGroup {
                Row(stringResource(R.string.nav_uncategorized), Icons.Filled.Inbox, ItemFilter.Uncategorized); GroupDivider(62.dp)
                Row(stringResource(R.string.nav_archive), Icons.Filled.Archive, ItemFilter.Archive); GroupDivider(62.dp)
                Row(stringResource(R.string.nav_trash), Icons.Filled.Delete, ItemFilter.Trash)
            }
        }
    }
    // ---- move selected items (web: MoveDialog)
    var moveCat by remember(showMove) { mutableStateOf<String?>("keep") }
    var moveFolder by remember(showMove) { mutableStateOf<String?>("keep") }
    GlassSheet(visible = showMove, onDismiss = { showMove = false }) {
        Text(stringResource(R.string.sel_move_title, vm.selection.size), style = Type.headline, color = c.text)
        SheetList {
            SectionHeader(stringResource(R.string.field_category))
            InsetGroup {
                ListRow(stringResource(R.string.sel_keep), onClick = { moveCat = "keep" }) { CheckMark(moveCat == "keep") }
                GroupDivider()
                ListRow(stringResource(R.string.field_none), onClick = { moveCat = null }) { CheckMark(moveCat == null) }
                for (cat in categories) {
                    GroupDivider()
                    ListRow(cat.name, onClick = { moveCat = cat.id }) { CheckMark(moveCat == cat.id) }
                }
            }
            SectionHeader(stringResource(R.string.field_folder))
            InsetGroup {
                ListRow(stringResource(R.string.sel_keep), onClick = { moveFolder = "keep" }) { CheckMark(moveFolder == "keep") }
                GroupDivider()
                ListRow(stringResource(R.string.field_none), onClick = { moveFolder = null }) { CheckMark(moveFolder == null) }
                for ((f, depth) in folderTree(collections)) {
                    GroupDivider()
                    ListRow(f.name, Modifier.padding(start = (depth * 18).dp), onClick = { moveFolder = f.id }) { CheckMark(moveFolder == f.id) }
                }
            }
            GlassButton(
                stringResource(R.string.sel_move),
                { vm.moveSelected(moveCat.takeIf { it != "keep" }, moveCat == "keep", moveFolder.takeIf { it != "keep" }, moveFolder == "keep"); showMove = false },
                Modifier.fillMaxWidth(),
            )
        }
    }
    // ---- sort
    GlassSheet(visible = showSort, onDismiss = { showSort = false }) {
        Text(stringResource(R.string.sort_title), style = Type.headline, color = c.text)
        InsetGroup {
            val options = listOf(SortBy.TITLE to R.string.sort_az, SortBy.UPDATED to R.string.sort_updated, SortBy.CREATED to R.string.sort_created, SortBy.USED to R.string.sort_used)
            options.forEachIndexed { i, (by, label) ->
                ListRow(stringResource(label), onClick = { vm.sortBy = by; showSort = false }) { CheckMark(vm.sortBy == by) }
                if (i != options.lastIndex) GroupDivider()
            }
        }
    }
    GlassAlert(
        visible = askEmpty,
        title = stringResource(R.string.purge_title),
        message = stringResource(R.string.purge_message),
        confirm = stringResource(R.string.action_delete_forever),
        onConfirm = { askEmpty = false; vm.purge(vm.trashedIds()) },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { askEmpty = false },
        destructive = true,
    )
}

@Composable
private fun IconChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalGlassColors.current
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier
            .liquidPress(source, 0.92f)
            .clip(Capsule)
            .background(if (selected) c.accent else c.fill)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) c.onAccent else c.text, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (selected) c.onAccent else c.text, maxLines = 1)
    }
}

// ------------------------------------------------------------------ item detail

private class FieldSpec(val label: String, val value: String, val secret: Boolean = false, val mono: Boolean = false, val multiline: Boolean = false, val url: String? = null)

@Composable
internal fun DetailScreen(vm: VaultViewModel, id: String) {
    val item = remember(vm.revision, id) { vm.item(id) }
    val back = stringResource(R.string.back)
    BackHandler { vm.go(Screen.Vault) }
    if (item == null) {
        LaunchedEffect(Unit) { vm.go(Screen.Vault) }
        return
    }
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val host = LocalOverlayHost.current
    val copied = stringResource(R.string.detail_copied)
    var unlockedTick by remember { mutableStateOf(0) }
    val locked = item.reprompt && id !in vm.revealed && unlockedTick >= 0
    var askPassword by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var askPurge by remember { mutableStateOf(false) }
    var pw by rememberSaveable { mutableStateOf("") }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    val fields = buildList {
        fun add(label: Int, value: String?, secret: Boolean = false, mono: Boolean = false, multiline: Boolean = false, url: String? = null) {
            if (!value.isNullOrBlank()) add(FieldSpec(context.getString(label), value, secret, mono, multiline, url))
        }
        add(R.string.field_username, item.username)
        add(R.string.field_email, item.email)
        add(R.string.field_password, item.password, secret = true)
        add(R.string.field_url, item.url, url = Items.safeHttpUrl(item.url))
        add(R.string.field_ssid, item.ssid)
        add(R.string.field_wifi_security, item.wifiSecurity)
        add(R.string.field_product, item.product)
        add(R.string.field_license_key, item.licenseKey, secret = true)
        add(R.string.field_licensee, item.licensee)
        add(R.string.field_version, item.version)
        add(R.string.field_purchase_date, item.purchaseDate)
        add(R.string.field_service, item.service)
        add(R.string.field_codes, item.codes, secret = true, mono = true, multiline = true)
        add(R.string.field_recovery_email, item.recoveryEmail)
        add(R.string.field_recovery_info, item.recoveryInfo, secret = true)
        for (f in item.customFields) if (f.value.isNotBlank()) add(FieldSpec(f.label.ifBlank { context.getString(R.string.field_custom_value) }, f.value, secret = f.hidden))
    }
    val notes = buildList {
        if (!item.notes.isNullOrBlank()) add(FieldSpec(context.getString(R.string.field_notes), item.notes!!, multiline = true))
        if (!item.importantNotes.isNullOrBlank()) add(FieldSpec(context.getString(R.string.field_important_notes), item.importantNotes!!, multiline = true))
        if (item.tags.isNotEmpty()) add(FieldSpec(context.getString(R.string.field_tags), item.tags.joinToString(", ")))
    }
    val history = item.passwordHistory.orEmpty()
    val onCopy = { f: FieldSpec ->
        copySensitive(context, f.label, f.value, vm.prefs.clipboardClearSeconds)
        vm.markUsed(id)
        host.toast(copied)
    }
    val onOpen = { url: String ->
        vm.markUsed(id)
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }
    val trashed = item.trashedAt != null

    GlassScaffold(
        topBar = {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, back, { vm.go(Screen.Vault) }, Modifier.align(Alignment.CenterStart))
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!trashed) {
                    GlassIconButton(
                        if (item.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        stringResource(if (item.favorite) R.string.action_unfavorite else R.string.action_favorite),
                        { vm.toggleFavorite(id) },
                        tint = if (item.favorite) c.star else c.text,
                    )
                    GlassIconButton(Icons.Filled.Edit, stringResource(R.string.action_edit), { if (locked) askPassword = true else vm.startEdit(id) })
                }
                GlassIconButton(Icons.Filled.MoreHoriz, stringResource(R.string.action_more), { more = true })
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                IconTile(iconFor(item.type), size = 58.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(item.title.ifBlank { stringResource(R.string.untitled) }, style = Type.title, color = c.text)
                    val status = when {
                        trashed -> stringResource(R.string.item_in_trash)
                        item.archivedAt != null -> stringResource(R.string.item_archived)
                        else -> stringResource(typeLabel(item.type))
                    }
                    Text(status, style = Type.footnote, color = if (trashed) c.danger else c.text2)
                }
            }
            if (locked) {
                InsetGroup {
                    ListRow(stringResource(R.string.detail_reprompt_title), subtitle = stringResource(R.string.detail_reprompt_body), leading = { IconTile(Icons.Filled.Lock) })
                }
                GlassButton(stringResource(R.string.detail_unlock), { askPassword = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Lock)
            }
            if (!locked && item.type == ItemType.LOGIN && !item.password.isNullOrEmpty() && remember(item.password) { com.damfizz.myvault.core.Strength.estimate(item.password!!).score < 3 }) {
                Footnote(stringResource(R.string.dash_weak), Modifier.padding(horizontal = 16.dp), color = c.star)
            }
            if (fields.isNotEmpty()) InsetGroup { fields.forEachIndexed { i, f -> FieldRow(f, locked && f.secret, onCopy, onOpen, vm.prefs.revealSeconds); if (i != fields.lastIndex) GroupDivider() } }
            if (notes.isNotEmpty()) InsetGroup { notes.forEachIndexed { i, f -> FieldRow(f, false, onCopy, onOpen); if (i != notes.lastIndex) GroupDivider() } }
            if (history.isNotEmpty() && !locked) {
                SectionHeader(stringResource(R.string.detail_history) + " (${history.size})")
                InsetGroup {
                    history.forEachIndexed { i, h ->
                        FieldRow(FieldSpec(stringResource(R.string.detail_replaced, date.format(Date(h.changedAt))), h.password, secret = true), false, onCopy, onOpen)
                        if (i != history.lastIndex) GroupDivider()
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Footnote(stringResource(R.string.meta_created) + ": " + date.format(Date(item.createdAt)))
                Footnote(stringResource(R.string.meta_modified) + ": " + date.format(Date(item.updatedAt)))
                item.passwordChangedAt?.let { Footnote(stringResource(R.string.meta_password_changed) + ": " + date.format(Date(it))) }
            }
        }
    }

    GlassSheet(visible = more, onDismiss = { more = false }) {
        InsetGroup {
            @Composable
            fun Action(label: Int, icon: ImageVector, danger: Boolean = false, run: () -> Unit) {
                ListRow(stringResource(label), leading = { IconTile(icon, 32.dp, if (danger) c.danger else c.accent) }, titleColor = if (danger) c.danger else c.text, onClick = { more = false; run() })
            }
            if (trashed) {
                Action(R.string.action_restore, Icons.Filled.RestoreFromTrash) { vm.restore(listOf(id)) }
                GroupDivider(62.dp)
                Action(R.string.action_delete_forever, Icons.Filled.DeleteForever, danger = true) { askPurge = true }
            } else {
                Action(R.string.action_duplicate, Icons.Filled.ContentCopy) { vm.duplicate(id) }
                GroupDivider(62.dp)
                if (item.archivedAt == null) {
                    Action(R.string.action_archive, Icons.Filled.Archive) { vm.archive(listOf(id), true); vm.go(Screen.Vault) }
                } else {
                    Action(R.string.action_unarchive, Icons.Filled.Unarchive) { vm.archive(listOf(id), false) }
                }
                GroupDivider(62.dp)
                Action(R.string.action_trash, Icons.Filled.Delete, danger = true) { vm.trash(listOf(id)); vm.go(Screen.Vault) }
            }
        }
    }
    GlassAlert(
        visible = askPurge,
        title = stringResource(R.string.purge_title),
        message = stringResource(R.string.purge_message),
        confirm = stringResource(R.string.action_delete_forever),
        onConfirm = { askPurge = false; vm.purge(listOf(id)); vm.go(Screen.Vault) },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { askPurge = false },
        destructive = true,
    )
    GlassSheet(visible = askPassword, onDismiss = { askPassword = false; pw = ""; vm.clearError() }) {
        fun submit() {
            if (pw.isNotEmpty()) {
                vm.reprompt(id, pw) { unlockedTick++; askPassword = false }
                pw = ""
            }
        }
        Text(stringResource(R.string.detail_reprompt_title), style = Type.headline, color = c.text)
        Body(stringResource(R.string.detail_reprompt_body))
        GlassTextField(pw, { pw = it }, stringResource(R.string.master_password), secret = true, onDone = ::submit)
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.detail_unlock), ::submit, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
    }
}

@Composable
private fun FieldRow(f: FieldSpec, locked: Boolean, onCopy: (FieldSpec) -> Unit, onOpen: (String) -> Unit, revealSeconds: Int = 20) {
    val c = LocalGlassColors.current
    var shown by remember { mutableStateOf(false) }
    // Revealed secrets hide themselves again (web: revealSeconds; 0 = until hidden manually).
    LaunchedEffect(shown) {
        if (shown && revealSeconds > 0) {
            kotlinx.coroutines.delay(revealSeconds * 1000L)
            shown = false
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.label, style = Type.footnote, color = c.text3)
            Text(
                if (f.secret && (locked || !shown)) "••••••••••" else f.value,
                style = Type.body.copy(fontFamily = if (f.secret || f.mono) FontFamily.Monospace else FontFamily.Default),
                color = c.text,
                maxLines = if (f.multiline) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!locked) {
            if (f.secret) {
                RowIconButton(
                    if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    stringResource(if (shown) R.string.detail_hide else R.string.detail_reveal) + " " + f.label,
                    { shown = !shown },
                )
            }
            if (f.url != null) RowIconButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_website), { onOpen(f.url) })
            RowIconButton(Icons.Filled.ContentCopy, stringResource(R.string.detail_copy) + " " + f.label, { onCopy(f) })
        } else {
            Icon(Icons.Filled.Lock, null, tint = c.text3, modifier = Modifier.padding(end = 12.dp).size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ editor

@Composable
private fun Labeled(label: String, error: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Footnote(label, Modifier.padding(start = 14.dp), color = LocalGlassColors.current.text2)
        content()
        if (error != null) Footnote(error, Modifier.padding(start = 14.dp), color = LocalGlassColors.current.danger)
    }
}

@Composable
internal fun EditorScreen(vm: VaultViewModel) {
    val item = vm.draft
    if (item == null) {
        LaunchedEffect(Unit) { vm.go(Screen.Vault) }
        return
    }
    val c = LocalGlassColors.current
    val collections = remember(vm.revision) { vm.collections() }
    val categories = remember(collections) { collections.filter { it.kind == "category" } }
    val folders = remember(collections) { folderTree(collections) }
    var errors by remember { mutableStateOf(emptySet<String>()) }
    var askDiscard by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    var tagsText by rememberSaveable { mutableStateOf(item.tags.joinToString(", ")) }
    fun set(next: VaultItem) { vm.draft = next }
    fun text(value: String): String? = value.ifEmpty { null }
    fun tryClose() { if (vm.draft != vm.draftInitial || tagsText != vm.draftInitial?.tags?.joinToString(", ")) askDiscard = true else vm.closeEditor() }
    fun save() {
        val tags = tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        vm.draft = item.copy(tags = tags, customFields = item.customFields.filter { it.label.isNotBlank() || it.value.isNotBlank() })
        errors = vm.saveDraft()
    }
    BackHandler { tryClose() }

    GlassScaffold(
        topBar = {
            GlassIconButton(Icons.Filled.Close, stringResource(R.string.cancel), ::tryClose, Modifier.align(Alignment.CenterStart))
            GlassButton(stringResource(R.string.action_save), ::save, Modifier.align(Alignment.CenterEnd), icon = Icons.Filled.Check)
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeTitle(
                if (vm.draftIsNew) stringResource(R.string.editor_new, stringResource(typeLabel(item.type))) else stringResource(R.string.editor_edit),
                Modifier.padding(start = 6.dp),
            )
            Labeled(stringResource(R.string.field_title), if ("title" in errors) stringResource(R.string.err_title) else null) {
                GlassTextField(item.title, { set(item.copy(title = it)) }, stringResource(R.string.field_title))
            }
            when (item.type) {
                ItemType.LOGIN -> {
                    Labeled(stringResource(R.string.field_url), if ("url" in errors) stringResource(R.string.err_url) else null) {
                        GlassTextField(item.url.orEmpty(), { set(item.copy(url = text(it))) }, "example.com", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                    }
                    Labeled(stringResource(R.string.field_username)) { GlassTextField(item.username.orEmpty(), { set(item.copy(username = text(it))) }, stringResource(R.string.field_username)) }
                    Labeled(stringResource(R.string.field_email)) { GlassTextField(item.email.orEmpty(), { set(item.copy(email = text(it))) }, stringResource(R.string.field_email), keyboard = androidx.compose.ui.text.input.KeyboardType.Email) }
                    Labeled(stringResource(R.string.field_password)) { GlassTextField(item.password.orEmpty(), { set(item.copy(password = text(it))) }, stringResource(R.string.field_password), secret = true) }
                }
                ItemType.WIFI -> {
                    Labeled(stringResource(R.string.field_ssid)) { GlassTextField(item.ssid.orEmpty(), { set(item.copy(ssid = text(it))) }, stringResource(R.string.field_ssid)) }
                    Labeled(stringResource(R.string.field_password)) { GlassTextField(item.password.orEmpty(), { set(item.copy(password = text(it))) }, stringResource(R.string.field_password), secret = true) }
                    Labeled(stringResource(R.string.field_wifi_security)) { GlassTextField(item.wifiSecurity.orEmpty(), { set(item.copy(wifiSecurity = text(it))) }, "WPA2 / WPA3") }
                }
                ItemType.RECOVERY_CODES -> {
                    Labeled(stringResource(R.string.field_service)) { GlassTextField(item.service.orEmpty(), { set(item.copy(service = text(it))) }, stringResource(R.string.field_service)) }
                    Labeled(stringResource(R.string.field_codes)) { GlassTextField(item.codes.orEmpty(), { set(item.copy(codes = text(it))) }, stringResource(R.string.field_codes_help), mono = true, multiline = true) }
                }
                ItemType.LICENSE -> {
                    Labeled(stringResource(R.string.field_product)) { GlassTextField(item.product.orEmpty(), { set(item.copy(product = text(it))) }, stringResource(R.string.field_product)) }
                    Labeled(stringResource(R.string.field_license_key)) { GlassTextField(item.licenseKey.orEmpty(), { set(item.copy(licenseKey = text(it))) }, stringResource(R.string.field_license_key), secret = true) }
                    Labeled(stringResource(R.string.field_licensee)) { GlassTextField(item.licensee.orEmpty(), { set(item.copy(licensee = text(it))) }, stringResource(R.string.field_licensee)) }
                    Labeled(stringResource(R.string.field_version)) { GlassTextField(item.version.orEmpty(), { set(item.copy(version = text(it))) }, stringResource(R.string.field_version)) }
                    Labeled(stringResource(R.string.field_purchase_date)) { GlassTextField(item.purchaseDate.orEmpty(), { set(item.copy(purchaseDate = text(it))) }, "2026-01-31") }
                }
            }
            Labeled(stringResource(if (item.type == ItemType.NOTE) R.string.field_note else R.string.field_notes)) {
                GlassTextField(item.notes.orEmpty(), { set(item.copy(notes = text(it))) }, stringResource(R.string.field_notes), multiline = true)
            }

            SectionHeader(stringResource(R.string.field_organize))
            InsetGroup {
                ListRow(stringResource(R.string.field_category), onClick = { pickCategory = true }) {
                    Text(categories.find { it.id == item.categoryId }?.name ?: stringResource(R.string.field_none), style = Type.callout, color = c.text2)
                    Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                }
                GroupDivider()
                ListRow(stringResource(R.string.field_folder), onClick = { pickFolder = true }) {
                    Text(folders.find { it.first.id == item.sectionId }?.first?.name ?: stringResource(R.string.field_none), style = Type.callout, color = c.text2)
                    Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                }
                GroupDivider()
                ToggleRow(stringResource(R.string.field_favorite), item.favorite, { set(item.copy(favorite = it)) })
                GroupDivider()
                ToggleRow(stringResource(R.string.field_reprompt), item.reprompt, { set(item.copy(reprompt = it)) }, subtitle = stringResource(R.string.field_reprompt_help))
            }
            Labeled(stringResource(R.string.field_tags)) { GlassTextField(tagsText, { tagsText = it }, stringResource(R.string.field_tags_hint)) }

            if (item.type == ItemType.LOGIN) {
                SectionHeader(stringResource(R.string.field_more))
                Labeled(stringResource(R.string.field_recovery_email)) { GlassTextField(item.recoveryEmail.orEmpty(), { set(item.copy(recoveryEmail = text(it))) }, stringResource(R.string.field_recovery_email), keyboard = androidx.compose.ui.text.input.KeyboardType.Email) }
                Labeled(stringResource(R.string.field_recovery_info)) { GlassTextField(item.recoveryInfo.orEmpty(), { set(item.copy(recoveryInfo = text(it))) }, stringResource(R.string.field_recovery_info), multiline = true) }
                Labeled(stringResource(R.string.field_important_notes)) { GlassTextField(item.importantNotes.orEmpty(), { set(item.copy(importantNotes = text(it))) }, stringResource(R.string.field_important_notes), multiline = true) }
            }

            SectionHeader(stringResource(R.string.field_custom_fields))
            for (f in item.customFields) {
                key(f.id) {
                    fun update(n: CustomField) = set(item.copy(customFields = item.customFields.map { if (it.id == f.id) n else it }))
                    InsetGroup {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassTextField(f.label, { update(f.copy(label = it)) }, stringResource(R.string.field_custom_label))
                            GlassTextField(f.value, { update(f.copy(value = it)) }, stringResource(R.string.field_custom_value), secret = f.hidden)
                        }
                        ToggleRow(stringResource(R.string.field_custom_hidden), f.hidden, { update(f.copy(hidden = it)) })
                        GroupDivider()
                        ListRow(stringResource(R.string.action_remove), titleColor = c.danger, onClick = { set(item.copy(customFields = item.customFields.filter { it.id != f.id })) })
                    }
                }
            }
            GlassButton(
                stringResource(R.string.field_add_custom),
                { set(item.copy(customFields = item.customFields + CustomField(Encoding.randomId(), "", "", false))) },
                Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.Add,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    GlassSheet(visible = pickCategory, onDismiss = { pickCategory = false }) {
        Text(stringResource(R.string.field_category), style = Type.headline, color = c.text)
        SheetList {
            InsetGroup {
                ListRow(stringResource(R.string.field_none), onClick = { set(item.copy(categoryId = null)); pickCategory = false }) { CheckMark(item.categoryId == null) }
                for (cat in categories) {
                    GroupDivider()
                    ListRow(cat.name, onClick = { set(item.copy(categoryId = cat.id)); pickCategory = false }) { CheckMark(item.categoryId == cat.id) }
                }
            }
        }
    }
    GlassSheet(visible = pickFolder, onDismiss = { pickFolder = false }) {
        Text(stringResource(R.string.field_folder), style = Type.headline, color = c.text)
        SheetList {
            InsetGroup {
                ListRow(stringResource(R.string.field_none), onClick = { set(item.copy(sectionId = null)); pickFolder = false }) { CheckMark(item.sectionId == null) }
                for ((f, depth) in folders) {
                    GroupDivider()
                    ListRow(f.name, Modifier.padding(start = (depth * 18).dp), onClick = { set(item.copy(sectionId = f.id)); pickFolder = false }) { CheckMark(item.sectionId == f.id) }
                }
            }
        }
    }
    GlassAlert(
        visible = askDiscard,
        title = stringResource(R.string.discard_title),
        message = stringResource(R.string.discard_message),
        confirm = stringResource(R.string.discard_confirm),
        onConfirm = { askDiscard = false; vm.closeEditor() },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { askDiscard = false },
        destructive = true,
    )
}
