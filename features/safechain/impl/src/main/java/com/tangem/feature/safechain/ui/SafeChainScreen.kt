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
                    state.previous.forEach { p -> item(key = "prev${p.address}") { PreviousVault(p, actions) } }
                    item { Summary(state, vault, actions) }
                    item { Actions(vault, actions) }
                    vault.alerts.forEach { a -> item(key = a) { Alert(a) } }
                    vault.backupChange?.let { b -> item { BackupChange(b, actions) } }
                    if (vault.pending.isNotEmpty()) {
                        item { SectionTitle("Sending") }
                        vault.pending.forEach { w -> item(key = "w${w.id}") { PendingRow(w, actions) } }
                    }
                    item { SectionTitle("Activity") }
                    item { ActivityList(vault.activity) }
                    item { SectionTitle("Security") }
                    item { SecurityCard(vault, state, actions) }
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
private fun Summary(state: SafeChainUM, v: VaultUM, actions: SafeChainActions) = Card {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Protected balance", style = TangemTheme.typography.subtitle2, color = TangemTheme.colors.text.tertiary)
        Spacer(Modifier.weight(1f))
        if (v.locked) Chip("Locked", Red, RedBg, R.drawable.ic_lock_24) else Chip("Protected · ${v.delay}", Green, GreenBg, R.drawable.ic_shield_24)
    }
    Text(v.balance, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = TangemTheme.colors.text.primary1)
    Text(
        if (v.locked) {
            "Locked: nothing can be sent until the backup key unlocks it."
        } else {
            "Every payment waits ${v.delay}, and you can cancel it until then. Cancelled money comes straight back here."
        },
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    if (v.locked && v.backupIsThisPhone) {
        PrimaryButton(text = "Unlock with this phone", onClick = actions::onUnlock, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(2.dp))
    Row {
        Column(Modifier.weight(1f)) {
            Text("Still on your card", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            Text(state.cardBalance, style = TangemTheme.typography.body1, color = TangemTheme.colors.text.primary1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Network fees", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            Text(state.gasBalance, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
        }
    }
}

@Composable
private fun Actions(v: VaultUM, actions: SafeChainActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("Send", R.drawable.ic_arrow_up_24, enabled = v.isPrimary && !v.locked, onClick = actions::onOpenWithdraw, modifier = Modifier.weight(1f))
        ActionButton("Receive", R.drawable.ic_arrow_down_24, enabled = true, onClick = actions::onOpenReceive, modifier = Modifier.weight(1f))
        ActionButton("Add", R.drawable.ic_plus_24, enabled = v.isPrimary, onClick = actions::onOpenAddFunds, modifier = Modifier.weight(1f))
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
            Text("Cancel · money comes back", color = Red, style = TangemTheme.typography.button)
        }
    }
    if (w.canRelease) SecondaryButton(text = "Deliver now", onClick = { actions.onReleaseWithdrawal(w.id) }, modifier = Modifier.fillMaxWidth())
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
private fun SecurityCard(v: VaultUM, state: SafeChainUM, actions: SafeChainActions) = Card {
    KeyRow("Spends", "This card", state.accountAddress)
    KeyRow("Backup key", if (v.backupIsThisPhone) "This phone · fingerprint" else "Another wallet", v.recovery)
    if (v.backupIsThisPhone) {
        Text(
            "Emergency fees on this phone: ${state.phoneKeyGas}",
            style = TangemTheme.typography.caption2,
            color = TangemTheme.colors.text.tertiary,
        )
    }
    KeyRow("Safe haven", if (v.haven == null) "Not set" else "Set", v.haven)
    KeyRow("Savings address", "On ${SepoliaConfig.NAME}", v.address)
    when {
        v.locked -> Unit
        v.backupIsThisPhone -> DangerRow("Lost your card? Lock with this phone", R.drawable.ic_fingerprint_24, actions::onEmergencyLock)
        v.isPrimary -> DangerRow("Lock now (card)", R.drawable.ic_lock_24, actions::onLock)
    }
    if (v.haven != null && v.isPrimary) SecondaryButton(text = "Move everything to your safe haven", onClick = actions::onMoveToHaven, modifier = Modifier.fillMaxWidth())
    if (v.backupIsThisPhone && v.backupChange == null && v.isPrimary) {
        TextButton(text = "Use another wallet as the backup instead", onClick = actions::onOpenOtherWallet)
    }
    if (!v.backupIsThisPhone && v.isPrimary) {
        Text(
            "Can't use that backup any more, or want this phone as the backup? Start a new Savings protected by this phone and move your money there.",
            style = TangemTheme.typography.caption2,
            color = TangemTheme.colors.text.tertiary,
        )
        SecondaryButton(text = "New Savings with this phone as backup", onClick = actions::onMoveToNewVault, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DangerRow(text: String, @DrawableRes icon: Int, onClick: () -> Unit) = Row(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RedBg).clickable(onClick = onClick).padding(vertical = 12.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(painterResource(icon), null, tint = Red, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, color = Red, style = TangemTheme.typography.button)
}

@Composable
private fun BackupChange(b: BackupChangeUM, actions: SafeChainActions) = Card {
    Text("Backup key change", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
    Text("New backup key", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
    Mono(groupedAddress(b.newBackup), TangemTheme.colors.text.primary1)
    when {
        !b.approved && b.canApproveFromPhone -> {
            Text("Waiting for this phone to approve.", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
            PrimaryButton(text = "Approve with fingerprint", onClick = actions::onApproveBackupChange, modifier = Modifier.fillMaxWidth())
        }
        !b.approved -> Text("Waiting for the other key to approve.", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
        b.canFinish -> PrimaryButton(text = "Finish", onClick = actions::onFinishBackupChange, modifier = Modifier.fillMaxWidth())
        else -> Text(b.waitText.orEmpty(), style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
    }
    SecondaryButton(text = "Cancel this change", onClick = actions::onCancelBackupChange, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PreviousVault(p: PreviousVaultUM, actions: SafeChainActions) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Previous Savings", style = TangemTheme.typography.subtitle2, color = TangemTheme.colors.text.tertiary)
            Text(p.balance, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        }
        Mono("${p.address.take(6)}…${p.address.takeLast(4)}", TangemTheme.colors.text.tertiary)
    }
    p.moving.forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Moving ${w.amount}", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1, modifier = Modifier.weight(1f))
            when {
                w.isFinal -> Chip("Ready", Green, GreenBg)
                w.countdown != null -> Chip(w.countdown, Amber, AmberBg, R.drawable.ic_clock_24)
            }
        }
        if (w.canRelease) PrimaryButton(text = "Bring it here now", onClick = { actions.onReleaseFromPrevious(p.address, w.id) }, modifier = Modifier.fillMaxWidth())
    }
    if (p.canMove && p.moving.isEmpty()) {
        Text("Move it into your current Savings. It waits out the old protection period first.", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
        PrimaryButton(text = "Move ${p.available} here", onClick = { actions.onMoveFromPrevious(p.address) }, modifier = Modifier.fillMaxWidth())
    }
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
    Icon(painterResource(R.drawable.ic_shield_24), null, tint = Green, modifier = Modifier.size(36.dp))
    Text("Protect your savings", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TangemTheme.colors.text.primary1)
    Text(
        "Every payment waits before it leaves, and you can cancel it until then: the money comes straight back. " +
            "Your card spends. " +
            if (s.otherWallet) "Another wallet you own is the backup key." else "This phone is the backup key: if your card is ever lost or stolen, your fingerprint locks everything.",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )

    Text("Waiting period", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1, modifier = Modifier.padding(top = 8.dp))
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

    if (!s.otherWallet) {
        PrimaryButton(text = "Create Protected Savings", onClick = actions::onSetupCreate, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            "One fingerprint, one card tap.",
            style = TangemTheme.typography.caption2,
            color = TangemTheme.colors.text.tertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(text = "Use another wallet as the backup instead", onClick = { actions.onSetupUseOtherWallet(true) })
    } else {
        OtherWalletSetup(s, actions)
        TextButton(text = "Use this phone as the backup instead", onClick = { actions.onSetupUseOtherWallet(false) })
    }
    s.error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
}

@Composable
private fun OtherWalletSetup(s: SetupUM, actions: SafeChainActions) {
    val clipboard = LocalClipboardManager.current
    Step(1, "Your backup wallet")
    AddressField(
        value = s.recovery,
        label = "Backup wallet address (another device)",
        onChange = actions::onSetupRecoveryChange,
        onPaste = { clipboard.getText()?.text?.let(actions::onSetupRecoveryChange) },
        onScan = actions::onScanRecovery,
    )
    Step(2, "Confirm with that wallet")
    if (s.consentLink == null) {
        PrimaryButton(text = "Continue", onClick = actions::onSetupMakeLink, modifier = Modifier.fillMaxWidth())
    } else {
        ConsentLink(s.consentLink)
        Step(3, "Bring back the signature")
        AddressField(
            value = s.signature,
            label = "Backup wallet's signature",
            onChange = actions::onSetupSignatureChange,
            onPaste = { clipboard.getText()?.text?.let(actions::onSetupSignatureChange) },
            onScan = actions::onScanSignature,
        )
        PrimaryButton(text = "Create Protected Savings", onClick = actions::onSetupCreateWithOtherWallet, modifier = Modifier.fillMaxWidth())
    }
}

/** The consent link as a QR, with Share and Copy, for the device that holds the other wallet. */
@Composable
private fun ConsentLink(link: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Text(
        "Open this where that wallet is and sign. It's free and only says \"I agree to be this vault's backup key\".",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = rememberQrPainter(link, size = 220.dp, padding = 8.dp),
            contentDescription = "Consent link QR code",
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, TangemTheme.colors.stroke.primary, RoundedCornerShape(16.dp)),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            text = "Share link",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, "Send to your backup wallet"))
            },
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(text = "Copy", onClick = { clipboard.setText(AnnotatedString(link)) }, modifier = Modifier.weight(1f))
    }
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
                is SheetUM.AddFunds -> AmountSheet("Add from your card", "On your card: ${sheet.max}", sheet.amount, sheet.error, null, actions)
                is SheetUM.Withdraw -> AmountSheet("Send", "Available: ${sheet.max}", sheet.amount, sheet.error, sheet.to, actions)
                is SheetUM.Receive -> ReceiveSheet(sheet)
                is SheetUM.OtherWallet -> OtherWalletSheet(sheet, actions)
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
            "It waits out the protection period before it's delivered. Until then you can cancel, and the money comes back.",
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
        if (r.withPhone) {
            "This phone's backup key signs exactly this, after your fingerprint."
        } else {
            "Your card signs exactly this. It has no screen, so check it here."
        },
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
    PrimaryButton(text = if (r.withPhone) "Confirm with fingerprint" else "Sign with card", onClick = actions::onSign, modifier = Modifier.fillMaxWidth())
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

@Composable
private fun ReceiveSheet(r: SheetUM.Receive) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    SheetTitle("Receive")
    Text(
        "Anything sent to this address lands in your protected balance. ${SepoliaConfig.NAME} test tokens (${SepoliaConfig.TOKEN_SYMBOL}) only.",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = rememberQrPainter(r.address, size = 220.dp, padding = 8.dp),
            contentDescription = "Savings address QR code",
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, TangemTheme.colors.stroke.primary, RoundedCornerShape(16.dp)),
        )
    }
    SelectionContainer { Mono(groupedAddress(r.address), TangemTheme.colors.text.primary1) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            text = "Share",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.address)
                context.startActivity(Intent.createChooser(send, "Share your Savings address"))
            },
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(text = "Copy", onClick = { clipboard.setText(AnnotatedString(r.address)) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OtherWalletSheet(o: SheetUM.OtherWallet, actions: SafeChainActions) {
    val clipboard = LocalClipboardManager.current
    SheetTitle("Use another wallet as the backup")
    Text(
        "Your card proposes it, this phone approves with your fingerprint, and it takes effect after the waiting period. Until then this phone stays the backup.",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    AddressField(
        value = o.address,
        label = "Other wallet's address",
        onChange = actions::onOtherWalletAddressChange,
        onPaste = { clipboard.getText()?.text?.let(actions::onOtherWalletAddressChange) },
        onScan = actions::onScanOtherWallet,
    )
    if (o.link == null) {
        PrimaryButton(text = "Continue", onClick = actions::onOtherWalletMakeLink, modifier = Modifier.fillMaxWidth())
    } else {
        ConsentLink(o.link)
        AddressField(
            value = o.signature,
            label = "That wallet's signature",
            onChange = actions::onOtherWalletSignatureChange,
            onPaste = { clipboard.getText()?.text?.let(actions::onOtherWalletSignatureChange) },
            onScan = actions::onScanOtherWalletSignature,
        )
        PrimaryButton(text = "Continue", onClick = actions::onOtherWalletContinue, modifier = Modifier.fillMaxWidth())
    }
    o.error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
}
