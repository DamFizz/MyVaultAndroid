package com.damfizz.myvault.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.damfizz.myvault.BuildConfigCompat
import com.damfizz.myvault.R
import com.damfizz.myvault.core.ItemType
import com.damfizz.myvault.core.VaultItem
import com.damfizz.myvault.security.BiometricKey
import com.damfizz.myvault.ui.glass.Body
import com.damfizz.myvault.ui.glass.BusyOverlay
import com.damfizz.myvault.ui.glass.Capsule
import com.damfizz.myvault.ui.glass.Chip
import com.damfizz.myvault.ui.glass.ErrorText
import com.damfizz.myvault.ui.glass.Footnote
import com.damfizz.myvault.ui.glass.GlassAlert
import com.damfizz.myvault.ui.glass.GlassButton
import com.damfizz.myvault.ui.glass.GlassIconButton
import com.damfizz.myvault.ui.glass.GlassScaffold
import com.damfizz.myvault.ui.glass.GlassSheet
import com.damfizz.myvault.ui.glass.GlassSurface
import com.damfizz.myvault.ui.glass.GlassTabBar
import com.damfizz.myvault.ui.glass.GlassTextField
import com.damfizz.myvault.ui.glass.GroupDivider
import com.damfizz.myvault.ui.glass.IconTile
import com.damfizz.myvault.ui.glass.InsetGroup
import com.damfizz.myvault.ui.glass.LargeTitle
import com.damfizz.myvault.ui.glass.LiquidGlassRoot
import com.damfizz.myvault.ui.glass.ListRow
import com.damfizz.myvault.ui.glass.LocalGlassColors
import com.damfizz.myvault.ui.glass.LocalOverlayHost
import com.damfizz.myvault.ui.glass.RowIconButton
import com.damfizz.myvault.ui.glass.SectionHeader
import com.damfizz.myvault.ui.glass.TabItem
import com.damfizz.myvault.ui.glass.TextLink
import com.damfizz.myvault.ui.glass.ToggleRow
import com.damfizz.myvault.ui.glass.Type
import java.text.DateFormat
import java.util.Date
import javax.crypto.Cipher

@Composable
fun AppRoot(vm: VaultViewModel) {
    LiquidGlassRoot {
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = { (fadeIn(tween(240)) + slideInVertically(tween(320)) { it / 28 }) togetherWith fadeOut(tween(140)) },
            contentKey = { it::class },
            label = "screen",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            when (s) {
                Screen.Welcome -> WelcomeScreen(vm)
                Screen.Create -> CreateScreen(vm)
                Screen.Import -> ImportScreen(vm)
                Screen.Unlock -> UnlockScreen(vm)
                is Screen.SecondFactor -> SecondFactorScreen(vm, s)
                Screen.Vault -> MainScreen(vm)
                is Screen.Detail -> DetailScreen(vm, s.id)
            }
        }
        BusyOverlay(vm.busy, stringResource(R.string.busy_kdf))
    }
}

// ------------------------------------------------------------------ shared pieces

/** Sign-in style page: drifting wallpaper, optional glass back button, centred column. */
@Composable
private fun AuthPage(onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    if (onBack != null) BackHandler(onBack = onBack)
    val back = stringResource(R.string.back)
    GlassScaffold(
        animatedWallpaper = true,
        topBar = onBack?.let { ob -> { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, back, ob, Modifier.align(Alignment.CenterStart)) } },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun Hero(icon: ImageVector) {
    GlassSurface(Modifier.size(88.dp), shape = Capsule, refraction = 20.dp, elevation = 18.dp, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = LocalGlassColors.current.accent, modifier = Modifier.size(40.dp))
    }
}

// ------------------------------------------------------------------ welcome / create / import

@Composable
private fun WelcomeScreen(vm: VaultViewModel) = AuthPage {
    Spacer(Modifier.height(72.dp))
    Hero(Icons.Filled.Shield)
    Spacer(Modifier.height(6.dp))
    LargeTitle(stringResource(R.string.welcome_title))
    Body(stringResource(R.string.welcome_body))
    Spacer(Modifier.height(14.dp))
    GlassButton(stringResource(R.string.welcome_import), { vm.go(Screen.Import) }, Modifier.fillMaxWidth())
    GlassButton(stringResource(R.string.welcome_create), { vm.go(Screen.Create) }, Modifier.fillMaxWidth(), prominent = false)
}

@Composable
private fun CreateScreen(vm: VaultViewModel) = AuthPage(onBack = { vm.go(Screen.Welcome) }) {
    var pw by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    LargeTitle(stringResource(R.string.create_title))
    Body(stringResource(R.string.create_hint))
    GlassTextField(pw, { pw = it }, stringResource(R.string.create_password), secret = true)
    GlassTextField(confirm, { confirm = it }, stringResource(R.string.create_confirm), secret = true, onDone = { vm.create(pw, confirm) })
    ErrorText(vm.error)
    GlassButton(stringResource(R.string.create_button), { vm.create(pw, confirm) }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

@Composable
private fun ImportScreen(vm: VaultViewModel) = AuthPage(onBack = { vm.cancelImport(); vm.go(Screen.Welcome) }) {
    val context = LocalContext.current
    var fileText by remember { mutableStateOf<String?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pw by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileName = displayName(context, uri)
            fileText = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }
    }
    LargeTitle(stringResource(R.string.import_title))
    Body(stringResource(R.string.import_body))
    GlassButton(
        fileName?.let { stringResource(R.string.import_picked, it) } ?: stringResource(R.string.import_pick),
        { picker.launch(arrayOf("*/*")) },
        Modifier.fillMaxWidth(),
        prominent = fileText == null,
    )
    if (fileText != null) {
        GlassTextField(pw, { pw = it }, stringResource(R.string.import_backup_password), secret = true, onDone = { vm.openBackupFile(fileText!!, pw) })
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.import_open), { vm.openBackupFile(fileText!!, pw) }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
    }
    val p = vm.pendingImport
    GlassAlert(
        visible = p != null,
        title = stringResource(R.string.import_confirm_title),
        message = p?.let { stringResource(R.string.import_confirm_body, DateFormat.getDateTimeInstance().format(Date(it.opened.createdAt)), it.opened.records.size) }.orEmpty(),
        confirm = stringResource(R.string.import_replace),
        onConfirm = vm::confirmImport,
        dismiss = stringResource(R.string.cancel),
        onDismiss = vm::cancelImport,
    )
}

// ------------------------------------------------------------------ unlock / two-step

private fun showBiometricPrompt(activity: FragmentActivity, title: String, negative: String, cipher: Cipher, onOk: (Cipher) -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            result.cryptoObject?.cipher?.let(onOk)
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setNegativeButtonText(negative)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

@Composable
private fun UnlockScreen(vm: VaultViewModel) = AuthPage {
    val activity = LocalContext.current as FragmentActivity
    var pw by rememberSaveable { mutableStateOf("") }
    var sk by rememberSaveable { mutableStateOf("") }
    var rememberSk by rememberSaveable { mutableStateOf(true) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var rk by rememberSaveable { mutableStateOf("") }
    var newPw by rememberSaveable { mutableStateOf("") }
    val bioTitle = stringResource(R.string.biometric_unlock_title)
    val cancel = stringResource(R.string.cancel)
    val bio = remember(vm.revision) { vm.biometricEnabled() }
    fun startBiometric() {
        vm.biometricUnlockCipher()?.let { c -> showBiometricPrompt(activity, bioTitle, cancel, c) { vm.unlockWithBiometric(it) } }
    }
    fun submit() {
        if (pw.isNotEmpty()) {
            vm.unlock(pw, sk, rememberSk)
            pw = ""
        }
    }
    LaunchedEffect(Unit) { if (bio) startBiometric() }

    Spacer(Modifier.height(72.dp))
    Hero(Icons.Filled.Lock)
    Spacer(Modifier.height(6.dp))
    if (!recovery) {
        LargeTitle(stringResource(R.string.unlock_title))
        GlassTextField(pw, { pw = it }, stringResource(R.string.master_password), secret = true, onDone = ::submit)
        if (vm.needsSecretKey) {
            GlassTextField(sk, { sk = it.uppercase() }, stringResource(R.string.secret_key), mono = true)
            Footnote(stringResource(R.string.secret_key_hint), Modifier.padding(horizontal = 16.dp))
            InsetGroup { ToggleRow(stringResource(R.string.remember_secret_key), rememberSk, { rememberSk = it }) }
        }
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.unlock_button), ::submit, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
        if (bio) {
            GlassButton(stringResource(R.string.unlock_biometric), ::startBiometric, Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.Fingerprint)
        }
        TextLink(stringResource(R.string.forgot_password), { recovery = true; vm.clearError() })
    } else {
        BackHandler { recovery = false }
        LargeTitle(stringResource(R.string.recovery_title))
        GlassTextField(rk, { rk = it.uppercase() }, stringResource(R.string.recovery_key), mono = true)
        GlassTextField(newPw, { newPw = it }, stringResource(R.string.new_master_password), secret = true)
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.recovery_button), { vm.unlockWithRecoveryKey(rk, newPw) }, Modifier.fillMaxWidth(), enabled = rk.isNotBlank() && newPw.isNotEmpty())
        TextLink(stringResource(R.string.back), { recovery = false; vm.clearError() })
    }
}

@Composable
private fun SecondFactorScreen(vm: VaultViewModel, s: Screen.SecondFactor) = AuthPage(onBack = { vm.lock() }) {
    var code by rememberSaveable { mutableStateOf("") }
    var trust by rememberSaveable { mutableStateOf(false) }
    var useRecovery by rememberSaveable { mutableStateOf(false) }
    var rc by rememberSaveable { mutableStateOf("") }
    LargeTitle(stringResource(R.string.twofa_title))
    if (!useRecovery) {
        Body(stringResource(R.string.twofa_body))
        GlassTextField(
            code, { v -> code = v.filter { it.isDigit() }.take(6) }, stringResource(R.string.twofa_code),
            mono = true, keyboard = KeyboardType.NumberPassword, onDone = { if (code.length == 6) vm.verifyTotp(s.challenge, code, trust) },
        )
        InsetGroup { ToggleRow(stringResource(R.string.twofa_trust), trust, { trust = it }) }
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.twofa_verify), { vm.verifyTotp(s.challenge, code, trust) }, Modifier.fillMaxWidth(), enabled = code.length == 6)
        TextLink(stringResource(R.string.twofa_use_recovery), { useRecovery = true; vm.clearError() })
    } else {
        GlassTextField(rc, { rc = it.uppercase() }, stringResource(R.string.twofa_recovery_code), mono = true)
        Footnote(stringResource(R.string.twofa_codes_left, s.challenge.recoveryCodesLeft), Modifier.padding(horizontal = 16.dp))
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.twofa_recovery_button), { vm.useRecoveryCode(s.challenge, rc) }, Modifier.fillMaxWidth(), enabled = rc.isNotBlank())
        TextLink(stringResource(R.string.back), { useRecovery = false; vm.clearError() })
    }
}

// ------------------------------------------------------------------ main (tabs)

@Composable
private fun MainScreen(vm: VaultViewModel) {
    val tabs = listOf(
        TabItem(Icons.Filled.Shield, stringResource(R.string.tab_vault)),
        TabItem(Icons.Filled.Star, stringResource(R.string.vault_favorites)),
        TabItem(Icons.Filled.Settings, stringResource(R.string.settings)),
    )
    val lock = stringResource(R.string.lock)
    GlassScaffold(
        topBar = { GlassIconButton(Icons.Filled.Lock, lock, vm::lock, Modifier.align(Alignment.CenterEnd)) },
        bottomBar = { GlassTabBar(tabs, vm.tab, { vm.tab = it }, Modifier.fillMaxWidth()) },
    ) { pad ->
        AnimatedContent(
            vm.tab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "tab",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            when (tab) {
                0 -> VaultList(vm, pad, favouritesOnly = false)
                1 -> VaultList(vm, pad, favouritesOnly = true)
                else -> SettingsTab(vm, pad)
            }
        }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    ItemType.WIFI -> Icons.Filled.Wifi
    ItemType.NOTE -> Icons.AutoMirrored.Filled.Notes
    ItemType.LICENSE -> Icons.Filled.VpnKey
    ItemType.RECOVERY_CODES -> Icons.Filled.Key
    else -> Icons.Filled.Person
}

private fun subtitle(i: VaultItem): String = listOfNotNull(i.username, i.email, i.ssid, i.product, i.service, i.url)
    .firstOrNull { it.isNotBlank() }.orEmpty()

@Composable
private fun VaultList(vm: VaultViewModel, pad: PaddingValues, favouritesOnly: Boolean) {
    val c = LocalGlassColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val rev = vm.revision
    val all = remember(rev) { vm.items().filter { it.archivedAt == null } }
    val categories = remember(rev) { vm.collections().filter { it.kind == "category" } }
    val shown = remember(rev, query, category, favouritesOnly) {
        val q = query.trim().lowercase()
        all.filter { i ->
            (q.isEmpty() || listOfNotNull(i.title, i.username, i.email, i.url, i.ssid, i.product).any { it.lowercase().contains(q) }) &&
                (!favouritesOnly || i.favorite) &&
                (category == null || i.categoryId == category)
        }.sortedWith(compareByDescending<VaultItem> { it.favorite }.thenBy { it.title.lowercase() })
    }
    val untitled = stringResource(R.string.untitled)
    LazyColumn(contentPadding = pad, modifier = Modifier.fillMaxSize()) {
        item { LargeTitle(if (favouritesOnly) stringResource(R.string.vault_favorites) else "MyVault", Modifier.padding(start = 6.dp, bottom = 12.dp)) }
        item { GlassTextField(query, { query = it }, stringResource(R.string.vault_search), leading = Icons.Filled.Search) }
        if (!favouritesOnly) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                    item { Chip(stringResource(R.string.vault_all), category == null) { category = null } }
                    items(categories, key = { it.id }) { cat -> Chip(cat.name, category == cat.id) { category = cat.id } }
                }
            }
        } else {
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (shown.isEmpty()) {
            item { Body(stringResource(R.string.vault_empty), Modifier.padding(16.dp)) }
        }
        itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
            val first = index == 0
            val last = index == shown.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp,
                bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp,
            )
            Column(Modifier.fillMaxWidth().clip(shape).background(c.fill)) {
                ListRow(
                    title = item.title.ifBlank { untitled },
                    subtitle = subtitle(item),
                    leading = { IconTile(iconFor(item.type)) },
                    onClick = { vm.go(Screen.Detail(item.id)) },
                ) {
                    if (item.favorite) Icon(Icons.Filled.Star, null, tint = c.star, modifier = Modifier.size(17.dp))
                    Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                }
                if (!last) GroupDivider(inset = 68.dp)
            }
        }
    }
}

// ------------------------------------------------------------------ item detail

private fun copySensitive(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) else putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (current == value) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, 30_000)
}

private class FieldSpec(val label: String, val value: String, val secret: Boolean = false, val mono: Boolean = false, val multiline: Boolean = false)

@Composable
private fun DetailScreen(vm: VaultViewModel, id: String) {
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
    var pw by rememberSaveable { mutableStateOf("") }

    val fields = buildList {
        fun add(label: Int, value: String?, secret: Boolean = false, mono: Boolean = false, multiline: Boolean = false) {
            if (!value.isNullOrBlank()) add(FieldSpec(context.getString(label), value, secret, mono, multiline))
        }
        add(R.string.field_username, item.username)
        add(R.string.field_email, item.email)
        add(R.string.field_password, item.password, secret = true)
        add(R.string.field_url, item.url)
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
        for (f in item.customFields) if (f.value.isNotBlank()) add(FieldSpec(f.label, f.value, secret = f.hidden))
    }
    val notes = buildList {
        if (!item.notes.isNullOrBlank()) add(FieldSpec(context.getString(R.string.field_notes), item.notes!!, multiline = true))
        if (!item.importantNotes.isNullOrBlank()) add(FieldSpec(context.getString(R.string.field_important_notes), item.importantNotes!!, multiline = true))
        if (item.tags.isNotEmpty()) add(FieldSpec(context.getString(R.string.field_tags), item.tags.joinToString(", ")))
    }
    val onCopy = { f: FieldSpec ->
        copySensitive(context, f.label, f.value)
        host.toast(copied)
    }

    GlassScaffold(topBar = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, back, { vm.go(Screen.Vault) }, Modifier.align(Alignment.CenterStart)) }) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                IconTile(iconFor(item.type), size = 58.dp)
                Spacer(Modifier.width(16.dp))
                Text(item.title.ifBlank { stringResource(R.string.untitled) }, style = Type.title, color = c.text)
            }
            if (locked) {
                InsetGroup {
                    ListRow(
                        stringResource(R.string.detail_reprompt_title),
                        subtitle = stringResource(R.string.detail_reprompt_body),
                        leading = { IconTile(Icons.Filled.Lock) },
                    )
                }
                GlassButton(stringResource(R.string.detail_unlock), { askPassword = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Lock)
            }
            if (fields.isNotEmpty()) InsetGroup { fields.forEachIndexed { i, f -> FieldRow(f, locked && f.secret, onCopy); if (i != fields.lastIndex) GroupDivider() } }
            if (notes.isNotEmpty()) InsetGroup { notes.forEachIndexed { i, f -> FieldRow(f, false, onCopy); if (i != notes.lastIndex) GroupDivider() } }
        }
    }

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
private fun FieldRow(f: FieldSpec, locked: Boolean, onCopy: (FieldSpec) -> Unit) {
    val c = LocalGlassColors.current
    var shown by remember { mutableStateOf(false) }
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
            RowIconButton(Icons.Filled.ContentCopy, stringResource(R.string.detail_copy) + " " + f.label, { onCopy(f) })
        } else {
            Icon(Icons.Filled.Lock, null, tint = c.text3, modifier = Modifier.padding(end = 12.dp).size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ settings tab

@Composable
private fun SettingsTab(vm: VaultViewModel, pad: PaddingValues) {
    val c = LocalGlassColors.current
    val activity = LocalContext.current as FragmentActivity
    val rev = vm.revision
    val bio = remember(rev) { vm.biometricEnabled() }
    val trustedUntil = remember(rev) { vm.deviceTrustedUntil() }
    var pw by rememberSaveable { mutableStateOf("") }
    var askBio by remember { mutableStateOf(false) }
    var askWipe by remember { mutableStateOf(false) }
    val promptTitle = stringResource(R.string.settings_biometric_prompt)
    val cancel = stringResource(R.string.cancel)
    val canBio = remember { BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LargeTitle(stringResource(R.string.settings_title), Modifier.padding(start = 6.dp, bottom = 6.dp))

        SectionHeader(stringResource(R.string.settings_biometric))
        InsetGroup {
            ListRow(
                stringResource(R.string.settings_biometric),
                subtitle = if (bio) stringResource(R.string.settings_biometric_on) else null,
                leading = { IconTile(Icons.Filled.Fingerprint) },
                onClick = if (bio || canBio) ({ if (bio) vm.disableBiometric() else askBio = true }) else null,
            ) { com.damfizz.myvault.ui.glass.GlassToggle(bio, { on -> if (on) askBio = true else vm.disableBiometric() }) }
        }
        Footnote(stringResource(R.string.settings_biometric_body), Modifier.padding(horizontal = 16.dp))

        SectionHeader(stringResource(R.string.settings_security))
        InsetGroup {
            ListRow(stringResource(if (vm.hasSecretKeyMode()) R.string.settings_secret_key_on else R.string.settings_secret_key_off), leading = { IconTile(Icons.Filled.Key) })
            GroupDivider(68.dp)
            ListRow(stringResource(if (vm.hasRecoveryKey()) R.string.settings_recovery_yes else R.string.settings_recovery_no), leading = { IconTile(Icons.Filled.VpnKey) })
            if (trustedUntil != null) {
                GroupDivider(68.dp)
                ListRow(
                    stringResource(R.string.settings_trust, DateFormat.getDateInstance().format(Date(trustedUntil))),
                    subtitle = stringResource(R.string.settings_trust_forget),
                    leading = { IconTile(Icons.Filled.Shield) },
                    onClick = vm::forgetTrust,
                )
            }
        }
        vm.lastKdfMillis?.let { Footnote(stringResource(R.string.settings_kdf, it.toInt()), Modifier.padding(horizontal = 16.dp)) }
        Footnote(stringResource(R.string.settings_autolock), Modifier.padding(horizontal = 16.dp))
        Footnote(stringResource(R.string.settings_readonly), Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(6.dp))
        InsetGroup { ListRow(stringResource(R.string.settings_wipe), titleColor = c.danger, onClick = { askWipe = true }) }
        Footnote(stringResource(R.string.settings_version, BuildConfigCompat.versionName(activity)), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }

    GlassSheet(visible = askBio, onDismiss = { askBio = false; pw = ""; vm.clearError() }) {
        fun submit() {
            if (pw.isEmpty()) return
            val password = pw
            pw = ""
            askBio = false
            runCatching { BiometricKey.encryptCipher() }.onSuccess { cipher ->
                showBiometricPrompt(activity, promptTitle, cancel, cipher) { vm.enableBiometric(it, password) }
            }
        }
        Text(stringResource(R.string.settings_biometric), style = Type.headline, color = c.text)
        Body(stringResource(R.string.settings_biometric_body))
        GlassTextField(pw, { pw = it }, stringResource(R.string.master_password), secret = true, onDone = ::submit)
        GlassButton(stringResource(R.string.settings_biometric_enable), ::submit, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty(), icon = Icons.Filled.Fingerprint)
    }
    GlassAlert(
        visible = askWipe,
        title = stringResource(R.string.settings_wipe),
        message = stringResource(R.string.settings_wipe_confirm),
        confirm = stringResource(R.string.settings_wipe_yes),
        onConfirm = { askWipe = false; vm.wipeDevice() },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { askWipe = false },
        destructive = true,
    )
}
