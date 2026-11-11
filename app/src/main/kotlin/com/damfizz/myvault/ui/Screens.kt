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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.damfizz.myvault.BuildConfigCompat
import com.damfizz.myvault.R
import com.damfizz.myvault.core.ItemType
import com.damfizz.myvault.core.VaultItem
import java.text.DateFormat
import java.util.Date
import javax.crypto.Cipher

@Composable
fun AppRoot(vm: VaultViewModel) {
    LiquidBackground {
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = { (fadeIn() + scaleIn(spring(dampingRatio = 0.75f, stiffness = 380f), initialScale = 0.96f)) togetherWith fadeOut() },
            contentKey = { it::class },
            label = "screen",
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        ) { s ->
            when (s) {
                Screen.Welcome -> WelcomeScreen(vm)
                Screen.Create -> CreateScreen(vm)
                Screen.Import -> ImportScreen(vm)
                Screen.Unlock -> UnlockScreen(vm)
                is Screen.SecondFactor -> SecondFactorScreen(vm, s)
                Screen.Vault -> VaultScreen(vm)
                is Screen.Detail -> DetailScreen(vm, s.id)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
        if (vm.busy) BusyOverlay()
    }
}

@Composable
private fun BusyOverlay() {
    Box(Modifier.fillMaxSize().background(Color(0x99060914)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.busy_kdf), color = Color.White)
            }
        }
    }
}

@Composable
private fun Page(onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    if (onBack != null) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
        } else {
            Spacer(Modifier.height(24.dp))
        }
        content()
    }
}

// ------------------------------------------------------------------ welcome / create / import

@Composable
private fun WelcomeScreen(vm: VaultViewModel) = Page {
    Spacer(Modifier.height(40.dp))
    Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Lock, null, tint = Accent, modifier = Modifier.size(34.dp))
    }
    Title(stringResource(R.string.welcome_title))
    Body(stringResource(R.string.welcome_body))
    Spacer(Modifier.height(8.dp))
    GlassButton(stringResource(R.string.welcome_import), { vm.go(Screen.Import) }, Modifier.fillMaxWidth())
    GlassButton(stringResource(R.string.welcome_create), { vm.go(Screen.Create) }, Modifier.fillMaxWidth(), primary = false)
}

@Composable
private fun CreateScreen(vm: VaultViewModel) = Page(onBack = { vm.go(Screen.Welcome) }) {
    var pw by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    Title(stringResource(R.string.create_title))
    GlassCard {
        GlassField(pw, { pw = it }, stringResource(R.string.create_password), secret = true)
        Spacer(Modifier.height(10.dp))
        GlassField(confirm, { confirm = it }, stringResource(R.string.create_confirm), secret = true)
        Spacer(Modifier.height(10.dp))
        Body(stringResource(R.string.create_hint))
        ErrorText(vm.error)
    }
    GlassButton(stringResource(R.string.create_button), { vm.create(pw, confirm) }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

@Composable
private fun ImportScreen(vm: VaultViewModel) = Page(onBack = { vm.cancelImport(); vm.go(Screen.Welcome) }) {
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
    Title(stringResource(R.string.import_title))
    Body(stringResource(R.string.import_body))
    GlassButton(fileName?.let { stringResource(R.string.import_picked, it) } ?: stringResource(R.string.import_pick), { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), primary = fileText == null)
    if (fileText != null) {
        GlassCard {
            GlassField(pw, { pw = it }, stringResource(R.string.import_backup_password), secret = true)
            ErrorText(vm.error)
        }
        GlassButton(stringResource(R.string.import_open), { vm.openBackupFile(fileText!!, pw) }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
    }
    vm.pendingImport?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(R.string.import_confirm_body, DateFormat.getDateTimeInstance().format(Date(p.opened.createdAt)), p.opened.records.size)) },
            confirmButton = { TextButton(onClick = vm::confirmImport) { Text(stringResource(R.string.import_replace)) } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text(stringResource(R.string.cancel)) } },
        )
    }
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
private fun UnlockScreen(vm: VaultViewModel) = Page {
    val context = LocalContext.current
    val activity = context as FragmentActivity
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
    LaunchedEffect(Unit) { if (bio) startBiometric() }

    Spacer(Modifier.height(32.dp))
    Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Lock, null, tint = Accent, modifier = Modifier.size(34.dp))
    }
    Title(stringResource(R.string.unlock_title))
    if (!recovery) {
        GlassCard {
            GlassField(pw, { pw = it }, stringResource(R.string.master_password), secret = true)
            if (vm.needsSecretKey) {
                Spacer(Modifier.height(10.dp))
                GlassField(sk, { sk = it.uppercase() }, stringResource(R.string.secret_key), placeholder = stringResource(R.string.secret_key_hint))
                CheckRow(stringResource(R.string.remember_secret_key), rememberSk) { rememberSk = it }
            }
            ErrorText(vm.error)
        }
        GlassButton(stringResource(R.string.unlock_button), { vm.unlock(pw, sk, rememberSk); pw = "" }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
        if (bio) {
            GlassButton(stringResource(R.string.unlock_biometric), { startBiometric() }, Modifier.fillMaxWidth(), primary = false)
        }
        TextButton(onClick = { recovery = true; vm.clearError() }) { Text(stringResource(R.string.forgot_password), color = Accent) }
    } else {
        BackHandler { recovery = false }
        GlassCard {
            Text(stringResource(R.string.recovery_title), color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            GlassField(rk, { rk = it.uppercase() }, stringResource(R.string.recovery_key))
            Spacer(Modifier.height(10.dp))
            GlassField(newPw, { newPw = it }, stringResource(R.string.new_master_password), secret = true)
            ErrorText(vm.error)
        }
        GlassButton(stringResource(R.string.recovery_button), { vm.unlockWithRecoveryKey(rk, newPw) }, Modifier.fillMaxWidth(), enabled = rk.isNotBlank() && newPw.isNotEmpty())
        TextButton(onClick = { recovery = false; vm.clearError() }) { Text(stringResource(R.string.back), color = Accent) }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
    }
}

@Composable
private fun SecondFactorScreen(vm: VaultViewModel, s: Screen.SecondFactor) = Page(onBack = { vm.lock() }) {
    var code by rememberSaveable { mutableStateOf("") }
    var trust by rememberSaveable { mutableStateOf(false) }
    var useRecovery by rememberSaveable { mutableStateOf(false) }
    var rc by rememberSaveable { mutableStateOf("") }
    Title(stringResource(R.string.twofa_title))
    if (!useRecovery) {
        Body(stringResource(R.string.twofa_body))
        GlassCard {
            GlassField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, stringResource(R.string.twofa_code), keyboard = KeyboardType.NumberPassword)
            CheckRow(stringResource(R.string.twofa_trust), trust) { trust = it }
            ErrorText(vm.error)
        }
        GlassButton(stringResource(R.string.twofa_verify), { vm.verifyTotp(s.challenge, code, trust) }, Modifier.fillMaxWidth(), enabled = code.length == 6)
        TextButton(onClick = { useRecovery = true; vm.clearError() }) { Text(stringResource(R.string.twofa_use_recovery), color = Accent) }
    } else {
        GlassCard {
            GlassField(rc, { rc = it.uppercase() }, stringResource(R.string.twofa_recovery_code))
            Body(stringResource(R.string.twofa_codes_left, s.challenge.recoveryCodesLeft), Modifier.padding(top = 8.dp))
            ErrorText(vm.error)
        }
        GlassButton(stringResource(R.string.twofa_recovery_button), { vm.useRecoveryCode(s.challenge, rc) }, Modifier.fillMaxWidth(), enabled = rc.isNotBlank())
        TextButton(onClick = { useRecovery = false; vm.clearError() }) { Text(stringResource(R.string.back), color = Accent) }
    }
}

// ------------------------------------------------------------------ vault list

private fun iconFor(type: String): ImageVector = when (type) {
    ItemType.WIFI -> Icons.Filled.Wifi
    ItemType.NOTE -> Icons.Filled.Notes
    ItemType.LICENSE -> Icons.Filled.VpnKey
    ItemType.RECOVERY_CODES -> Icons.Filled.Key
    else -> Icons.Filled.Person
}

private fun subtitle(i: VaultItem): String = listOfNotNull(i.username, i.email, i.ssid, i.product, i.service, i.url)
    .firstOrNull { it.isNotBlank() }.orEmpty()

@Composable
private fun VaultScreen(vm: VaultViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val rev = vm.revision
    val all = remember(rev) { vm.items().filter { it.archivedAt == null } }
    val categories = remember(rev) { vm.collections().filter { it.kind == "category" } }
    val shown = remember(rev, query, filter) {
        all.filter { i ->
            val q = query.trim().lowercase()
            val matchesQuery = q.isEmpty() || listOfNotNull(i.title, i.username, i.email, i.url, i.ssid, i.product).any { it.lowercase().contains(q) }
            val matchesFilter = when (filter) {
                "all" -> true
                "fav" -> i.favorite
                else -> i.categoryId == filter
            }
            matchesQuery && matchesFilter
        }.sortedWith(compareByDescending<VaultItem> { it.favorite }.thenBy { it.title.lowercase() })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MyVault", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings), tint = Color.White) }
            IconButton(onClick = vm::lock) { Icon(Icons.Filled.Lock, stringResource(R.string.lock), tint = Color.White) }
        }
        GlassField(query, { query = it }, stringResource(R.string.vault_search), Modifier.padding(vertical = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            item { Chip(stringResource(R.string.vault_all), filter == "all") { filter = "all" } }
            item { Chip(stringResource(R.string.vault_favorites), filter == "fav") { filter = "fav" } }
            items(categories, key = { it.id }) { c -> Chip(c.name, filter == c.id) { filter = c.id } }
        }
        if (shown.isEmpty()) {
            Body(stringResource(R.string.vault_empty), Modifier.padding(16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { item ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { vm.go(Screen.Detail(item.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(iconFor(item.type), null, tint = Accent)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title.ifBlank { stringResource(R.string.untitled) }, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = subtitle(item)
                            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.favorite) Icon(Icons.Filled.Star, null, tint = Color(0xFFFFD166), modifier = Modifier.size(18.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .then(if (selected) Modifier.clip(shape).background(Accent.copy(alpha = 0.9f)) else Modifier.glass(shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Color(0xFF0B1020) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

@Composable
private fun DetailScreen(vm: VaultViewModel, id: String) = Page(onBack = { vm.go(Screen.Vault) }) {
    val item = remember(vm.revision, id) { vm.item(id) } ?: return@Page
    val context = LocalContext.current
    val copied = stringResource(R.string.detail_copied)
    var unlockedTick by remember { mutableStateOf(0) }
    val locked = item.reprompt && id !in vm.revealed && unlockedTick >= 0
    var pw by rememberSaveable { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(iconFor(item.type), null, tint = Accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(item.title.ifBlank { stringResource(R.string.untitled) }, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }

    if (locked) {
        GlassCard {
            Text(stringResource(R.string.detail_reprompt_title), color = Color.White, fontWeight = FontWeight.SemiBold)
            Body(stringResource(R.string.detail_reprompt_body), Modifier.padding(vertical = 8.dp))
            GlassField(pw, { pw = it }, stringResource(R.string.master_password), secret = true)
            ErrorText(vm.error)
        }
        GlassButton(stringResource(R.string.detail_unlock), { vm.reprompt(id, pw) { unlockedTick++ }; pw = "" }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
    }

    val onCopy = { label: String, value: String ->
        copySensitive(context, label, value)
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Field(stringResource(R.string.field_username), item.username, onCopy = onCopy)
        Field(stringResource(R.string.field_email), item.email, onCopy = onCopy)
        Field(stringResource(R.string.field_password), item.password, secret = true, locked = locked, onCopy = onCopy)
        Field(stringResource(R.string.field_url), item.url, onCopy = onCopy)
        Field(stringResource(R.string.field_ssid), item.ssid, onCopy = onCopy)
        Field(stringResource(R.string.field_wifi_security), item.wifiSecurity, onCopy = onCopy)
        Field(stringResource(R.string.field_product), item.product, onCopy = onCopy)
        Field(stringResource(R.string.field_license_key), item.licenseKey, secret = true, locked = locked, onCopy = onCopy)
        Field(stringResource(R.string.field_licensee), item.licensee, onCopy = onCopy)
        Field(stringResource(R.string.field_version), item.version, onCopy = onCopy)
        Field(stringResource(R.string.field_purchase_date), item.purchaseDate, onCopy = onCopy)
        Field(stringResource(R.string.field_service), item.service, onCopy = onCopy)
        Field(stringResource(R.string.field_codes), item.codes, secret = true, locked = locked, onCopy = onCopy, mono = true)
        Field(stringResource(R.string.field_recovery_email), item.recoveryEmail, onCopy = onCopy)
        Field(stringResource(R.string.field_recovery_info), item.recoveryInfo, onCopy = onCopy)
        for (f in item.customFields) Field(f.label, f.value, secret = f.hidden, locked = locked && f.hidden, onCopy = onCopy)
        Field(stringResource(R.string.field_notes), item.notes, onCopy = onCopy, multiline = true)
        Field(stringResource(R.string.field_important_notes), item.importantNotes, onCopy = onCopy, multiline = true)
        if (item.tags.isNotEmpty()) Field(stringResource(R.string.field_tags), item.tags.joinToString(", "), onCopy = onCopy)
    }
}

@Composable
private fun Field(
    label: String,
    value: String?,
    secret: Boolean = false,
    locked: Boolean = false,
    mono: Boolean = false,
    multiline: Boolean = false,
    onCopy: (String, String) -> Unit,
) {
    if (value.isNullOrBlank()) return
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = when {
                secret && (locked || !shown) -> "••••••••••"
                else -> value
            }
            Text(
                text,
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = if (secret || mono) FontFamily.Monospace else FontFamily.Default,
                maxLines = if (multiline || mono) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!locked) {
                if (secret) TextButton(onClick = { shown = !shown }) { Text(stringResource(if (shown) R.string.detail_hide else R.string.detail_reveal), color = Accent) }
                TextButton(onClick = { onCopy(label, value) }) { Text(stringResource(R.string.detail_copy), color = Accent) }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsScreen(vm: VaultViewModel) = Page(onBack = { vm.go(Screen.Vault) }) {
    val activity = LocalContext.current as FragmentActivity
    val rev = vm.revision
    val bio = remember(rev) { vm.biometricEnabled() }
    val trustedUntil = remember(rev) { vm.deviceTrustedUntil() }
    var pw by rememberSaveable { mutableStateOf("") }
    var askWipe by remember { mutableStateOf(false) }
    val promptTitle = stringResource(R.string.settings_biometric_prompt)
    val cancel = stringResource(R.string.cancel)
    val canBio = remember { BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS }

    Title(stringResource(R.string.settings_title))
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Fingerprint, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.settings_biometric), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Body(stringResource(R.string.settings_biometric_body), Modifier.padding(vertical = 8.dp))
        if (bio) {
            Body(stringResource(R.string.settings_biometric_on))
            Spacer(Modifier.height(8.dp))
            GlassButton(stringResource(R.string.settings_biometric_disable), vm::disableBiometric, Modifier.fillMaxWidth(), primary = false)
        } else if (canBio) {
            GlassField(pw, { pw = it }, stringResource(R.string.master_password), secret = true)
            Spacer(Modifier.height(8.dp))
            GlassButton(stringResource(R.string.settings_biometric_enable), {
                val password = pw
                runCatching { com.damfizz.myvault.security.BiometricKey.encryptCipher() }.onSuccess { c ->
                    showBiometricPrompt(activity, promptTitle, cancel, c) { vm.enableBiometric(it, password) }
                }
                pw = ""
            }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
        }
        ErrorText(vm.error)
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_security), color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Body(stringResource(if (vm.hasSecretKeyMode()) R.string.settings_secret_key_on else R.string.settings_secret_key_off))
        Body(stringResource(if (vm.hasRecoveryKey()) R.string.settings_recovery_yes else R.string.settings_recovery_no))
        if (trustedUntil != null) {
            Body(stringResource(R.string.settings_trust, DateFormat.getDateInstance().format(Date(trustedUntil))))
            TextButton(onClick = vm::forgetTrust) { Text(stringResource(R.string.settings_trust_forget), color = Accent) }
        }
        vm.lastKdfMillis?.let { Body(stringResource(R.string.settings_kdf, it.toInt())) }
        Body(stringResource(R.string.settings_autolock))
    }
    GlassCard(Modifier.fillMaxWidth()) { Body(stringResource(R.string.settings_readonly)) }
    GlassButton(stringResource(R.string.settings_wipe), { askWipe = true }, Modifier.fillMaxWidth(), primary = false)
    Body(stringResource(R.string.settings_version, BuildConfigCompat.versionName(activity)))
    if (askWipe) {
        AlertDialog(
            onDismissRequest = { askWipe = false },
            title = { Text(stringResource(R.string.settings_wipe)) },
            text = { Text(stringResource(R.string.settings_wipe_confirm)) },
            confirmButton = { TextButton(onClick = { askWipe = false; vm.wipeDevice() }) { Text(stringResource(R.string.settings_wipe_yes), color = Color(0xFFFF8A80)) } },
            dismissButton = { TextButton(onClick = { askWipe = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
