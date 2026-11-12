package com.damfizz.myvault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damfizz.myvault.R
import com.damfizz.myvault.core.Generator
import com.damfizz.myvault.core.Strength
import com.damfizz.myvault.ui.glass.*

// ------------------------------------------------------------------ strength meter

private fun strengthLabel(label: String): Int = when (label) {
    "weak" -> R.string.strength_weak
    "fair" -> R.string.strength_fair
    "strong" -> R.string.strength_strong
    "veryStrong" -> R.string.strength_veryStrong
    else -> R.string.strength_veryWeak
}

private fun strengthColor(score: Int): Color = when (score) {
    0 -> Color(0xFFE5484D)
    1 -> Color(0xFFF76B15)
    2 -> Color(0xFFF5A524)
    3 -> Color(0xFF46A758)
    else -> Color(0xFF12A594)
}

@Composable
internal fun StrengthMeter(password: String, modifier: Modifier = Modifier) {
    val c = LocalGlassColors.current
    val r = remember(password) { Strength.estimate(password) }
    val fill by animateFloatAsState((r.score + 1) / 5f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "strength")
    val color = strengthColor(r.score)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(Capsule).background(c.pill).drawBehind {
                drawRoundRect(color, size = Size(size.width * fill.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height, size.height))
            },
        )
        Row {
            Text(stringResource(strengthLabel(r.label)), style = Type.footnote.copy(fontWeight = FontWeight.SemiBold), color = color, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.strength_bits, r.bits), style = Type.footnote, color = c.text3)
        }
    }
}

// ------------------------------------------------------------------ generator

@Composable
internal fun GeneratorTab(vm: VaultViewModel, pad: PaddingValues) {
    val c = LocalGlassColors.current
    val context = LocalContext.current
    val host = LocalOverlayHost.current
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var length by rememberSaveable { mutableIntStateOf(20) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var lower by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var ambiguous by rememberSaveable { mutableStateOf(true) }
    var repeats by rememberSaveable { mutableStateOf(true) }
    var words by rememberSaveable { mutableIntStateOf(5) }
    var separator by rememberSaveable { mutableStateOf("-") }
    var capitalize by rememberSaveable { mutableStateOf(true) }
    var number by rememberSaveable { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    var saveSheet by remember { mutableStateOf(false) }
    var pickLogin by remember { mutableStateOf(false) }
    var pickSeparator by remember { mutableStateOf(false) }

    val anyClass = upper || lower || digits || symbols
    val result = remember(nonce, mode, length, upper, lower, digits, symbols, ambiguous, repeats, words, separator, capitalize, number) {
        runCatching {
            if (mode == 0) Generator.password(Generator.PasswordOptions(length, upper, lower, digits, symbols, ambiguous, repeats))
            else Generator.passphrase(Generator.PassphraseOptions(words, separator, capitalize, number))
        }.getOrDefault("")
    }
    val copied = stringResource(R.string.detail_copied)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LargeTitle(stringResource(R.string.gen_title), Modifier.padding(start = 6.dp))
        Body(stringResource(R.string.gen_subtitle), Modifier.padding(horizontal = 6.dp))

        InsetGroup {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    result.ifEmpty { stringResource(R.string.gen_none_selected) },
                    style = Type.title.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp, lineHeight = 30.sp),
                    color = if (result.isEmpty()) c.danger else c.text,
                )
                if (result.isNotEmpty()) StrengthMeter(result)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(stringResource(R.string.gen_regenerate), { nonce++ }, Modifier.weight(1f), prominent = false, icon = Icons.Filled.Refresh)
            GlassButton(stringResource(R.string.detail_copy), { copySensitive(context, "password", result, vm.prefs.clipboardClearSeconds); host.toast(copied) }, Modifier.weight(1f), enabled = result.isNotEmpty(), icon = Icons.Filled.ContentCopy)
        }
        GlassButton(stringResource(R.string.gen_save), { saveSheet = true }, Modifier.fillMaxWidth(), prominent = false, enabled = result.isNotEmpty(), icon = Icons.Filled.Save)

        Box(Modifier.fillMaxWidth().height(46.dp).clip(Capsule).background(c.fill)) {
            FlowingSelector(2, mode, { mode = it }, Modifier.fillMaxSize(), pillColor = c.accent) { i, sel ->
                Text(stringResource(if (i == 0) R.string.gen_password else R.string.gen_passphrase), style = Type.callout.copy(fontWeight = FontWeight.SemiBold), color = if (sel) c.onAccent else c.text)
            }
        }

        val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = c.accent, inactiveTrackColor = c.pill)
        if (mode == 0) {
            InsetGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.gen_length, length), style = Type.body, color = c.text)
                    Slider(length.toFloat(), { length = it.toInt() }, valueRange = Generator.MIN_LENGTH.toFloat()..64f, colors = sliderColors)
                }
                GroupDivider(); ToggleRow(stringResource(R.string.gen_upper), upper, { upper = it })
                GroupDivider(); ToggleRow(stringResource(R.string.gen_lower), lower, { lower = it })
                GroupDivider(); ToggleRow(stringResource(R.string.gen_digits), digits, { digits = it })
                GroupDivider(); ToggleRow(stringResource(R.string.gen_symbols), symbols, { symbols = it })
            }
            InsetGroup {
                ToggleRow(stringResource(R.string.gen_ambiguous), ambiguous, { ambiguous = it }, subtitle = stringResource(R.string.gen_ambiguous_help))
                GroupDivider(); ToggleRow(stringResource(R.string.gen_repeats), repeats, { repeats = it })
            }
            if (!anyClass) Footnote(stringResource(R.string.gen_none_selected), Modifier.padding(horizontal = 16.dp), color = c.danger)
        } else {
            InsetGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.gen_words, words), style = Type.body, color = c.text)
                    Slider(words.toFloat(), { words = it.toInt() }, valueRange = 3f..12f, steps = 8, colors = sliderColors)
                }
                GroupDivider()
                ListRow(stringResource(R.string.gen_separator), onClick = { pickSeparator = true }) {
                    Text(if (separator == " ") "␣" else separator, style = Type.body.copy(fontFamily = FontFamily.Monospace), color = c.text2)
                    Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                }
                GroupDivider(); ToggleRow(stringResource(R.string.gen_capitalize), capitalize, { capitalize = it })
                GroupDivider(); ToggleRow(stringResource(R.string.gen_number), number, { number = it })
            }
        }
        Footnote(stringResource(R.string.gen_wordlist_note), Modifier.padding(horizontal = 16.dp))
    }

    GlassSheet(visible = pickSeparator, onDismiss = { pickSeparator = false }) {
        Text(stringResource(R.string.gen_separator), style = Type.headline, color = c.text)
        InsetGroup {
            val options = listOf("-", " ", ".", "_", ",")
            options.forEachIndexed { i, sep ->
                ListRow(if (sep == " ") "␣  (space)" else sep, onClick = { separator = sep; pickSeparator = false }) { CheckMark(separator == sep) }
                if (i != options.lastIndex) GroupDivider()
            }
        }
    }
    GlassSheet(visible = saveSheet, onDismiss = { saveSheet = false }) {
        Text(stringResource(R.string.gen_save), style = Type.headline, color = c.text)
        InsetGroup {
            ListRow(stringResource(R.string.gen_new_entry), leading = { IconTile(Icons.Filled.Add, 32.dp) }, onClick = { saveSheet = false; vm.newLoginWithPassword(result) })
            GroupDivider(62.dp)
            ListRow(stringResource(R.string.gen_existing), leading = { IconTile(Icons.Filled.SwapHoriz, 32.dp) }, onClick = { saveSheet = false; pickLogin = true })
        }
        Footnote(stringResource(R.string.gen_history_note), Modifier.padding(horizontal = 16.dp))
    }
    GlassSheet(visible = pickLogin, onDismiss = { pickLogin = false }) {
        Text(stringResource(R.string.gen_existing), style = Type.headline, color = c.text)
        val logins = remember(vm.revision, pickLogin) { vm.logins() }
        SheetList {
            InsetGroup {
                logins.forEachIndexed { i, l ->
                    ListRow(l.title, subtitle = l.username ?: l.email, onClick = { pickLogin = false; vm.replacePassword(l.id, result) })
                    if (i != logins.lastIndex) GroupDivider()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ security center

@Composable
internal fun SecurityTab(vm: VaultViewModel, pad: PaddingValues) {
    val c = LocalGlassColors.current
    val report = remember(vm.revision) { vm.securityReport() } ?: return
    var listTitle by remember { mutableStateOf("") }
    var listIds by remember { mutableStateOf<List<String>?>(null) }
    var askBreach by remember { mutableStateOf(false) }
    val scoreColor = when {
        report.score >= 80 -> Color(0xFF46A758)
        report.score >= 50 -> Color(0xFFF5A524)
        else -> Color(0xFFE5484D)
    }
    val sweep by animateFloatAsState(report.score / 100f, spring(dampingRatio = 0.75f, stiffness = 120f), label = "score")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LargeTitle(stringResource(R.string.sec_title), Modifier.padding(start = 6.dp))
        Body(stringResource(R.string.sec_subtitle), Modifier.padding(horizontal = 6.dp))

        InsetGroup {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(96.dp).drawBehind {
                        val stroke = 10.dp.toPx()
                        val inset = stroke / 2
                        val arc = Size(size.width - stroke, size.height - stroke)
                        drawArc(c.pill, 135f, 270f, false, androidx.compose.ui.geometry.Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                        drawArc(scoreColor, 135f, 270f * sweep.coerceIn(0f, 1f), false, androidx.compose.ui.geometry.Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("${report.score}", style = Type.largeTitle.copy(fontSize = 30.sp), color = c.text) }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(
                        stringResource(if (report.score >= 80) R.string.sec_great else if (report.score >= 50) R.string.sec_ok else R.string.sec_bad),
                        style = Type.headline, color = scoreColor,
                    )
                    Text(stringResource(R.string.sec_summary, report.total, report.withPassword), style = Type.footnote, color = c.text2)
                }
            }
        }

        @Composable
        fun Issue(label: Int, icon: ImageVector, ids: List<String>, bad: Boolean) {
            val title = stringResource(label)
            ListRow(
                title,
                leading = { IconTile(icon, 34.dp, if (ids.isEmpty()) Color(0xFF46A758) else if (bad) c.danger else Color(0xFFF5A524)) },
                onClick = if (ids.isEmpty()) null else ({ listTitle = title; listIds = ids }),
            ) {
                Text("${ids.size}", style = Type.headline, color = if (ids.isEmpty()) c.text3 else c.text)
                if (ids.isNotEmpty()) Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
            }
        }
        InsetGroup {
            Issue(R.string.sec_weak, Icons.Filled.Warning, report.weak, true); GroupDivider(64.dp)
            Issue(R.string.sec_reused, Icons.Filled.ContentCopy, report.reused.flatten(), true); GroupDivider(64.dp)
            Issue(R.string.sec_duplicates, Icons.Filled.People, report.duplicates.flatten(), false); GroupDivider(64.dp)
            Issue(R.string.sec_old, Icons.Filled.Schedule, report.old, false)
        }
        InsetGroup {
            Issue(R.string.sec_missing_url, Icons.Filled.Link, report.missingUrl, false); GroupDivider(64.dp)
            Issue(R.string.sec_missing_login, Icons.Filled.Person, report.missingLogin, false); GroupDivider(64.dp)
            Issue(R.string.sec_uncategorized, Icons.Filled.Inbox, report.uncategorized, false)
        }

        SectionHeader(stringResource(R.string.breach_title))
        Footnote(stringResource(R.string.breach_desc), Modifier.padding(horizontal = 16.dp))
        val progress = vm.breachProgress
        val breached = vm.breached
        if (progress != null) {
            Footnote(stringResource(R.string.breach_progress, progress.first, progress.second), Modifier.padding(horizontal = 16.dp), color = c.accent)
        } else {
            GlassButton(stringResource(R.string.breach_start), { askBreach = true }, Modifier.fillMaxWidth(), prominent = false, icon = Icons.Filled.TravelExplore)
        }
        vm.breachError?.let { Footnote(stringResource(R.string.breach_error, it), Modifier.padding(horizontal = 16.dp), color = c.danger) }
        if (breached != null && progress == null) {
            if (breached.isEmpty()) {
                Footnote(stringResource(R.string.breach_none), Modifier.padding(horizontal = 16.dp), color = Color(0xFF46A758))
            } else {
                Footnote(stringResource(R.string.breach_found_desc), Modifier.padding(horizontal = 16.dp), color = c.danger)
                InsetGroup {
                    val rows = breached.entries.mapNotNull { (id, n) -> vm.item(id)?.let { it to n } }.sortedByDescending { it.second }
                    rows.forEachIndexed { i, (item, n) ->
                        ListRow(item.title, subtitle = stringResource(R.string.breach_times, n), leading = { IconTile(Icons.Filled.Warning, 34.dp, c.danger) }, onClick = { vm.go(Screen.Detail(item.id)) }) {
                            Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                        }
                        if (i != rows.lastIndex) GroupDivider(64.dp)
                    }
                }
            }
        }
    }

    GlassSheet(visible = listIds != null, onDismiss = { listIds = null }) {
        Text(listTitle, style = Type.headline, color = c.text)
        val rows = remember(listIds, vm.revision) { listIds.orEmpty().distinct().mapNotNull(vm::item) }
        SheetList {
            InsetGroup {
                rows.forEachIndexed { i, item ->
                    ListRow(item.title, subtitle = item.username ?: item.email, leading = { IconTile(iconFor(item.type), 34.dp) }, onClick = { listIds = null; vm.go(Screen.Detail(item.id)) }) {
                        Icon(Icons.Filled.ChevronRight, null, tint = c.text3, modifier = Modifier.size(22.dp))
                    }
                    if (i != rows.lastIndex) GroupDivider(64.dp)
                }
            }
        }
    }
    GlassAlert(
        visible = askBreach,
        title = stringResource(R.string.breach_confirm_title),
        message = stringResource(R.string.breach_confirm_body),
        confirm = stringResource(R.string.breach_start),
        onConfirm = { askBreach = false; vm.runBreachCheck() },
        dismiss = stringResource(R.string.cancel),
        onDismiss = { askBreach = false },
    )
}
