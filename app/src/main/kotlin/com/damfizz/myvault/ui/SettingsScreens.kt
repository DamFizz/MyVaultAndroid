package com.damfizz.myvault.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.damfizz.myvault.BuildConfigCompat
import com.damfizz.myvault.R
import com.damfizz.myvault.core.Collections
import com.damfizz.myvault.core.Totp
import com.damfizz.myvault.core.VaultCollection
import com.damfizz.myvault.security.BiometricKey
import com.damfizz.myvault.ui.glass.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ small building blocks

/** Lucide icon names used by the web app → the closest Material icon. */
internal fun collectionIcon(name: String): ImageVector = when (name) {
    "message-circle" -> Icons.Filled.ChatBubble
    "users" -> Icons.Filled.People
    "gamepad" -> Icons.Filled.SportsEsports
    "trophy" -> Icons.Filled.EmojiEvents
    "landmark" -> Icons.Filled.AccountBalance
    "wallet" -> Icons.Filled.AccountBalanceWallet
    "credit-card" -> Icons.Filled.CreditCard
    "piggy-bank" -> Icons.Filled.Savings
    "briefcase" -> Icons.Filled.Work
    "building" -> Icons.Filled.Business
    "graduation-cap" -> Icons.Filled.School
    "book" -> Icons.AutoMirrored.Filled.MenuBook
    "mail" -> Icons.Filled.Email
    "at-sign" -> Icons.Filled.AlternateEmail
    "shopping-bag" -> Icons.Filled.ShoppingBag
    "gift" -> Icons.Filled.CardGiftcard
    "clapperboard" -> Icons.Filled.Movie
    "music" -> Icons.Filled.MusicNote
    "camera" -> Icons.Filled.PhotoCamera
    "globe" -> Icons.Filled.Public
    "code" -> Icons.Filled.Code
    "terminal" -> Icons.Filled.Terminal
    "server" -> Icons.Filled.Dns
    "database" -> Icons.Filled.Storage
    "laptop" -> Icons.Filled.Laptop
    "monitor" -> Icons.Filled.Monitor
    "smartphone" -> Icons.Filled.Smartphone
    "wifi" -> Icons.Filled.Wifi
    "home" -> Icons.Filled.Home
    "car" -> Icons.Filled.DirectionsCar
    "plane" -> Icons.Filled.Flight
    "heart" -> Icons.Filled.Favorite
    "flame" -> Icons.Filled.LocalFireDepartment
    "zap" -> Icons.Filled.Bolt
    "lightbulb" -> Icons.Filled.Lightbulb
    "palette" -> Icons.Filled.Palette
    "package" -> Icons.Filled.Inventory2
    "user" -> Icons.Filled.Person
    "key" -> Icons.Filled.Key
    "shield" -> Icons.Filled.Shield
    "lock" -> Icons.Filled.Lock
    else -> Icons.Filled.Folder
}

internal fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF64748B))

@Composable
private fun <T> OptionSheet(visible: Boolean, title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(visible, onDismiss) {
        Text(title, style = Type.headline, color = LocalGlassColors.current.text)
        SheetList {
            InsetGroup {
                options.forEachIndexed { i, (value, label) ->
                    ListRow(label, onClick = { onPick(value); onDismiss() }) { CheckMark(value == selected) }
                    if (i != options.lastIndex) GroupDivider()
                }
            }
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = LocalGlassColors.current
    ListRow(title, leading = icon?.let { { IconTile(it, 34.dp) } }, onClick = onClick) {
        Text(value, style = Type.callout, color = c.text2, maxLines = 1)
        Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
    }
}

/** A sheet that asks for the master password before a sensitive change. */
@Composable
private fun PasswordSheet(visible: Boolean, title: String, body: String?, confirm: String, vm: VaultViewModel, destructive: Boolean = false, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pw by rememberSaveable { mutableStateOf("") }
    GlassSheet(visible, onDismiss = { pw = ""; vm.clearError(); onDismiss() }) {
        fun submit() {
            if (pw.isNotEmpty()) {
                onSubmit(pw)
                pw = ""
            }
        }
        Text(title, style = Type.headline, color = LocalGlassColors.current.text)
        if (body != null) Body(body)
        GlassTextField(pw, { pw = it }, stringResource(R.string.master_password), secret = true, onDone = ::submit)
        ErrorText(vm.error)
        GlassButton(confirm, ::submit, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty(), destructive = destructive)
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    }
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color(0xFF111113), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

// ------------------------------------------------------------------ settings tab

@Composable
internal fun SettingsTab(vm: VaultViewModel, pad: PaddingValues) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val host = LocalOverlayHost.current
    val rev = vm.revision
    val prefs = vm.prefs
    val settings = remember(rev) { vm.vaultSettings() } ?: return
    val security = remember(rev) { vm.security() } ?: return
    val bio = remember(rev) { vm.biometricEnabled() }
    val trustedUntil = remember(rev) { vm.deviceTrustedUntil() }
    val categories = remember(rev) { vm.collections().filter { it.kind == "category" } }
    // Re-read when coming back to the app (e.g. after adding a fingerprint in the phone's settings).
    var resumeTick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val bioStatus = remember(rev, resumeTick) { com.damfizz.myvault.security.BiometricSupport.status(activity) }
    val canBio = bioStatus == com.damfizz.myvault.security.BiometricSupport.Status.AVAILABLE
    var sheet by remember { mutableStateOf("") }
    fun close() { sheet = "" }
    val promptTitle = stringResource(R.string.settings_biometric_prompt)
    val cancel = stringResource(R.string.cancel)
    val never = stringResource(R.string.set_never)
    val copied = stringResource(R.string.detail_copied)

    @Composable fun mins(v: Int) = if (v == 0) never else stringResource(R.string.set_minutes, v)
    @Composable fun secs(v: Int, zero: String = never) = when {
        v < 0 -> never
        v == 0 -> zero
        v % 60 == 0 -> stringResource(R.string.set_minutes, v / 60)
        else -> stringResource(R.string.set_seconds, v)
    }
    @Composable fun days(v: Int, zero: String) = if (v == 0) zero else stringResource(R.string.set_days, v)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LargeTitle(stringResource(R.string.settings_title), Modifier.padding(start = 6.dp, bottom = 6.dp))

        SectionHeader(stringResource(R.string.set_appearance))
        InsetGroup {
            ValueRow(stringResource(R.string.set_display_name), prefs.displayName.ifBlank { stringResource(R.string.set_display_name_none) }, Icons.Filled.Person) { sheet = "name" }
            GroupDivider(64.dp)
            val themeLabel = stringResource(when (prefs.theme) { "dark" -> R.string.set_theme_dark; "light" -> R.string.set_theme_light; else -> R.string.set_theme_system })
            ValueRow(stringResource(R.string.set_theme), themeLabel, Icons.Filled.DarkMode) { sheet = "theme" }
            GroupDivider(64.dp)
            ValueRow(stringResource(R.string.set_background), stringResource(wallpaperLabel(prefs.wallpaper)), Icons.Filled.Wallpaper) { sheet = "wallpaper" }
            GroupDivider(64.dp)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.set_accent), style = Type.body, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((name, pair) in Accents) {
                        val col = if (c.dark) pair.second else pair.first
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(col)
                                .then(if (prefs.accent == name) Modifier.border(3.dp, c.text, CircleShape) else Modifier)
                                .clickable { prefs.updateAccent(name) },
                        )
                    }
                }
            }
            GroupDivider(64.dp)
            val motionLabel = stringResource(when (prefs.motion) { "reduced" -> R.string.set_motion_reduced; "full" -> R.string.set_motion_full; else -> R.string.set_theme_system })
            ValueRow(stringResource(R.string.set_motion), motionLabel, Icons.Filled.Animation) { sheet = "motion" }
            GroupDivider(16.dp)
            ToggleRow(stringResource(R.string.set_reduce_transparency), prefs.reduceTransparency, prefs::updateReduceTransparency, stringResource(R.string.set_reduce_transparency_sub))
            GroupDivider(16.dp)
            ToggleRow(stringResource(R.string.set_high_contrast), prefs.highContrast, prefs::updateHighContrast)
            if (Build.VERSION.SDK_INT >= 33) {
                GroupDivider(64.dp)
                val tag = remember(rev, sheet) { context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() }
                ValueRow(stringResource(R.string.set_language), when { tag.startsWith("ms") -> "Bahasa Melayu"; tag.startsWith("en") -> "English"; else -> stringResource(R.string.set_language_system) }, Icons.Filled.Language) { sheet = "lang" }
            }
        }

        SectionHeader(stringResource(R.string.settings_security))
        InsetGroup {
            ListRow(stringResource(R.string.set_change_password), leading = { IconTile(Icons.Filled.Password, 34.dp) }, onClick = { sheet = "password" }) {
                Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
            }
            GroupDivider(64.dp)
            ListRow(
                stringResource(R.string.set_totp),
                subtitle = stringResource(if (security.totp != null) R.string.set_totp_on else R.string.set_totp_off),
                leading = { IconTile(Icons.Filled.PhonelinkLock, 34.dp) },
                onClick = { sheet = if (security.totp != null) "totpOff" else "totpOn" },
            ) { GlassToggle(security.totp != null, { sheet = if (security.totp != null) "totpOff" else "totpOn" }) }
            if (security.totp != null) {
                GroupDivider(64.dp)
                ListRow(
                    stringResource(R.string.set_recovery_codes),
                    subtitle = stringResource(R.string.set_recovery_codes_left, security.recoveryCodeHashes.size),
                    leading = { IconTile(Icons.Filled.Pin, 34.dp) },
                    onClick = { sheet = "codes" },
                ) { Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp)) }
            }
            GroupDivider(64.dp)
            ListRow(
                stringResource(R.string.set_recovery_key),
                subtitle = stringResource(if (vm.hasRecoveryKey()) R.string.settings_recovery_yes else R.string.settings_recovery_no),
                leading = { IconTile(Icons.Filled.VpnKey, 34.dp) },
                onClick = { sheet = "rk" },
            ) { Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp)) }
            GroupDivider(64.dp)
            ListRow(stringResource(if (vm.hasSecretKeyMode()) R.string.settings_secret_key_on else R.string.settings_secret_key_off), leading = { IconTile(Icons.Filled.Key, 34.dp) }, onClick = { sheet = "sk" }) {
                Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
            }
            GroupDivider(64.dp)
            val bioHint = when {
                bio -> stringResource(R.string.settings_biometric_on)
                bioStatus == com.damfizz.myvault.security.BiometricSupport.Status.NONE_ENROLLED -> stringResource(R.string.bio_none_enrolled)
                bioStatus == com.damfizz.myvault.security.BiometricSupport.Status.WEAK_ONLY -> stringResource(R.string.bio_weak_only)
                bioStatus == com.damfizz.myvault.security.BiometricSupport.Status.UNAVAILABLE -> stringResource(R.string.bio_unavailable)
                else -> null
            }
            val openEnroll = { runCatching { activity.startActivity(com.damfizz.myvault.security.BiometricSupport.enrollIntent()) }; Unit }
            ListRow(
                stringResource(R.string.settings_biometric),
                subtitle = bioHint,
                leading = { IconTile(Icons.Filled.Fingerprint, 34.dp) },
                onClick = when {
                    bio -> ({ vm.disableBiometric() })
                    canBio -> ({ sheet = "bio" })
                    bioStatus == com.damfizz.myvault.security.BiometricSupport.Status.UNAVAILABLE -> null
                    else -> openEnroll
                },
            ) { if (bio || canBio) GlassToggle(bio, { on -> if (on) sheet = "bio" else vm.disableBiometric() }) }
            if (trustedUntil != null) {
                GroupDivider(64.dp)
                ListRow(
                    stringResource(R.string.settings_trust, DateFormat.getDateInstance().format(Date(trustedUntil))),
                    subtitle = stringResource(R.string.settings_trust_forget),
                    leading = { IconTile(Icons.Filled.Shield, 34.dp) },
                    onClick = vm::forgetTrust,
                )
            }
        }
        Footnote(stringResource(R.string.set_totp_note), Modifier.padding(horizontal = 16.dp))

        SectionHeader(stringResource(R.string.set_locking))
        InsetGroup {
            ValueRow(stringResource(R.string.set_auto_lock), mins(prefs.autoLockMinutes)) { sheet = "autoLock" }
            GroupDivider()
            ValueRow(stringResource(R.string.set_lock_hidden), secs(prefs.lockOnHiddenSeconds, stringResource(R.string.set_immediately))) { sheet = "hidden" }
            GroupDivider()
            ValueRow(stringResource(R.string.set_reveal), secs(prefs.revealSeconds)) { sheet = "reveal" }
            GroupDivider()
            ValueRow(stringResource(R.string.set_clipboard), secs(prefs.clipboardClearSeconds)) { sheet = "clip" }
        }

        SectionHeader(stringResource(R.string.set_vault))
        InsetGroup {
            ListRow(stringResource(R.string.set_organize), leading = { IconTile(Icons.Filled.Folder, 34.dp) }, onClick = { vm.go(Screen.Organize) }) {
                Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
            }
            GroupDivider(64.dp)
            ValueRow(stringResource(R.string.set_default_category), categories.find { it.id == settings.defaultCategoryId }?.name ?: stringResource(R.string.field_none)) { sheet = "defCat" }
            GroupDivider()
            ValueRow(stringResource(R.string.set_history), if (settings.passwordHistoryLimit == 0) stringResource(R.string.set_off) else stringResource(R.string.set_history_keep, settings.passwordHistoryLimit)) { sheet = "history" }
            GroupDivider()
            ValueRow(stringResource(R.string.set_old_passwords), days(settings.oldPasswordDays, never)) { sheet = "old" }
            GroupDivider()
            ValueRow(stringResource(R.string.set_trash_retention), days(settings.trashRetentionDays, stringResource(R.string.set_keep_forever))) { sheet = "trash" }
            GroupDivider()
            ToggleRow(stringResource(R.string.set_reprompt_default), settings.repromptByDefault, { on -> vm.updateVaultSettings { it.copy(repromptByDefault = on) } })
        }

        SectionHeader(stringResource(R.string.set_data))
        InsetGroup {
            ListRow(stringResource(R.string.sync_title), subtitle = vm.prefs.driveEmail, leading = { IconTile(Icons.Filled.CloudSync, 34.dp) }, onClick = { vm.go(Screen.Sync) }) {
                Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
            }
            GroupDivider(64.dp)
            ListRow(stringResource(R.string.bk_title), leading = { IconTile(Icons.Filled.Backup, 34.dp) }, onClick = { vm.go(Screen.BackupRestore) }) {
                Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
            }
            GroupDivider(64.dp)
            ValueRow(stringResource(R.string.set_device_name), vm.deviceName()) { sheet = "device" }
        }
        Footnote(stringResource(R.string.set_storage, vm.recordCount()), Modifier.padding(horizontal = 16.dp))
        vm.lastKdfMillis?.let { Footnote(stringResource(R.string.settings_kdf, it.toInt()), Modifier.padding(horizontal = 16.dp)) }
        Footnote(stringResource(R.string.settings_readonly), Modifier.padding(horizontal = 16.dp))

        SectionHeader(stringResource(R.string.set_about))
        InsetGroup {
            ListRow(
                stringResource(R.string.upd_check),
                subtitle = vm.update?.let { stringResource(R.string.upd_available, it.version) } ?: stringResource(R.string.upd_current, vm.installedVersion),
                leading = { IconTile(Icons.Filled.SystemUpdate, 34.dp) },
                onClick = { sheet = "update"; if (vm.update == null) vm.checkForUpdates(manual = true) },
            ) { Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp)) }
            GroupDivider(64.dp)
            ToggleRow(stringResource(R.string.upd_auto), prefs.autoUpdateCheck, prefs::updateAutoUpdateCheck, subtitle = stringResource(R.string.upd_auto_help))
        }
        Footnote(stringResource(R.string.set_about_security), Modifier.padding(horizontal = 16.dp), color = c.text2)
        Footnote(stringResource(R.string.set_about_privacy), Modifier.padding(horizontal = 16.dp), color = c.text2)
        Footnote(stringResource(R.string.settings_version, BuildConfigCompat.versionName(activity)), Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(6.dp))
        InsetGroup { ListRow(stringResource(R.string.settings_wipe), titleColor = c.danger, onClick = { sheet = "wipe" }) }
        Spacer(Modifier.height(8.dp))
    }

    // ---- option sheets
    GlassSheet(visible = sheet == "name", onDismiss = ::close) {
        var draft by remember(sheet) { mutableStateOf(prefs.displayName) }
        Text(stringResource(R.string.set_display_name), style = Type.headline, color = c.text)
        Footnote(stringResource(R.string.set_display_name_sub))
        GlassTextField(draft, { draft = it.take(40) }, stringResource(R.string.set_display_name_hint), onDone = { prefs.updateDisplayName(draft); close() })
        GlassButton(stringResource(R.string.action_save), { prefs.updateDisplayName(draft); close() }, Modifier.fillMaxWidth())
    }
    OptionSheet(sheet == "motion", stringResource(R.string.set_motion), listOf("system" to stringResource(R.string.set_theme_system), "reduced" to stringResource(R.string.set_motion_reduced), "full" to stringResource(R.string.set_motion_full)), prefs.motion, prefs::updateMotion, ::close)
    OptionSheet(sheet == "theme", stringResource(R.string.set_theme), listOf("system" to stringResource(R.string.set_theme_system), "light" to stringResource(R.string.set_theme_light), "dark" to stringResource(R.string.set_theme_dark)), prefs.theme, prefs::updateTheme, ::close)
    if (Build.VERSION.SDK_INT >= 33) {
        val lm = context.getSystemService(LocaleManager::class.java)
        OptionSheet(sheet == "lang", stringResource(R.string.set_language), listOf("" to stringResource(R.string.set_language_system), "en" to "English", "ms" to "Bahasa Melayu"), lm.applicationLocales.toLanguageTags().take(2), { lm.applicationLocales = if (it.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(it) }, ::close)
    }
    OptionSheet(sheet == "autoLock", stringResource(R.string.set_auto_lock), listOf(1, 5, 10, 30, 60, 0).map { it to mins(it) }, prefs.autoLockMinutes, prefs::updateAutoLockMinutes, ::close)
    OptionSheet(sheet == "hidden", stringResource(R.string.set_lock_hidden), listOf(0, 30, 60, 300, -1).map { it to secs(it, stringResource(R.string.set_immediately)) }, prefs.lockOnHiddenSeconds, prefs::updateLockOnHiddenSeconds, ::close)
    OptionSheet(sheet == "reveal", stringResource(R.string.set_reveal), listOf(10, 20, 60, 0).map { it to secs(it) }, prefs.revealSeconds, prefs::updateRevealSeconds, ::close)
    OptionSheet(sheet == "clip", stringResource(R.string.set_clipboard), listOf(10, 30, 60, 120, 0).map { it to secs(it) }, prefs.clipboardClearSeconds, prefs::updateClipboardClearSeconds, ::close)
    OptionSheet(sheet == "defCat", stringResource(R.string.set_default_category), listOf<Pair<String?, String>>(null to stringResource(R.string.field_none)) + categories.map { it.id to it.name }, settings.defaultCategoryId, { v -> vm.updateVaultSettings { it.copy(defaultCategoryId = v) } }, ::close)
    OptionSheet(sheet == "history", stringResource(R.string.set_history), listOf(0, 3, 5, 10, 20).map { it to if (it == 0) stringResource(R.string.set_off) else stringResource(R.string.set_history_keep, it) }, settings.passwordHistoryLimit, { v -> vm.updateVaultSettings { it.copy(passwordHistoryLimit = v) } }, ::close)
    OptionSheet(sheet == "old", stringResource(R.string.set_old_passwords), listOf(90, 180, 365, 730).map { it to days(it, never) }, settings.oldPasswordDays, { v -> vm.updateVaultSettings { it.copy(oldPasswordDays = v) } }, ::close)
    OptionSheet(sheet == "trash", stringResource(R.string.set_trash_retention), listOf(7, 30, 90, 0).map { it to days(it, stringResource(R.string.set_keep_forever)) }, settings.trashRetentionDays, { v -> vm.updateVaultSettings { it.copy(trashRetentionDays = v) } }, ::close)

    // ---- device name
    var deviceName by rememberSaveable(sheet == "device") { mutableStateOf(vm.deviceName()) }
    GlassSheet(sheet == "device", ::close) {
        Text(stringResource(R.string.set_device_name), style = Type.headline, color = c.text)
        Body(stringResource(R.string.set_device_name_help))
        GlassTextField(deviceName, { deviceName = it.take(40) }, stringResource(R.string.set_device_name))
        GlassButton(stringResource(R.string.action_save), { vm.renameDevice(deviceName); close() }, Modifier.fillMaxWidth(), enabled = deviceName.isNotBlank())
    }

    // ---- change master password
    var cur by rememberSaveable { mutableStateOf("") }
    var new1 by rememberSaveable { mutableStateOf("") }
    var new2 by rememberSaveable { mutableStateOf("") }
    GlassSheet(sheet == "password", onDismiss = { cur = ""; new1 = ""; new2 = ""; vm.clearError(); close() }) {
        Text(stringResource(R.string.set_change_password), style = Type.headline, color = c.text)
        GlassTextField(cur, { cur = it }, stringResource(R.string.set_current_password), secret = true)
        GlassTextField(new1, { new1 = it }, stringResource(R.string.set_new_password), secret = true)
        if (new1.isNotEmpty()) StrengthMeter(new1, Modifier.padding(horizontal = 6.dp))
        GlassTextField(new2, { new2 = it }, stringResource(R.string.set_confirm_password), secret = true)
        Footnote(stringResource(R.string.set_change_password_note))
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.set_change_password), { vm.changeMasterPassword(cur, new1, new2) { cur = ""; new1 = ""; new2 = ""; close() } }, Modifier.fillMaxWidth(), enabled = cur.isNotEmpty() && new1.isNotEmpty() && new2.isNotEmpty())
    }

    // ---- authenticator
    val secret = remember(sheet == "totpOn") { Totp.generateSecret() }
    var code by rememberSaveable(sheet == "totpOn") { mutableStateOf("") }
    GlassSheet(sheet == "totpOn", onDismiss = { vm.clearError(); close() }) {
        Text(stringResource(R.string.set_totp_enable), style = Type.headline, color = c.text)
        SheetList {
            Body(stringResource(R.string.set_totp_step1))
            QrCode(Totp.otpauthUri(secret, "Vault (${vm.deviceName()})"), Modifier.size(200.dp).align(Alignment.CenterHorizontally))
            Footnote(stringResource(R.string.set_totp_manual))
            Text(Totp.formatSecretForDisplay(secret), style = Type.callout.copy(fontFamily = FontFamily.Monospace), color = c.text,
                modifier = Modifier.clickable { copySensitive(context, "secret", secret, prefs.clipboardClearSeconds); host.toast(copied) })
            Body(stringResource(R.string.set_totp_step2))
            GlassTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, stringResource(R.string.twofa_code), mono = true, keyboard = KeyboardType.NumberPassword)
            ErrorText(vm.error)
            GlassButton(stringResource(R.string.twofa_verify), { vm.enableTotp(secret, code) { close() } }, Modifier.fillMaxWidth(), enabled = code.length == 6)
        }
    }
    PasswordSheet(sheet == "totpOff", stringResource(R.string.set_totp_disable), stringResource(R.string.set_totp_note), stringResource(R.string.set_totp_disable), vm, destructive = true, onDismiss = ::close) { pw -> vm.disableTotp(pw) { close() } }
    PasswordSheet(sheet == "codes", stringResource(R.string.set_regenerate_codes), stringResource(R.string.set_recovery_codes_left, security.recoveryCodeHashes.size), stringResource(R.string.set_regenerate_codes), vm, onDismiss = ::close) { pw -> vm.regenerateRecoveryCodes(pw) { close() } }

    // ---- recovery key
    GlassSheet(sheet == "rk", ::close) {
        Text(stringResource(R.string.set_recovery_key), style = Type.headline, color = c.text)
        Body(stringResource(if (vm.hasRecoveryKey()) R.string.settings_recovery_yes else R.string.settings_recovery_no))
        GlassButton(stringResource(if (vm.hasRecoveryKey()) R.string.set_recovery_key_replace else R.string.set_recovery_key_create), { sheet = "rkCreate" }, Modifier.fillMaxWidth())
        if (vm.hasRecoveryKey()) GlassButton(stringResource(R.string.set_recovery_key_remove), { sheet = "rkRemove" }, Modifier.fillMaxWidth(), prominent = false, destructive = true)
    }
    PasswordSheet(sheet == "rkCreate", stringResource(R.string.set_recovery_key_create), stringResource(R.string.set_recovery_key_once), stringResource(R.string.action_done), vm, onDismiss = ::close) { pw -> vm.createRecoveryKey(pw) { close() } }
    PasswordSheet(sheet == "rkRemove", stringResource(R.string.set_recovery_key_remove), null, stringResource(R.string.set_recovery_key_remove), vm, destructive = true, onDismiss = ::close) { pw -> vm.removeRecoveryKey(pw) { close() } }

    // ---- one-time secrets (recovery key / codes)
    val reveal = vm.reveal
    var ack by remember(reveal) { mutableStateOf(false) }
    GlassSheet(reveal != null, onDismiss = { if (ack) vm.reveal = null }) {
        val isKey = reveal?.first == "recoveryKey"
        Text(stringResource(if (isKey) R.string.set_recovery_key else R.string.set_recovery_codes), style = Type.headline, color = c.text)
        Body(stringResource(if (isKey) R.string.set_recovery_key_once else R.string.set_codes_once))
        val text = reveal?.second.orEmpty().joinToString("\n")
        InsetGroup { Text(text, style = Type.body.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.padding(16.dp)) }
        GlassButton(stringResource(R.string.detail_copy), { copySensitive(context, "recovery", text, prefs.clipboardClearSeconds); host.toast(copied) }, Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.ContentCopy)
        InsetGroup { ToggleRow(stringResource(R.string.set_saved_ack), ack, { ack = it }) }
        GlassButton(stringResource(R.string.action_done), { vm.reveal = null }, Modifier.fillMaxWidth(), enabled = ack)
    }

    // ---- background
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { vm.setWallpaperPhoto(uri); close() }
    }
    GlassSheet(sheet == "wallpaper", ::close) {
        Text(stringResource(R.string.set_background), style = Type.headline, color = c.text)
        InsetGroup {
            val ids = Wallpapers.keys.toList()
            ids.forEachIndexed { i, id ->
                val st = Wallpapers.getValue(id)
                ListRow(
                    stringResource(wallpaperLabel(id)),
                    leading = {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(if (c.dark) st.darkBase else st.lightBase) + (if (c.dark) st.darkBlobs else st.lightBlobs).map { it.copy(alpha = 1f) }.take(2).ifEmpty { listOf(if (c.dark) st.darkBase else st.lightBase) }))
                                .border(1.dp, c.separator, RoundedCornerShape(10.dp)),
                        )
                    },
                    onClick = { prefs.updateWallpaper(id); close() },
                ) { CheckMark(prefs.wallpaper == id) }
                if (i != ids.lastIndex) GroupDivider(64.dp)
            }
            if (vm.wallpaperFile().exists()) {
                GroupDivider(64.dp)
                ListRow(stringResource(R.string.bg_photo), leading = { IconTile(Icons.Filled.Image, 34.dp) }, onClick = { prefs.updateWallpaper("photo"); close() }) { CheckMark(prefs.wallpaper == "photo") }
            }
        }
        GlassButton(
            stringResource(R.string.bg_choose_photo),
            { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.AddPhotoAlternate,
        )
        Footnote(stringResource(R.string.bg_photo_note))
    }

    // ---- app update
    GlassSheet(sheet == "update", onDismiss = { vm.clearUpdateStatus(); close() }) {
        UpdateSheetBody(vm)
    }

    // ---- Secret Key (format v2)
    GlassSheet(sheet == "sk", ::close) {
        Text(stringResource(R.string.sk_title), style = Type.headline, color = c.text)
        Body(stringResource(R.string.sk_explain))
        if (vm.hasSecretKeyMode()) {
            GlassButton(stringResource(R.string.sk_show), { sheet = "skShow" }, Modifier.fillMaxWidth(), icon = Icons.Filled.QrCode)
            GlassButton(stringResource(R.string.sk_rotate), { vm.prepareSecretKey(); sheet = "skKit" }, Modifier.fillMaxWidth(), prominent = false)
            GlassButton(stringResource(R.string.sk_turn_off), { sheet = "skOff" }, Modifier.fillMaxWidth(), prominent = false, destructive = true)
        } else if (!vm.hasRecoveryKey()) {
            Footnote(stringResource(R.string.sk_needs_recovery), color = c.danger)
            GlassButton(stringResource(R.string.set_recovery_key_create), { sheet = "rkCreate" }, Modifier.fillMaxWidth())
        } else {
            Footnote(stringResource(R.string.sk_web_warning), color = c.star)
            GlassButton(stringResource(R.string.sk_turn_on), { vm.prepareSecretKey(); sheet = "skKit" }, Modifier.fillMaxWidth())
        }
    }
    // Emergency Kit: show the new key, confirm it was saved, then enable with the master password.
    var last4 by rememberSaveable(sheet == "skKit") { mutableStateOf("") }
    var skPw by rememberSaveable(sheet == "skKit") { mutableStateOf("") }
    GlassSheet(sheet == "skKit", onDismiss = { vm.pendingSecretKey = null; vm.clearError(); close() }) {
        val key = vm.pendingSecretKey ?: return@GlassSheet
        Text(stringResource(R.string.sk_kit_title), style = Type.headline, color = c.text)
        Body(stringResource(R.string.sk_kit_body))
        QrCode(remember(key) { vm.secretKeyQr(key) }, Modifier.size(190.dp).align(Alignment.CenterHorizontally))
        InsetGroup { Text(key, style = Type.body.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.padding(16.dp)) }
        GlassButton(stringResource(R.string.detail_copy), { copySensitive(context, "secret key", key, prefs.clipboardClearSeconds); host.toast(copied) }, Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.ContentCopy)
        GlassTextField(last4, { last4 = it.uppercase().take(4) }, stringResource(R.string.sk_confirm_last4), mono = true)
        GlassTextField(skPw, { skPw = it }, stringResource(R.string.master_password), secret = true)
        ErrorText(vm.error)
        GlassButton(stringResource(R.string.sk_turn_on), { vm.enableSecretKey(skPw) { close() }; skPw = "" }, Modifier.fillMaxWidth(), enabled = skPw.isNotEmpty() && last4 == key.replace("-", "").takeLast(4))
    }
    PasswordSheet(sheet == "skOff", stringResource(R.string.sk_turn_off), stringResource(R.string.sk_explain), stringResource(R.string.sk_turn_off), vm, destructive = true, onDismiss = ::close) { pw -> vm.disableSecretKey(pw) { close() } }
    PasswordSheet(sheet == "skShow", stringResource(R.string.sk_show), null, stringResource(R.string.detail_reveal), vm, onDismiss = ::close) { pw -> vm.revealSecretKey(pw) { close() } }
    val shownSk = vm.shownSecretKey
    GlassSheet(shownSk != null, onDismiss = { vm.shownSecretKey = null }) {
        if (shownSk == null) return@GlassSheet
        Text(stringResource(R.string.sk_title), style = Type.headline, color = c.text)
        Body(stringResource(R.string.sk_kit_body))
        QrCode(remember(shownSk) { vm.secretKeyQr(shownSk) }, Modifier.size(190.dp).align(Alignment.CenterHorizontally))
        InsetGroup { Text(shownSk, style = Type.body.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.padding(16.dp)) }
        GlassButton(stringResource(R.string.action_done), { vm.shownSecretKey = null }, Modifier.fillMaxWidth())
    }

    // ---- biometric
    PasswordSheet(sheet == "bio", stringResource(R.string.settings_biometric), stringResource(R.string.settings_biometric_body), stringResource(R.string.settings_biometric_enable), vm, onDismiss = ::close) { pw ->
        close()
        runCatching { BiometricKey.encryptCipher() }.onSuccess { cipher -> showBiometricPrompt(activity, promptTitle, cancel, cipher) { vm.enableBiometric(it, pw) } }
    }
    GlassAlert(
        visible = sheet == "wipe",
        title = stringResource(R.string.settings_wipe),
        message = stringResource(R.string.settings_wipe_confirm),
        confirm = stringResource(R.string.settings_wipe_yes),
        onConfirm = { close(); vm.wipeDevice() },
        dismiss = stringResource(R.string.cancel),
        onDismiss = ::close,
        destructive = true,
    )
}

// ------------------------------------------------------------------ organize

@Composable
internal fun OrganizeScreen(vm: VaultViewModel) {
    val c = LocalGlassColors.current
    val rev = vm.revision
    val all = remember(rev) { vm.collections() }
    val items = remember(rev) { vm.items() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<VaultCollection?>(null) }
    val kind = if (tab == 0) "category" else "section"
    BackHandler { vm.tab = 3; vm.go(Screen.Vault) }

    // categories in order; folders as a tree
    val rows: List<Pair<VaultCollection, Int>> = remember(all, tab) {
        if (tab == 0) all.filter { it.kind == "category" }.sortedWith(compareBy<VaultCollection> { it.order }.thenBy { it.name }).map { it to 0 }
        else {
            val out = mutableListOf<Pair<VaultCollection, Int>>()
            fun walk(parent: String?, depth: Int) {
                for (f in all.filter { it.kind == "section" && it.parentId == parent }.sortedWith(compareBy<VaultCollection> { it.order }.thenBy { it.name })) {
                    out += f to depth
                    if (depth < 8) walk(f.id, depth + 1)
                }
            }
            walk(null, 0)
            out
        }
    }

    GlassScaffold(
        topBar = {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), { vm.tab = 3; vm.go(Screen.Vault) }, Modifier.align(Alignment.CenterStart))
            GlassIconButton(Icons.Filled.Add, stringResource(if (tab == 0) R.string.org_new_category else R.string.org_new_folder), { editing = Collections.newTemplate(kind, all, System.currentTimeMillis()) }, Modifier.align(Alignment.CenterEnd))
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeTitle(stringResource(R.string.org_title), Modifier.padding(start = 6.dp))
            Box(Modifier.fillMaxWidth().height(46.dp).clip(Capsule).background(c.fill)) {
                FlowingSelector(2, tab, { tab = it }, Modifier.fillMaxSize(), pillColor = c.accent) { i, sel ->
                    Text(stringResource(if (i == 0) R.string.org_categories else R.string.org_folders), style = Type.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (sel) c.onAccent else c.text)
                }
            }
            if (rows.isEmpty()) Body(stringResource(if (tab == 0) R.string.org_empty_cat else R.string.org_empty_folder), Modifier.padding(16.dp))
            else InsetGroup {
                rows.forEachIndexed { i, (col, depth) ->
                    ListRow(
                        col.name,
                        Modifier.padding(start = (depth * 18).dp),
                        subtitle = stringResource(R.string.org_items, Collections.itemCount(col, items)),
                        leading = { IconTile(collectionIcon(col.icon), 36.dp, parseColor(col.color)) },
                        onClick = { editing = col },
                    ) {
                        if (col.pinned) Icon(Icons.Filled.PushPin, null, tint = c.accent, modifier = Modifier.size(16.dp))
                        if (Collections.canMove(col, all, -1)) RowIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.org_move_up), { vm.moveCollection(col, -1) }, tint = c.text2)
                        if (Collections.canMove(col, all, 1)) RowIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.org_move_down), { vm.moveCollection(col, 1) }, tint = c.text2)
                    }
                    if (i != rows.lastIndex) GroupDivider(66.dp)
                }
            }
        }
    }

    CollectionEditor(vm, editing) { editing = it }
}

/**
 * Create / edit a category or folder, and delete it (items are never deleted, only moved).
 * Shared by the Organize screen and the Vault tab's category tiles.
 */
@Composable
internal fun CollectionEditor(vm: VaultViewModel, editing: VaultCollection?, onEditingChange: (VaultCollection?) -> Unit) {
    val c = LocalGlassColors.current
    val rev = vm.revision
    val all = remember(rev) { vm.collections() }
    val items = remember(rev) { vm.items() }
    var deleting by remember { mutableStateOf<VaultCollection?>(null) }
    var moveTo by remember { mutableStateOf<String?>(null) }
    // ---- editor sheet
    val e = editing
    GlassSheet(e != null, onDismiss = { onEditingChange(null) }) {
        if (e == null) return@GlassSheet
        val isNew = all.none { it.id == e.id }
        val duplicate = Collections.isDuplicate(e, all)
        Text(stringResource(if (!isNew) R.string.org_edit else if (e.kind == "category") R.string.org_new_category else R.string.org_new_folder), style = Type.headline, color = c.text)
        SheetList {
            GlassTextField(e.name, { onEditingChange(e.copy(name = it.take(60))) }, stringResource(R.string.org_name), leading = collectionIcon(e.icon))
            if (duplicate) Footnote(stringResource(R.string.org_duplicate), Modifier.padding(start = 14.dp), color = c.danger)
            if (e.kind == "section") {
                val parents = Collections.validParents(e, all)
                var pickParent by remember { mutableStateOf(false) }
                InsetGroup {
                    ValueRow(stringResource(R.string.org_parent), parents.find { it.id == e.parentId }?.name ?: stringResource(R.string.org_top_level)) { pickParent = !pickParent }
                    if (pickParent) {
                        GroupDivider()
                        ListRow(stringResource(R.string.org_top_level), onClick = { onEditingChange(e.copy(parentId = null)); pickParent = false }) { CheckMark(e.parentId == null) }
                        for (p in parents) {
                            GroupDivider()
                            ListRow(p.name, onClick = { onEditingChange(e.copy(parentId = p.id)); pickParent = false }) { CheckMark(e.parentId == p.id) }
                        }
                    }
                }
            }
            Footnote(stringResource(R.string.org_color), Modifier.padding(start = 14.dp))
            FlowGrid(Collections.COLORS.size, 8) { i ->
                val hex = Collections.COLORS[i]
                Box(Modifier.size(32.dp).clip(CircleShape).background(parseColor(hex)).then(if (e.color == hex) Modifier.border(3.dp, c.text, CircleShape) else Modifier).clickable { onEditingChange(e.copy(color = hex)) })
            }
            Footnote(stringResource(R.string.org_icon), Modifier.padding(start = 14.dp))
            FlowGrid(Collections.ICONS.size, 7) { i ->
                val name = Collections.ICONS[i]
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (e.icon == name) c.accent.copy(alpha = 0.25f) else c.field).clickable { onEditingChange(e.copy(icon = name)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(collectionIcon(name), null, tint = if (e.icon == name) c.accent else c.text2, modifier = Modifier.size(22.dp)) }
            }
            InsetGroup { ToggleRow(stringResource(R.string.org_pin), e.pinned, { onEditingChange(e.copy(pinned = it)) }) }
            GlassButton(stringResource(R.string.action_save), { vm.saveCollection(e); onEditingChange(null) }, Modifier.fillMaxWidth(), enabled = e.name.isNotBlank() && !duplicate)
            if (!isNew) GlassButton(stringResource(R.string.org_delete), { onEditingChange(null); moveTo = null; deleting = e }, Modifier.fillMaxWidth(), prominent = false, destructive = true)
        }
    }

    // ---- delete sheet: items are never deleted, only moved
    val d = deleting
    GlassSheet(d != null, onDismiss = { deleting = null }) {
        if (d == null) return@GlassSheet
        val count = Collections.itemCount(d, items)
        Text(stringResource(R.string.org_delete_title, d.name), style = Type.headline, color = c.text)
        Body(if (count == 0) stringResource(R.string.org_delete_empty) else stringResource(R.string.org_delete_body, count))
        if (count > 0) {
            SheetList {
                InsetGroup {
                    ListRow(stringResource(R.string.org_leave), onClick = { moveTo = null }) { CheckMark(moveTo == null) }
                    for (o in all.filter { it.kind == d.kind && it.id != d.id && it.id !in com.damfizz.myvault.core.Items.descendantSectionIds(all, d.id) }) {
                        GroupDivider()
                        ListRow(o.name, onClick = { moveTo = o.id }) { CheckMark(moveTo == o.id) }
                    }
                }
            }
        }
        GlassButton(stringResource(R.string.org_delete), { vm.deleteCollection(d.id, moveTo); deleting = null }, Modifier.fillMaxWidth(), destructive = true)
    }
}

/** Simple fixed-column grid (no lazy layout: used for small palettes inside sheets). */
@Composable
private fun FlowGrid(count: Int, columns: Int, cell: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until (count + columns - 1) / columns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (col in 0 until columns) {
                    val i = row * columns + col
                    if (i < count) cell(i) else Spacer(Modifier.size(32.dp))
                }
            }
        }
    }
}

/** Body of the update sheet: check result, release notes, download progress, install. */
@Composable
internal fun ColumnScope.UpdateSheetBody(vm: VaultViewModel) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val r = vm.update
    val status = vm.updateStatus
    Text(stringResource(R.string.upd_check), style = Type.headline, color = c.text)
    Footnote(stringResource(R.string.upd_current, vm.installedVersion))
    when {
        status == "checking" -> Body(stringResource(R.string.upd_checking))
        r == null && status == "latest" -> Body(stringResource(R.string.upd_latest))
        r == null && status != null -> {
            Footnote(status, color = c.danger)
            TextLink(stringResource(R.string.upd_open_page), { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.damfizz.myvault.data.Updater.RELEASES_PAGE))) } })
        }
        r != null -> {
            Text(stringResource(R.string.upd_available, r.version), style = Type.headline, color = c.accent)
            if (r.notes.isNotBlank()) InsetGroup { Text(r.notes.take(1500), style = Type.footnote, color = c.text2, modifier = Modifier.padding(14.dp)) }
            if (!com.damfizz.myvault.data.Updater.canInstall(context)) {
                Footnote(stringResource(R.string.upd_allow_help))
                GlassButton(stringResource(R.string.upd_allow), { runCatching { context.startActivity(com.damfizz.myvault.data.Updater.allowInstallIntent(context)) } }, Modifier.fillMaxWidth(), prominent = false)
            }
            if (status == "downloading") {
                Body(stringResource(R.string.upd_downloading, (vm.updateProgress * 100).toInt()))
                androidx.compose.material3.LinearProgressIndicator(progress = { vm.updateProgress }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.pill)
            } else {
                if (status != null) Footnote(status, color = c.danger)
                GlassButton(stringResource(R.string.upd_install), vm::downloadAndInstallUpdate, Modifier.fillMaxWidth(), icon = Icons.Filled.SystemUpdate)
                TextLink(stringResource(R.string.upd_open_page), { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.pageUrl))) } })
            }
        }
    }
}

internal fun wallpaperLabel(id: String): Int = when (id) {
    "graphite" -> R.string.bg_graphite
    "ocean" -> R.string.bg_ocean
    "sunset" -> R.string.bg_sunset
    "aurora" -> R.string.bg_aurora
    "plain" -> R.string.bg_plain
    "photo" -> R.string.bg_photo
    else -> R.string.bg_midnight
}
