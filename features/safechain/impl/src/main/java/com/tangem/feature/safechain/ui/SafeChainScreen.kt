package com.tangem.feature.safechain.ui

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangem.core.ui.R
import com.tangem.core.ui.components.PrimaryButton
import com.tangem.core.ui.components.SecondaryButton
import com.tangem.core.ui.components.TextButton
import com.tangem.core.ui.components.appbar.AppBarWithBackButton
import com.tangem.core.ui.components.rememberQrPainter
import com.tangem.core.ui.res.TangemTheme
import com.tangem.core.ui.utils.WindowInsetsZero
import com.tangem.feature.safechain.chain.SepoliaConfig
import com.tangem.feature.safechain.chain.groupedAddress

// Status colours that read well on both themes.
private val Green = Color(0xFF1E9E5A)
private val GreenBg = Color(0x1F1E9E5A)
private val Red = Color(0xFFE5484D)
private val RedBg = Color(0x1FE5484D)
private val Amber = Color(0xFFD9930D)
private val AmberBg = Color(0x1FD9930D)

private val CardShape = RoundedCornerShape(20.dp)

@Composable
internal fun SafeChainScreen(state: SafeChainUM, actions: SafeChainActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(TangemTheme.colors.background.secondary)) {
        AppBarWithBackButton(onBackClick = actions::onBack, text = "Protected Savings")
        // The app draws edge to edge, so the keyboard doesn't resize the window: keep the setup fields above it.
        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HealthBanner(state.health, state.loadError) }
            val vault = state.vault
            when {
                state.loading && vault == null -> item { Loading() }
                vault != null -> {
                    item { Summary(state, vault) }
                    item { Actions(vault, actions) }
                    vault.alerts.forEach { a -> item(key = a) { Alert(a) } }
                    if (vault.pending.isNotEmpty()) {
                        item { SectionTitle("On its way out") }
                        vault.pending.forEach { w -> item(key = "w${w.id}") { PendingRow(w, actions) } }
                    }
                    item { SectionTitle("Activity") }
                    item { ActivityList(vault.activity) }
                    item { SectionTitle("Security") }
                    item { SecurityCard(vault, state.accountAddress) }
                }
                state.accountAddress != null -> item { Setup(state, actions) }
            }
            item { Footer(state) }
        }
    }
    Sheet(state.sheet, actions)
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(CardShape)
            .background(TangemTheme.colors.background.primary)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text = text,
    style = TangemTheme.typography.subtitle2,
    color = TangemTheme.colors.text.tertiary,
    modifier = Modifier.padding(start = 28.dp, top = 4.dp),
)

@Composable
private fun Chip(text: String, fg: Color, bg: Color, @DrawableRes icon: Int? = null) {
    Row(
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon?.let { Icon(painterResource(it), null, tint = fg, modifier = Modifier.size(14.dp)) }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Mono(text: String, color: Color = TangemTheme.colors.text.secondary) = Text(
    text = text,
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = color,
)

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = TangemTheme.colors.icon.primary1)
}

@Composable
private fun HealthBanner(health: HealthUM, loadError: String?) {
    val (text, fg, bg) = when {
        loadError != null -> Triple(loadError, Red, RedBg)
        health is HealthUM.Disagree -> Triple(
            "Your two Sepolia data sources disagree (${health.text}). Check an independent explorer before trusting this screen.",
            Red, RedBg,
        )
        health is HealthUM.Degraded -> Triple("One Sepolia data source isn't answering, so this isn't fully cross-checked.", Amber, AmberBg)
        else -> return
    }
    Text(
        text = text,
        color = fg,
        style = TangemTheme.typography.body2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CardShape).background(bg).padding(14.dp),
    )
}

// ------------------------------------------------------------------ vault

@Composable
private fun Summary(state: SafeChainUM, v: VaultUM) = Card {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Savings", style = TangemTheme.typography.subtitle2, color = TangemTheme.colors.text.tertiary)
        Spacer(Modifier.weight(1f))
        if (v.locked) Chip("Locked", Red, RedBg, R.drawable.ic_lock_24) else Chip(v.protection, Green, GreenBg, R.drawable.ic_shield_24)
    }
    Text(v.balance, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = TangemTheme.colors.text.primary1)
    Text(
        "Every withdrawal waits ${v.protection.substringAfter("· ")} and can be cancelled until then.",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    Spacer(Modifier.height(2.dp))
    Row {
        Column(Modifier.weight(1f)) {
            Text("Spending (card)", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            Text(state.spendingBalance, style = TangemTheme.typography.body1, color = TangemTheme.colors.text.primary1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Gas", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            Text(state.gasBalance, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
        }
    }
}

@Composable
private fun Actions(v: VaultUM, actions: SafeChainActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("Add", R.drawable.ic_plus_24, enabled = v.isPrimary, onClick = actions::onOpenAddFunds, modifier = Modifier.weight(1f))
        ActionButton("Withdraw", R.drawable.ic_arrow_up_24, enabled = v.isPrimary && !v.locked, onClick = actions::onOpenWithdraw, modifier = Modifier.weight(1f))
        ActionButton("Lock", R.drawable.ic_lock_24, enabled = !v.locked, onClick = actions::onLock, modifier = Modifier.weight(1f), danger = true)
    }
}

@Composable
private fun ActionButton(label: String, @DrawableRes icon: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, danger: Boolean = false) {
    val fg = when {
        !enabled -> TangemTheme.colors.text.disabled
        danger -> Red
        else -> TangemTheme.colors.text.primary1
    }
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(TangemTheme.colors.background.primary)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (danger && enabled) RedBg else TangemTheme.colors.background.secondary),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(22.dp)) }
        Text(label, color = fg, style = TangemTheme.typography.button)
    }
}

@Composable
private fun Alert(text: String) = Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CardShape).background(RedBg).padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    Icon(painterResource(R.drawable.ic_warning_20), null, tint = Red, modifier = Modifier.size(20.dp))
    Text(text, color = Red, style = TangemTheme.typography.body2)
}

@Composable
private fun PendingRow(w: WithdrawalUM, actions: SafeChainActions) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.amount, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
            Text(
                if (w.toHaven) "to your safe haven" else "to ${w.to.take(6)}…${w.to.takeLast(4)}",
                style = TangemTheme.typography.body2,
                color = TangemTheme.colors.text.secondary,
            )
        }
        when {
            w.isFinal -> Chip("Final", TangemTheme.colors.text.secondary, TangemTheme.colors.background.secondary)
            w.countdown != null -> Chip(w.countdown, Amber, AmberBg, R.drawable.ic_clock_24)
        }
    }
    if (w.disputed) Text("⚠ Your data sources disagree about this one", color = Red, style = TangemTheme.typography.caption2)
    if (!w.isFinal) Text("Final at ${w.deadline}", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
    if (w.canCancel) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RedBg)
                .clickable { actions.onCancelWithdrawal(w.id) }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_close_24), null, tint = Red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cancel withdrawal", color = Red, style = TangemTheme.typography.button)
        }
    }
    if (w.canRelease) SecondaryButton(text = "Release", onClick = { actions.onReleaseWithdrawal(w.id) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ActivityList(items: List<ActivityUM>) = Card {
    if (items.isEmpty()) {
        Text("Nothing yet.", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.tertiary)
    }
    val uri = LocalUriHandler.current
    items.forEachIndexed { i, a ->
        Row(
            modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(a.url) } }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val color = when (a.tone) {
                ActivityUM.Tone.Good -> Green
                ActivityUM.Tone.Warning -> Amber
                ActivityUM.Tone.Danger -> Red
                ActivityUM.Tone.Neutral -> TangemTheme.colors.icon.informative
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1)
                if (a.detail.isNotBlank()) Text(a.detail, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            }
            Text(a.time, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
        }
        if (i < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(TangemTheme.colors.stroke.primary))
    }
}

@Composable
private fun SecurityCard(v: VaultUM, me: String?) = Card {
    KeyRow("Spending key", "This card", me)
    KeyRow("Recovery key", if (v.isRecovery) "This card" else "Your other device", v.recovery)
    KeyRow("Safe haven", if (v.haven == null) "Not set" else "Set", v.haven)
    KeyRow("Vault", "On ${SepoliaConfig.NAME}", v.address)
}

@Composable
private fun KeyRow(label: String, value: String, address: String?) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clickable(enabled = address != null) { address?.let { clipboard.setText(AnnotatedString(it)) } }) {
        Row {
            Text(label, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.tertiary, modifier = Modifier.weight(1f))
            Text(value, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1)
        }
        address?.let { Mono(groupedAddress(it), TangemTheme.colors.text.tertiary) }
    }
}

@Composable
private fun Footer(state: SafeChainUM) = Text(
    text = "${SepoliaConfig.NAME} test network · test tokens only · " +
        (if (state.health is HealthUM.Agree) "verified by 2 independent data sources · " else "") +
        "the contracts enforce every rule",
    style = TangemTheme.typography.caption2,
    color = TangemTheme.colors.text.tertiary,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp).navigationBarsPadding(),
)

// ------------------------------------------------------------------ setup

@Composable
private fun Setup(state: SafeChainUM, actions: SafeChainActions) = Card {
    val s = state.setup
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Icon(painterResource(R.drawable.ic_shield_24), null, tint = Green, modifier = Modifier.size(36.dp))
    Text("Protect your savings", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TangemTheme.colors.text.primary1)
    Text(
        "Money in Protected Savings can only leave after a waiting period, and you can cancel any withdrawal until then. " +
            "Your card is the spending key; a second key on another device can also cancel and lock.",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )

    Step(1, "Your recovery key")
    AddressField(
        value = s.recovery,
        label = "Recovery key address (another device)",
        onChange = actions::onSetupRecoveryChange,
        onPaste = { clipboard.getText()?.text?.let(actions::onSetupRecoveryChange) },
        onScan = actions::onScanRecovery,
    )

    Step(2, "Waiting period")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("24" to "1 day", "72" to "3 days", "168" to "7 days").forEach { (h, label) ->
            val on = s.delayHours == h
            Text(
                label,
                color = if (on) TangemTheme.colors.text.primary2 else TangemTheme.colors.text.primary1,
                style = TangemTheme.typography.button,
                modifier = Modifier.clip(CircleShape)
                    .background(if (on) TangemTheme.colors.button.primary else TangemTheme.colors.background.secondary)
                    .clickable { actions.onSetupDelayChange(h) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    Step(3, "Confirm with the recovery key")
    if (s.consentLink == null) {
        PrimaryButton(text = "Continue", onClick = actions::onSetupMakeLink, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            "Open this on the device with your recovery key and sign. It's free and only says \"I agree to protect this card's savings\".",
            style = TangemTheme.typography.body2,
            color = TangemTheme.colors.text.secondary,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painter = rememberQrPainter(s.consentLink, size = 220.dp, padding = 8.dp),
                contentDescription = "Consent link QR code",
                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, TangemTheme.colors.stroke.primary, RoundedCornerShape(16.dp)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "Share link",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.consentLink)
                    context.startActivity(Intent.createChooser(send, "Send to your recovery device"))
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(text = "Copy", onClick = { clipboard.setText(AnnotatedString(s.consentLink)) }, modifier = Modifier.weight(1f))
        }

        Step(4, "Bring back the signature")
        AddressField(
            value = s.signature,
            label = "Recovery key's signature",
            onChange = actions::onSetupSignatureChange,
            onPaste = { clipboard.getText()?.text?.let(actions::onSetupSignatureChange) },
            onScan = actions::onScanSignature,
        )
        PrimaryButton(text = "Create Protected Savings", onClick = actions::onSetupCreate, modifier = Modifier.fillMaxWidth())
    }
    s.error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
}

@Composable
private fun Step(n: Int, title: String) = Row(
    modifier = Modifier.padding(top = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(TangemTheme.colors.button.primary), contentAlignment = Alignment.Center) {
        Text("$n", color = TangemTheme.colors.text.primary2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Text(title, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
}

@Composable
private fun AddressField(value: String, label: String, onChange: (String) -> Unit, onPaste: () -> Unit, onScan: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = TangemTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            Row {
                IconButton(onClick = onPaste) { Icon(painterResource(R.drawable.ic_copy_24), "Paste", tint = TangemTheme.colors.icon.informative) }
                IconButton(onClick = onScan) { Icon(painterResource(R.drawable.ic_qrcode_scan_24), "Scan", tint = TangemTheme.colors.icon.informative) }
            }
        },
    )
}

// ------------------------------------------------------------------ sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(sheet: SheetUM, actions: SafeChainActions) {
    if (sheet == SheetUM.None) return
    val canDismiss = sheet !is SheetUM.Working
    ModalBottomSheet(
        onDismissRequest = { if (canDismiss) actions.onCloseSheet() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { canDismiss }),
        containerColor = TangemTheme.colors.background.primary,
        // Insets are handled below, as Tangem's own sheets do: the app draws edge to edge, so without this the
        // keyboard covers the lower fields (the destination address) instead of pushing them up.
        contentWindowInsets = { WindowInsetsZero },
    ) {
        // Keyboard padding outside the scroll, so the viewport ends above the keyboard and the focused field
        // is scrolled into view.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (sheet) {
                is SheetUM.AddFunds -> AmountSheet("Add to Savings", "On your card: ${sheet.max}", sheet.amount, sheet.error, null, actions)
                is SheetUM.Withdraw -> AmountSheet("Withdraw", "Available: ${sheet.max}", sheet.amount, sheet.error, sheet.to, actions)
                is SheetUM.Review -> ReviewSheet(sheet, actions)
                is SheetUM.Working -> WorkingSheet(sheet)
                is SheetUM.Result -> ResultSheet(sheet, actions)
                SheetUM.None -> Unit
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, color: Color = TangemTheme.colors.text.primary1) =
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color)

@Composable
private fun AmountSheet(title: String, hint: String, amount: String, error: String?, destination: String?, actions: SafeChainActions) {
    val clipboard = LocalClipboardManager.current
    SheetTitle(title)
    OutlinedTextField(
        value = amount,
        onValueChange = actions::onAmountChange,
        label = { Text("Amount") },
        suffix = { Text(SepoliaConfig.TOKEN_SYMBOL) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        textStyle = TangemTheme.typography.h3,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(hint, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary, modifier = Modifier.weight(1f))
        TextButton(text = "Max", onClick = actions::onMax)
    }
    if (destination != null) {
        AddressField(
            value = destination,
            label = "To address",
            onChange = actions::onDestinationChange,
            onPaste = { clipboard.getText()?.text?.let(actions::onDestinationChange) },
            onScan = actions::onScanDestination,
        )
        Text(
            "It waits out the protection period first. You can cancel it until then.",
            style = TangemTheme.typography.caption2,
            color = TangemTheme.colors.text.tertiary,
        )
    }
    error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
    PrimaryButton(text = "Continue", onClick = actions::onContinue, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ReviewSheet(r: SheetUM.Review, actions: SafeChainActions) {
    r.step?.let { Text(it, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary) }
    SheetTitle(r.title, if (r.danger) Red else TangemTheme.colors.text.primary1)
    Text(
        "Your card signs exactly this. It has no screen, so check it here.",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(TangemTheme.colors.background.secondary).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            r.rows.forEach { (label, value) ->
                Column {
                    Text(label, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
                    if (value.startsWith("0x ") || value.contains("\n0x ")) {
                        value.split("\n").forEach { line ->
                            if (line.startsWith("0x ")) Mono(line, TangemTheme.colors.text.primary1) else Text(line, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1)
                        }
                    } else {
                        Text(value, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1)
                    }
                }
            }
        }
    }
    r.note?.let { Text(it, style = TangemTheme.typography.body2, color = if (r.danger) Red else TangemTheme.colors.text.secondary) }
    Text(r.fee, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
    PrimaryButton(text = "Sign with card", onClick = actions::onSign, modifier = Modifier.fillMaxWidth())
    SecondaryButton(text = "Not now", onClick = actions::onCloseSheet, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun WorkingSheet(w: SheetUM.Working) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (w.holdCard) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(TangemTheme.colors.background.secondary), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_scan_24), null, tint = TangemTheme.colors.icon.primary1, modifier = Modifier.size(40.dp))
            }
        } else {
            CircularProgressIndicator(color = TangemTheme.colors.icon.primary1, modifier = Modifier.size(48.dp))
        }
        Text(w.title, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1, textAlign = TextAlign.Center)
        Text(w.text, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ResultSheet(r: SheetUM.Result, actions: SafeChainActions) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(if (r.ok) GreenBg else RedBg), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (r.ok) R.drawable.ic_check_24 else R.drawable.ic_close_24),
                null,
                tint = if (r.ok) Green else Red,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(r.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TangemTheme.colors.text.primary1, textAlign = TextAlign.Center)
        Text(r.text, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary, textAlign = TextAlign.Center)
        r.url?.let { TextButton(text = "View on explorer", onClick = { runCatching { uri.openUri(it) } }) }
    }
    PrimaryButton(text = "Done", onClick = actions::onCloseSheet, modifier = Modifier.fillMaxWidth())
}
