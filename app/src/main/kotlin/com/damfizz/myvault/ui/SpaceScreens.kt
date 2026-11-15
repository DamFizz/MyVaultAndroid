package com.damfizz.myvault.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damfizz.myvault.R
import com.damfizz.myvault.core.Banks
import com.damfizz.myvault.core.Space
import com.damfizz.myvault.core.SpaceAsset
import com.damfizz.myvault.core.SpaceEntry
import com.damfizz.myvault.core.SpaceType
import com.damfizz.myvault.data.SpaceMedia
import com.damfizz.myvault.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ shared bits

/** Account colours (generic: no bank branding). */
private val SpacePalette = listOf("#2563eb", "#0d9488", "#7c3aed", "#db2777", "#ea580c", "#16a34a", "#475569", "#ca8a04")

private fun parseHex(hex: String?): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

internal fun spaceColor(e: SpaceEntry): Color = parseHex(e.color) ?: parseHex(SpacePalette[(e.id.hashCode() and 0x7fffffff) % SpacePalette.size])!!

/** Share modes, in the order of the selector. */
internal object ShareMode { const val NUMBER = 0; const val QR = 1; const val BOTH = 2 }

/** Generic round badge with the bank's short letters (never a logo). */
@Composable
private fun BankBadge(e: SpaceEntry, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(spaceColor(e)), contentAlignment = Alignment.Center) {
        if (e.type == SpaceType.QR) {
            Icon(Icons.Filled.QrCode2, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
        } else {
            val t = Banks.badge(e).take(4)
            Text(t, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * (if (t.length > 3) 0.26f else 0.32f)).sp, maxLines = 1)
        }
    }
}

/** Decodes an encrypted QR asset off the main thread; the bitmap lives only while shown. */
@Composable
private fun rememberQrBitmap(bytes: ByteArray?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, bytes) {
        value = if (bytes == null) null else withContext(Dispatchers.Default) { SpaceMedia.decode(bytes)?.asImageBitmap() }
    }
    return state.value
}

/**
 * The QR itself: always on white, never under glass, tint or blur. Upscaled without smoothing so the
 * modules stay sharp; downscaled with filtering so they do not alias.
 */
@Composable
private fun QrBox(bytes: ByteArray?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = LocalGlassColors.current
    val bmp = rememberQrBitmap(bytes)
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp)
            .onSizeChanged { box = it },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp == null) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (bytes != null) CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
            }
        } else {
            val upscale = box.width >= bmp.width
            Image(
                bmp,
                contentDescription = stringResource(R.string.space_qr),
                contentScale = ContentScale.Fit,
                filterQuality = if (upscale) FilterQuality.None else FilterQuality.Medium,
                modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height),
            )
        }
    }
}

/** Copy button that briefly turns into a check mark. */
@Composable
private fun CopyChip(label: String, value: String?, vm: VaultViewModel, copiedMessage: String) {
    val context = LocalContext.current
    val host = LocalOverlayHost.current
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { delay(1400); done = false } }
    IconChip(if (done) Icons.Filled.Check else Icons.Filled.ContentCopy, label, selected = done) {
        if (value.isNullOrBlank()) return@IconChip
        copySensitive(context, label, value, vm.prefs.clipboardClearSeconds)
        done = true
        host.toast(copiedMessage)
    }
}

private fun copyNumber(context: android.content.Context, vm: VaultViewModel, e: SpaceEntry, label: String) {
    val n = e.accountNumber ?: return
    copySensitive(context, label, n, vm.prefs.clipboardClearSeconds)
    vm.markUsed(e.id)
}

// ------------------------------------------------------------------ My Space tab

@Composable
internal fun SpaceTab(vm: VaultViewModel, pad: PaddingValues) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val host = LocalOverlayHost.current
    val rev = vm.revision
    val all = remember(rev) { vm.spaceEntries() }
    val query = vm.spaceQuery
    val shown = remember(all, query) { Space.search(all, query) }
    val recent = remember(rev) { vm.recentSpace() }
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var shareFor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var qrFor by remember { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.space_number_copied)
    val numberLabel = stringResource(R.string.space_account_number)

    val onCopy: (SpaceEntry) -> Unit = { e -> copyNumber(context, vm, e, numberLabel); host.toast(copied) }

    LazyColumn(contentPadding = pad, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(start = 6.dp, bottom = 4.dp)) {
                LargeTitle(stringResource(R.string.space_title))
                Footnote(stringResource(R.string.space_tagline), color = c.text2)
            }
        }
        if (all.isEmpty()) {
            item { SpaceEmpty(vm) }
        } else if (query.isNotBlank()) {
            if (shown.isEmpty()) item { Body(stringResource(R.string.space_no_results), Modifier.padding(16.dp)) }
            items(shown, key = { it.id }) { e -> BankCard(vm, e, onCopy, { qrFor = e.id }, { shareFor = e.id to ShareMode.NUMBER }, { actionsFor = e.id }) }
        } else {
            if (recent.isNotEmpty() && all.size > 2) {
                item { SectionHeader(stringResource(R.string.space_recent)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recent, key = { "r-" + it.id }) { e ->
                            IconChip(if (e.type == SpaceType.QR) Icons.Filled.QrCode2 else Icons.Filled.AccountBalance, e.displayName, selected = false) {
                                vm.markUsed(e.id); vm.go(Screen.SpaceDetail(e.id))
                            }
                        }
                    }
                }
            }
            val favs = all.filter { it.favorite }
            val banks = all.filter { !it.favorite && it.type == SpaceType.BANK }
            val qrs = all.filter { !it.favorite && it.type == SpaceType.QR }
            if (favs.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.space_favourites)) }
                items(favs, key = { it.id }) { e -> BankCard(vm, e, onCopy, { qrFor = e.id }, { shareFor = e.id to ShareMode.NUMBER }, { actionsFor = e.id }) }
            }
            if (banks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.space_bank_accounts)) }
                items(banks, key = { it.id }) { e -> BankCard(vm, e, onCopy, { qrFor = e.id }, { shareFor = e.id to ShareMode.NUMBER }, { actionsFor = e.id }) }
            }
            if (qrs.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.space_payment_qrs)) }
                items(qrs, key = { it.id }) { e -> BankCard(vm, e, onCopy, { qrFor = e.id }, { shareFor = e.id to ShareMode.QR }, { actionsFor = e.id }) }
            }
        }
    }

    SpaceActionsSheet(vm, actionsFor?.let { vm.spaceEntry(it) }, onDismiss = { actionsFor = null }, onCopy = onCopy) { id, mode -> actionsFor = null; shareFor = id to mode }
    SpaceShareSheet(vm, shareFor?.first?.let { vm.spaceEntry(it) }, shareFor?.second ?: ShareMode.NUMBER) { shareFor = null }
    val qrEntry = qrFor?.let { vm.spaceEntry(it) }
    QrFullScreen(qrEntry != null, qrEntry?.let { vm.spaceAsset(it.qrAssetId)?.bytes() }, qrEntry?.displayName.orEmpty()) { qrFor = null }
}

@Composable
private fun SpaceEmpty(vm: VaultViewModel) {
    val c = LocalGlassColors.current
    InsetGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Filled.AccountBalanceWallet, 46.dp)
            Text(stringResource(R.string.space_empty_title), style = Type.headline, color = c.text)
            Text(stringResource(R.string.space_empty_body), style = Type.callout, color = c.text2)
            GlassButton(stringResource(R.string.space_add_bank), { vm.go(Screen.SpaceEditor(null, SpaceType.BANK)) }, Modifier.fillMaxWidth(), icon = Icons.Filled.AccountBalance)
            GlassButton(stringResource(R.string.space_add_qr_entry), { vm.go(Screen.SpaceEditor(null, SpaceType.QR)) }, Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.QrCode2)
        }
    }
}

/** "+" on the My Space tab: what to add. */
@Composable
internal fun SpaceAddSheet(vm: VaultViewModel, visible: Boolean, onDismiss: () -> Unit) {
    val c = LocalGlassColors.current
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text(stringResource(R.string.space_add_title), style = Type.headline, color = c.text)
        InsetGroup {
            ListRow(stringResource(R.string.space_add_bank), subtitle = stringResource(R.string.space_add_bank_sub), leading = { IconTile(Icons.Filled.AccountBalance) }, onClick = { onDismiss(); vm.go(Screen.SpaceEditor(null, SpaceType.BANK)) })
            GroupDivider(68.dp)
            ListRow(stringResource(R.string.space_add_qr_entry), subtitle = stringResource(R.string.space_add_qr_sub), leading = { IconTile(Icons.Filled.QrCode2) }, onClick = { onDismiss(); vm.go(Screen.SpaceEditor(null, SpaceType.QR)) })
        }
    }
}

/**
 * Account card: content, not glass (solid fill with a touch highlight), so long lists scroll
 * cheaply. Shows only the last 4 digits. Copy / QR / Share are one tap away.
 */
@Composable
private fun BankCard(
    vm: VaultViewModel,
    e: SpaceEntry,
    onCopy: (SpaceEntry) -> Unit,
    onQr: () -> Unit,
    onShare: () -> Unit,
    onActions: () -> Unit,
) {
    val c = LocalGlassColors.current
    val interaction = rememberGlassInteraction()
    val shape = RoundedCornerShape(26.dp)
    val color = spaceColor(e)
    val hasQr = e.qrAssetId != null
    Column(
        Modifier
            .fillMaxWidth()
            .glassPress(interaction, onLongClick = onActions) { vm.markUsed(e.id); vm.go(Screen.SpaceDetail(e.id)) }
            .pressScale(interaction, 0.02f)
            .clip(shape)
            .background(c.fill)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(color.copy(alpha = if (c.dark) 0.22f else 0.14f), Color.Transparent)))
            .pressGlow(interaction, c.text.copy(alpha = if (c.dark) 0.14f else 0.09f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(e, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.displayName, style = Type.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(e.bankName?.takeIf { it != e.displayName }, e.holder).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = Type.footnote, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.favorite) Icon(Icons.Filled.Star, null, tint = c.star, modifier = Modifier.size(18.dp))
            if (hasQr) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.QrCode2, stringResource(R.string.space_has_qr), tint = c.text2, modifier = Modifier.size(20.dp))
            }
        }
        if (!e.accountNumber.isNullOrBlank()) {
            Text(Space.masked(e.accountNumber), style = Type.title.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp), color = c.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!e.accountNumber.isNullOrBlank()) IconChip(Icons.Filled.ContentCopy, stringResource(R.string.space_copy), selected = false) { onCopy(e) }
            if (hasQr) IconChip(Icons.Filled.QrCode2, stringResource(R.string.space_view_qr), selected = false) { vm.markUsed(e.id); onQr() }
            IconChip(Icons.Filled.IosShare, stringResource(R.string.space_share), selected = false, onClick = onShare)
        }
    }
}

/** Long-press menu: the everyday actions without opening the account. */
@Composable
private fun SpaceActionsSheet(vm: VaultViewModel, e: SpaceEntry?, onDismiss: () -> Unit, onCopy: (SpaceEntry) -> Unit, onShare: (String, Int) -> Unit) {
    val c = LocalGlassColors.current
    GlassSheet(visible = e != null, onDismiss = onDismiss) {
        if (e == null) return@GlassSheet
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(e, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(e.displayName, style = Type.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val hasNumber = !e.accountNumber.isNullOrBlank()
        val hasQr = e.qrAssetId != null
        InsetGroup {
            if (hasNumber) {
                ListRow(stringResource(R.string.space_copy_number), leading = { IconTile(Icons.Filled.ContentCopy, 34.dp) }, onClick = { onCopy(e); onDismiss() })
                GroupDivider(64.dp)
                ListRow(stringResource(R.string.space_share_number), leading = { IconTile(Icons.Filled.Notes, 34.dp) }, onClick = { onShare(e.id, ShareMode.NUMBER) })
                GroupDivider(64.dp)
            }
            if (hasQr) {
                ListRow(stringResource(R.string.space_share_qr), leading = { IconTile(Icons.Filled.QrCode2, 34.dp) }, onClick = { onShare(e.id, ShareMode.QR) })
                GroupDivider(64.dp)
            }
            if (hasNumber && hasQr) {
                ListRow(stringResource(R.string.space_share_both), leading = { IconTile(Icons.Filled.IosShare, 34.dp) }, onClick = { onShare(e.id, ShareMode.BOTH) })
                GroupDivider(64.dp)
            }
            ListRow(stringResource(R.string.action_edit), leading = { IconTile(Icons.Filled.Edit, 34.dp) }, onClick = { onDismiss(); vm.go(Screen.SpaceEditor(e.id, e.type)) })
        }
    }
}

// ------------------------------------------------------------------ QR full screen

/** Large, readable QR on white; screen brightness up while it is shown (helps scanners). */
@Composable
private fun QrFullScreen(visible: Boolean, bytes: ByteArray?, title: String, onDismiss: () -> Unit) {
    GlassOverlay {
        val rm = LocalGlassAccessibility.current.reduceMotion
        val p by animateFloatAsState(if (visible) 1f else 0f, GlassMotion.sheet(rm), label = "qr")
        if (!visible && p < 0.01f) return@GlassOverlay
        if (visible) BackHandler(onBack = onDismiss)
        val activity = LocalContext.current as? Activity
        DisposableEffect(visible) {
            val w = activity?.window
            val before = w?.attributes?.screenBrightness
            if (visible && w != null) w.attributes = w.attributes.apply { screenBrightness = 1f }
            onDispose { if (w != null && before != null) w.attributes = w.attributes.apply { screenBrightness = before } }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = p.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.86f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .graphicsLayer { val s = 0.9f + 0.1f * p; scaleX = s; scaleY = s },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, style = Type.title, color = Color.White, textAlign = TextAlign.Center)
                QrBox(bytes, Modifier.fillMaxWidth())
                Text(stringResource(R.string.space_qr_close_hint), style = Type.footnote, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// ------------------------------------------------------------------ detail

@Composable
internal fun SpaceDetailScreen(vm: VaultViewModel, id: String) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val host = LocalOverlayHost.current
    val scope = rememberCoroutineScope()
    val rev = vm.revision
    val e = remember(rev, id) { vm.spaceEntry(id) }
    val asset = remember(rev, e?.qrAssetId) { vm.spaceAsset(e?.qrAssetId) }
    var revealed by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf<Int?>(null) }
    var qrOpen by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    BackHandler { vm.go(Screen.Vault) }
    LaunchedEffect(revealed) {
        val s = vm.prefs.revealSeconds
        if (revealed && s > 0) { delay(s * 1000L); revealed = false }
    }
    if (e == null) {
        LaunchedEffect(Unit) { vm.go(Screen.Vault) }
        return
    }
    val importErrors = importErrorText()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            try {
                val img = withContext(Dispatchers.IO) { SpaceMedia.import(context, uri) }
                vm.saveSpace(e, img, removeImage = false)
            } catch (x: SpaceMedia.ImportError) {
                host.toast(importErrors(x.reason))
            } finally {
                importing = false
            }
        }
    }
    val numberLabel = stringResource(R.string.space_account_number)
    val copied = stringResource(R.string.space_number_copied)
    val copiedGeneric = stringResource(R.string.detail_copied)

    GlassScaffold(
        topBar = {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), { vm.go(Screen.Vault) }, Modifier.align(Alignment.CenterStart))
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassIconButton(if (e.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.action_favorite), { vm.toggleSpaceFavorite(e.id) }, tint = if (e.favorite) c.star else c.text)
                GlassIconButton(Icons.Filled.Edit, stringResource(R.string.action_edit), { vm.go(Screen.SpaceEditor(e.id, e.type)) })
                GlassIconButton(Icons.Filled.Delete, stringResource(R.string.space_delete), { askDelete = true }, tint = c.danger)
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                BankBadge(e, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.displayName, style = Type.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(e.bankName?.takeIf { it != e.displayName }, accountTypeLabel(e.accountType)).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = Type.callout, color = c.text2)
                }
            }

            val hasNumber = !e.accountNumber.isNullOrBlank()
            if (hasNumber || !e.holder.isNullOrBlank() || !e.duitNowId.isNullOrBlank()) {
                InsetGroup {
                    val holderText = e.holder
                    if (!holderText.isNullOrBlank()) {
                        ListRow(holderText, subtitle = stringResource(R.string.space_holder)) {
                            RowIconButton(Icons.Filled.ContentCopy, stringResource(R.string.space_copy), { copySensitive(context, "holder", holderText, vm.prefs.clipboardClearSeconds); host.toast(copiedGeneric) })
                        }
                    }
                    if (hasNumber) {
                        if (!e.holder.isNullOrBlank()) GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (revealed) Space.grouped(e.accountNumber) else Space.masked(e.accountNumber),
                                    style = Type.title.copy(fontFamily = FontFamily.Monospace, fontSize = 21.sp),
                                    color = c.text,
                                )
                                Text(numberLabel, style = Type.footnote, color = c.text2)
                            }
                            RowIconButton(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (revealed) R.string.detail_hide else R.string.detail_reveal), { revealed = !revealed }, tint = c.text2)
                            RowIconButton(Icons.Filled.ContentCopy, stringResource(R.string.space_copy_number), { copyNumber(context, vm, e, numberLabel); host.toast(copied) })
                        }
                    }
                    val duitNowText = e.duitNowId
                    if (!duitNowText.isNullOrBlank()) {
                        GroupDivider()
                        ListRow(duitNowText, subtitle = stringResource(R.string.space_duitnow)) {
                            RowIconButton(Icons.Filled.ContentCopy, stringResource(R.string.space_copy), { copySensitive(context, "DuitNow", duitNowText, vm.prefs.clipboardClearSeconds); host.toast(copiedGeneric) })
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.space_payment_qr))
            if (asset != null) {
                val bytes = remember(asset.id) { asset.bytes() }
                QrBox(bytes, Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { vm.markUsed(e.id); qrOpen = true }
                if (!asset.intact()) Footnote(stringResource(R.string.space_qr_damaged), Modifier.padding(horizontal = 16.dp), color = c.danger)
                Footnote(stringResource(R.string.space_qr_tap_hint), Modifier.align(Alignment.CenterHorizontally))
            } else {
                InsetGroup {
                    ListRow(
                        stringResource(if (importing) R.string.space_importing else R.string.space_add_qr),
                        subtitle = stringResource(R.string.space_add_qr_hint),
                        leading = { IconTile(Icons.Filled.AddPhotoAlternate, 34.dp) },
                        onClick = {
                            if (!importing) {
                                vm.expectExternalActivity()
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        },
                    )
                }
            }

            val notesText = e.notes
            if (!notesText.isNullOrBlank()) {
                SectionHeader(stringResource(R.string.field_notes))
                InsetGroup { Text(notesText, style = Type.body, color = c.text, modifier = Modifier.padding(16.dp)) }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(stringResource(R.string.space_share), { share = if (hasNumber) ShareMode.NUMBER else ShareMode.QR }, Modifier.weight(1f), icon = Icons.Filled.IosShare)
                if (hasNumber) GlassButton(stringResource(R.string.space_copy), { copyNumber(context, vm, e, numberLabel); host.toast(copied) }, Modifier.weight(1f), prominent = false, icon = Icons.Filled.ContentCopy)
            }
            Footnote(stringResource(R.string.space_share_note), Modifier.padding(horizontal = 8.dp))
        }
    }
    SpaceShareSheet(vm, if (share != null) e else null, share ?: ShareMode.NUMBER) { share = null }
    QrFullScreen(qrOpen, asset?.let { remember(it.id) { it.bytes() } }, e.displayName) { qrOpen = false }
    GlassAlert(
        visible = askDelete,
        title = stringResource(R.string.space_delete_title),
        message = stringResource(R.string.space_delete_body),
        confirm = stringResource(R.string.space_delete),
        onConfirm = { askDelete = false; vm.deleteSpace(e.id); vm.go(Screen.Vault) },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { askDelete = false },
        destructive = true,
    )
}

@Composable
private fun accountTypeLabel(t: String?): String? = when (t) {
    null, "" -> null
    "savings" -> stringResource(R.string.space_type_savings)
    "current" -> stringResource(R.string.space_type_current)
    "other" -> stringResource(R.string.space_type_other)
    else -> t
}

@Composable
private fun importErrorText(): (SpaceMedia.ImportError.Reason) -> String {
    val notImage = stringResource(R.string.space_err_not_image)
    val tooLarge = stringResource(R.string.space_err_too_large)
    val unreadable = stringResource(R.string.space_err_unreadable)
    return { r ->
        when (r) {
            SpaceMedia.ImportError.Reason.NOT_AN_IMAGE -> notImage
            SpaceMedia.ImportError.Reason.TOO_LARGE -> tooLarge
            SpaceMedia.ImportError.Reason.UNREADABLE -> unreadable
        }
    }
}

// ------------------------------------------------------------------ editor

@Composable
internal fun SpaceEditorScreen(vm: VaultViewModel, id: String?, type: String) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val host = LocalOverlayHost.current
    val scope = rememberCoroutineScope()
    val original = remember(id) { id?.let { vm.spaceEntry(it) } }
    val newId = rememberSaveable { com.damfizz.myvault.core.Encoding.randomId() }
    val entryType = original?.type ?: type
    val isBank = entryType == SpaceType.BANK

    var bankId by rememberSaveable { mutableStateOf(original?.bankId) }
    var customBank by rememberSaveable { mutableStateOf(if (original?.bankId == Banks.CUSTOM || (original != null && Banks.byId(original.bankId) == null)) original.bankName.orEmpty() else "") }
    var title by rememberSaveable { mutableStateOf(original?.title.orEmpty()) }
    var holder by rememberSaveable { mutableStateOf(original?.holder.orEmpty()) }
    var number by rememberSaveable { mutableStateOf(original?.accountNumber.orEmpty()) }
    var duitNow by rememberSaveable { mutableStateOf(original?.duitNowId.orEmpty()) }
    var accountType by rememberSaveable { mutableStateOf(original?.accountType) }
    var notes by rememberSaveable { mutableStateOf(original?.notes.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(original?.color ?: SpacePalette[(vm.spaceEntries().size) % SpacePalette.size]) }
    var favorite by rememberSaveable { mutableStateOf(original?.favorite ?: false) }
    var newImage by remember { mutableStateOf<SpaceMedia.Imported?>(null) }
    var removeImage by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var bankSheet by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    val existingAsset = remember(original?.qrAssetId) { vm.spaceAsset(original?.qrAssetId) }
    val existingBytes = remember(existingAsset?.id) { existingAsset?.bytes() }
    val shownBytes = newImage?.bytes ?: existingBytes?.takeIf { !removeImage }
    val importErrors = importErrorText()

    val bankName = when {
        bankId == null -> ""
        bankId == Banks.CUSTOM -> customBank.trim()
        else -> Banks.byId(bankId)?.name ?: customBank.trim()
    }
    val normalized = Space.normalizeAccountNumber(number)
    val dirty = original == null && (bankId != null || title.isNotBlank() || holder.isNotBlank() || number.isNotBlank() || newImage != null || notes.isNotBlank()) ||
        original != null && (bankName != original.bankName.orEmpty() || title != original.title || holder != original.holder.orEmpty() || normalized != original.accountNumber.orEmpty() ||
            duitNow != original.duitNowId.orEmpty() || accountType != original.accountType || notes != original.notes.orEmpty() || color != original.color ||
            favorite != original.favorite || newImage != null || removeImage)

    fun close() {
        if (original != null) vm.go(Screen.SpaceDetail(original.id)) else vm.go(Screen.Vault)
    }
    fun tryClose() { if (dirty) askDiscard = true else close() }
    BackHandler(onBack = ::tryClose)

    val errNeedBank = stringResource(R.string.space_err_need_bank)
    val errNeedNumberOrQr = stringResource(R.string.space_err_need_number_or_qr)
    val errNeedQr = stringResource(R.string.space_err_need_qr)
    fun save() {
        val hasQr = shownBytes != null
        error = when {
            isBank && bankName.isBlank() -> errNeedBank
            isBank && normalized.isEmpty() && !hasQr -> errNeedNumberOrQr
            !isBank && !hasQr -> errNeedQr
            else -> null
        }
        if (error != null) return
        val base = original ?: SpaceEntry(id = newId, type = entryType)
        val entry = base.copy(
            title = title.trim(),
            bankId = if (isBank) bankId else null,
            bankName = if (isBank) bankName else null,
            holder = holder.trim().ifEmpty { null },
            accountNumber = if (isBank) normalized.ifEmpty { null } else null,
            duitNowId = duitNow.trim().ifEmpty { null },
            accountType = accountType,
            notes = notes.trim().ifEmpty { null },
            color = color,
            favorite = favorite,
        )
        val savedId = vm.saveSpace(entry, newImage, removeImage = removeImage && newImage == null) ?: return
        vm.go(Screen.SpaceDetail(savedId))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            try {
                newImage = withContext(Dispatchers.IO) { SpaceMedia.import(context, uri) }
                removeImage = false
            } catch (x: SpaceMedia.ImportError) {
                host.toast(importErrors(x.reason))
            } finally {
                importing = false
            }
        }
    }
    fun pick() {
        if (importing) return
        vm.expectExternalActivity()
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    GlassScaffold(
        topBar = {
            GlassIconButton(Icons.Filled.Close, stringResource(R.string.cancel), ::tryClose, Modifier.align(Alignment.CenterStart))
            GlassIconButton(Icons.Filled.Check, stringResource(R.string.action_save), ::save, Modifier.align(Alignment.CenterEnd), tint = c.accent)
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LargeTitle(
                stringResource(
                    when {
                        original != null -> R.string.space_edit_title
                        isBank -> R.string.space_add_bank
                        else -> R.string.space_add_qr_entry
                    },
                ),
                Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
            ErrorText(error)
            if (isBank) {
                SectionHeader(stringResource(R.string.space_section_account))
                InsetGroup {
                    ValueRow(stringResource(R.string.space_bank), bankName.ifBlank { stringResource(R.string.space_choose_bank) }, Icons.Filled.AccountBalance) { bankSheet = true }
                }
                if (bankId == Banks.CUSTOM) GlassTextField(customBank, { customBank = it.take(60) }, stringResource(R.string.space_custom_bank))
                GlassTextField(title, { title = it.take(60) }, stringResource(R.string.space_nickname))
                GlassTextField(holder, { holder = it.take(80) }, stringResource(R.string.space_holder))
                GlassTextField(number, { number = it.take(40) }, stringResource(R.string.space_account_number), mono = true, keyboard = KeyboardType.Number)
                if (number.isNotEmpty() && normalized != number) Footnote(stringResource(R.string.space_number_saved_as, normalized), Modifier.padding(horizontal = 16.dp))
            } else {
                GlassTextField(title, { title = it.take(60) }, stringResource(R.string.space_qr_name))
            }

            SectionHeader(stringResource(R.string.space_payment_qr))
            if (shownBytes != null) {
                QrBox(shownBytes, Modifier.fillMaxWidth().padding(horizontal = 40.dp))
                newImage?.let { img ->
                    Footnote(
                        stringResource(if (img.qrFound) R.string.space_qr_detected else R.string.space_qr_not_detected),
                        Modifier.padding(horizontal = 16.dp),
                        color = if (img.qrFound) c.text2 else c.danger,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconChip(Icons.Filled.AddPhotoAlternate, stringResource(R.string.space_replace_qr), selected = false) { pick() }
                    val box = newImage?.qrBox
                    val img = newImage
                    if (img != null && box != null && box.width().toLong() * box.height() < img.width.toLong() * img.height * 0.8) {
                        IconChip(Icons.Filled.Crop, stringResource(R.string.space_crop_qr), selected = false) {
                            scope.launch {
                                importing = true
                                newImage = runCatching { withContext(Dispatchers.Default) { SpaceMedia.crop(img, box) } }.getOrDefault(img)
                                importing = false
                            }
                        }
                    }
                    IconChip(Icons.Filled.Delete, stringResource(R.string.space_remove_qr), selected = false) { newImage = null; removeImage = true }
                }
            } else {
                InsetGroup {
                    ListRow(
                        stringResource(if (importing) R.string.space_importing else R.string.space_add_qr),
                        subtitle = stringResource(R.string.space_add_qr_hint),
                        leading = { IconTile(Icons.Filled.AddPhotoAlternate, 34.dp) },
                        onClick = ::pick,
                    )
                }
            }
            if (importing) CircularProgressIndicator(Modifier.size(22.dp).align(Alignment.CenterHorizontally), color = c.accent, strokeWidth = 2.dp)
            Footnote(stringResource(R.string.space_qr_real_only), Modifier.padding(horizontal = 16.dp))

            SectionHeader(stringResource(R.string.space_section_more))
            if (isBank) {
                GlassTextField(duitNow, { duitNow = it.take(60) }, stringResource(R.string.space_duitnow))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    for ((key, label) in listOf("savings" to R.string.space_type_savings, "current" to R.string.space_type_current, "other" to R.string.space_type_other)) {
                        Chip(stringResource(label), accountType == key) { accountType = if (accountType == key) null else key }
                    }
                }
            }
            GlassTextField(notes, { notes = it.take(2000) }, stringResource(R.string.field_notes), multiline = true)
            InsetGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.space_colour), style = Type.body, color = c.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (hex in SpacePalette) {
                            val col = parseHex(hex)!!
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(col)
                                    .then(if (color == hex) Modifier.border(3.dp, c.text, CircleShape) else Modifier)
                                    .clickable { color = hex }
                                    .semantics { contentDescription = hex },
                            )
                        }
                    }
                }
                GroupDivider(16.dp)
                ToggleRow(stringResource(R.string.space_favourite), favorite, { favorite = it })
            }
            Spacer(Modifier.height(4.dp))
            GlassButton(stringResource(R.string.action_save), ::save, Modifier.fillMaxWidth())
        }
    }

    // Bank picker
    GlassSheet(visible = bankSheet, onDismiss = { bankSheet = false }) {
        Text(stringResource(R.string.space_choose_bank), style = Type.headline, color = c.text)
        InsetGroup {
            Banks.ALL.forEachIndexed { i, b ->
                ListRow(b.name, onClick = { bankId = b.id; bankSheet = false }) { CheckMark(bankId == b.id) }
                if (i != Banks.ALL.lastIndex) GroupDivider()
            }
        }
        InsetGroup {
            ListRow(stringResource(R.string.space_other_bank), onClick = { bankId = Banks.CUSTOM; bankSheet = false }) { CheckMark(bankId == Banks.CUSTOM) }
        }
    }
    GlassAlert(
        visible = askDiscard,
        title = stringResource(R.string.discard_title),
        message = stringResource(R.string.discard_message),
        confirm = stringResource(R.string.discard_confirm),
        onConfirm = { askDiscard = false; close() },
        dismiss = stringResource(R.string.space_keep_editing),
        onDismiss = { askDiscard = false },
        destructive = true,
    )
}

// ------------------------------------------------------------------ share sheet

/**
 * Share: number only, QR only, or both (image + text, or one payment-card image). Shows exactly
 * what will be sent, then opens the Android Sharesheet; the user picks the app and the person.
 * Plain copies are made only here, after the tap, in cache/share/, and cleaned up later.
 */
@Composable
internal fun SpaceShareSheet(vm: VaultViewModel, e: SpaceEntry?, initialMode: Int, onDismiss: () -> Unit) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val host = LocalOverlayHost.current
    val scope = rememberCoroutineScope()
    GlassSheet(visible = e != null, onDismiss = onDismiss) {
        if (e == null) return@GlassSheet
        val asset = remember(e.qrAssetId) { vm.spaceAsset(e.qrAssetId) }
        val qrBytes = remember(asset?.id) { asset?.bytes() }
        val hasNumber = !e.accountNumber.isNullOrBlank()
        val hasQr = asset != null
        var mode by remember(e.id, initialMode) {
            mutableIntStateOf(
                when {
                    initialMode == ShareMode.BOTH && hasNumber && hasQr -> ShareMode.BOTH
                    initialMode == ShareMode.QR && hasQr -> ShareMode.QR
                    hasNumber -> ShareMode.NUMBER
                    else -> ShareMode.QR
                },
            )
        }
        var includeBank by remember { mutableStateOf(true) }
        var includeHolder by remember { mutableStateOf(true) }
        var asCard by remember { mutableStateOf(false) }
        var working by remember { mutableStateOf(false) }
        val labels = Space.Labels(stringResource(R.string.space_bank), stringResource(R.string.space_holder), stringResource(R.string.space_account_number))
        val text = Space.shareText(e, Space.ShareOptions(includeBank, includeHolder), labels)
        val title = stringResource(R.string.space_share_title, e.displayName)
        val noApp = stringResource(R.string.space_err_no_app)
        val damaged = stringResource(R.string.space_qr_damaged)
        val cardLabels = SpaceMedia.CardLabels(stringResource(R.string.space_holder), stringResource(R.string.space_account_number), stringResource(R.string.space_card_footer))
        val accent = spaceColor(e).toArgb()
        // The payment card is drawn only when that format is chosen.
        val card by produceState<ByteArray?>(null, e.id, asset?.id, mode, asCard) {
            value = if (mode == ShareMode.BOTH && asCard) withContext(Dispatchers.Default) { SpaceMedia.paymentCard(e, qrBytes, accent, cardLabels) } else null
        }

        Text(stringResource(R.string.space_share), style = Type.headline, color = c.text)
        Text(e.displayName, style = Type.footnote, color = c.text2)
        Box(Modifier.fillMaxWidth().height(46.dp).clip(Capsule).background(c.fill)) {
            FlowingSelector(3, mode, { m ->
                when {
                    m == ShareMode.NUMBER && !hasNumber -> host.toast(context.getString(R.string.space_err_no_number))
                    m != ShareMode.NUMBER && !hasQr -> host.toast(context.getString(R.string.space_err_no_qr))
                    else -> mode = m
                }
            }, Modifier.fillMaxSize(), pillColor = c.accent) { i, sel ->
                Text(
                    stringResource(listOf(R.string.space_mode_number, R.string.space_mode_qr, R.string.space_mode_both)[i]),
                    style = Type.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = if (sel) c.onAccent else c.text,
                    maxLines = 1,
                )
            }
        }

        when (mode) {
            ShareMode.NUMBER -> {
                InsetGroup {
                    ToggleRow(stringResource(R.string.space_include_bank), includeBank, { includeBank = it })
                    GroupDivider(16.dp)
                    ToggleRow(stringResource(R.string.space_include_holder), includeHolder, { includeHolder = it })
                }
                PreviewText(text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(stringResource(R.string.space_share), {
                        vm.markUsed(e.id)
                        if (!SpaceMedia.shareText(context, text, title)) host.toast(noApp)
                    }, Modifier.weight(1f), icon = Icons.Filled.IosShare)
                    GlassButton(stringResource(R.string.space_copy), {
                        copyNumber(context, vm, e, labels.number)
                        host.toast(context.getString(R.string.space_number_copied))
                    }, Modifier.weight(1f), prominent = false)
                }
            }
            ShareMode.QR -> {
                QrBox(qrBytes, Modifier.fillMaxWidth().padding(horizontal = 48.dp))
                GlassButton(stringResource(R.string.space_share_qr), {
                    if (working) return@GlassButton
                    if (asset?.intact() != true) { host.toast(damaged); return@GlassButton }
                    vm.markUsed(e.id)
                    scope.launch {
                        working = true
                        val uri = withContext(Dispatchers.IO) { SpaceMedia.shareFile(context, qrBytes!!, "payment-qr.${SpaceMedia.extFor(asset.mime)}") }
                        if (!SpaceMedia.shareImage(context, uri, asset.mime, title)) host.toast(noApp)
                        working = false
                    }
                }, Modifier.fillMaxWidth(), icon = Icons.Filled.IosShare)
            }
            else -> {
                Box(Modifier.fillMaxWidth().height(40.dp).clip(Capsule).background(c.fill)) {
                    FlowingSelector(2, if (asCard) 1 else 0, { asCard = it == 1 }, Modifier.fillMaxSize()) { i, sel ->
                        Text(
                            stringResource(if (i == 0) R.string.space_both_image_text else R.string.space_both_card),
                            style = Type.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = if (sel) c.text else c.text2,
                            maxLines = 1,
                        )
                    }
                }
                if (!asCard) {
                    QrBox(qrBytes, Modifier.fillMaxWidth().padding(horizontal = 72.dp))
                    InsetGroup {
                        ToggleRow(stringResource(R.string.space_include_bank), includeBank, { includeBank = it })
                        GroupDivider(16.dp)
                        ToggleRow(stringResource(R.string.space_include_holder), includeHolder, { includeHolder = it })
                    }
                    PreviewText(text)
                    Footnote(stringResource(R.string.space_both_hint), Modifier.padding(horizontal = 8.dp))
                } else {
                    val bmp = rememberQrBitmap(card)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp).clip(RoundedCornerShape(18.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        if (bmp != null) {
                            Image(bmp, stringResource(R.string.space_both_card), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height))
                        } else {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.7f), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp) }
                        }
                    }
                    Footnote(stringResource(R.string.space_card_hint), Modifier.padding(horizontal = 8.dp))
                }
                GlassButton(stringResource(R.string.space_share), {
                    if (working) return@GlassButton
                    if (asset?.intact() != true) { host.toast(damaged); return@GlassButton }
                    vm.markUsed(e.id)
                    scope.launch {
                        working = true
                        val ok = if (asCard) {
                            val bytes = card ?: withContext(Dispatchers.Default) { SpaceMedia.paymentCard(e, qrBytes, accent, cardLabels) }
                            val uri = withContext(Dispatchers.IO) { SpaceMedia.shareFile(context, bytes, "payment-details.png") }
                            SpaceMedia.shareImage(context, uri, "image/png", title)
                        } else {
                            val uri = withContext(Dispatchers.IO) { SpaceMedia.shareFile(context, qrBytes!!, "payment-qr.${SpaceMedia.extFor(asset.mime)}") }
                            SpaceMedia.shareImage(context, uri, asset.mime, title, text)
                        }
                        if (!ok) host.toast(noApp)
                        working = false
                    }
                }, Modifier.fillMaxWidth(), icon = Icons.Filled.IosShare)
            }
        }
        Footnote(stringResource(R.string.space_share_note), Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun PreviewText(text: String) {
    val c = LocalGlassColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.field).padding(14.dp)) {
        Text(stringResource(R.string.space_preview), style = Type.caption, color = c.text3)
        Spacer(Modifier.height(6.dp))
        Text(text, style = Type.callout.copy(fontFamily = FontFamily.Monospace), color = c.text)
    }
}
