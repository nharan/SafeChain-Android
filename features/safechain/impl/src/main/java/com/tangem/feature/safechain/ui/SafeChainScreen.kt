package com.tangem.feature.safechain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tangem.core.ui.components.PrimaryButton
import com.tangem.core.ui.components.SecondaryButton
import com.tangem.core.ui.components.appbar.AppBarWithBackButton
import com.tangem.core.ui.res.TangemTheme
import com.tangem.feature.safechain.chain.SepoliaConfig
import com.tangem.feature.safechain.chain.groupedAddress

private val Danger = Color(0xFFFFE3E1)
private val Warn = Color(0xFFFFF4D6)

@Composable
internal fun SafeChainScreen(state: SafeChainUM, actions: SafeChainActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(TangemTheme.colors.background.secondary)) {
        AppBarWithBackButton(onBackClick = actions::onBack, text = "SafeChain · ${SepoliaConfig.NAME}")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { Banner(state) }
            item { AccountCard(state, actions) }
            if (state.vaults.isEmpty() && !state.loading && state.accountAddress != null) {
                item { CreateVaultCard(state.create, actions) }
            }
            state.vaults.forEach { v -> item(key = v.address) { VaultCard(v, actions) } }
            item {
                Text(
                    text = "Test network only (${SepoliaConfig.NAME}). The contracts enforce the rules; this screen only " +
                        "shows them. Anything you sign is shown here in full before you tap the card.",
                    style = TangemTheme.typography.caption2,
                    color = TangemTheme.colors.text.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
    state.confirm?.let { ConfirmDialog(it, actions) }
}

@Composable
private fun Banner(state: SafeChainUM) {
    state.busy?.let { Notice(it, Warn) }
    state.message?.let { Notice(it, Warn) }
    state.error?.let { Notice(it, Danger) }
    when (val h = state.health) {
        is HealthUM.Disagree -> Notice(
            "Your RPC endpoints disagree — verify on an independent explorer before trusting this screen. ${h.text}. " +
                "Disputed items are shown with their most urgent reading.",
            Danger,
        )
        is HealthUM.Degraded -> Notice("Not all RPC endpoints are answering — data isn't fully cross-checked. ${h.text}", Warn)
        else -> Unit
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    SelectionContainer {
        Text(
            text = text,
            style = TangemTheme.typography.body2,
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(color, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(TangemTheme.colors.background.primary, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        content()
    }
}

@Composable
private fun Body(text: String, mono: Boolean = false) = Text(
    text = text,
    style = TangemTheme.typography.body2.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it },
    color = TangemTheme.colors.text.secondary,
)

@Composable
private fun AccountCard(state: SafeChainUM, actions: SafeChainActions) = Card("Spending key: this card") {
    state.accountAddress?.let { SelectionContainer { Body(groupedAddress(it), mono = true) } }
        ?: Body("Reading the card's Ethereum key…")
    Body("Compare this with the Ethereum address on the Tangem app's Receive screen.")
    Body("Gas: ${state.ethBalance}")
    Body("Spending balance: ${state.tokenBalance}")
    SecondaryButton(text = "Refresh", onClick = actions::onRefresh, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CreateVaultCard(create: CreateVaultUM, actions: SafeChainActions) = Card("Create Protected Savings") {
    Body(
        "Withdrawals wait out a protection period and can be cancelled until then. A second, independent key " +
            "(the recovery key, on another device) can also cancel and lock.",
    )
    OutlinedTextField(
        value = create.recovery,
        onValueChange = actions::onRecoveryChange,
        label = { Text("Recovery key address (another device)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = create.delayHours,
        onValueChange = actions::onDelayHoursChange,
        label = { Text("Protection period (hours, 24–720)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (create.consentLink == null) {
        PrimaryButton(text = "Next: get the recovery key's consent", onClick = actions::onStartCreate, modifier = Modifier.fillMaxWidth())
    } else {
        val clipboard = LocalClipboardManager.current
        Body(
            "1. Open this link where your recovery key lives (the SafeChain web wallet, Sepolia mode) and sign. " +
                "2. Paste the signature below. 3. Tap your card to create the vault.",
        )
        SelectionContainer { Body(create.consentLink, mono = true) }
        SecondaryButton(
            text = "Copy link",
            onClick = { clipboard.setText(AnnotatedString(create.consentLink)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = create.consentSignature,
            onValueChange = actions::onConsentSignatureChange,
            label = { Text("Recovery key's signature (0x…)") },
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(text = "Create Savings (tap card)", onClick = actions::onCreateVault, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun VaultCard(v: VaultUM, actions: SafeChainActions) = Card("Protected Savings") {
    SelectionContainer { Body(groupedAddress(v.address), mono = true) }
    Body("Balance ${v.balance} · available ${v.available} · ${v.protection}")
    Body("Recovery key: ${groupedAddress(v.recovery)}", mono = true)
    v.haven?.let { Body("Safe haven: ${groupedAddress(it)}", mono = true) }
    if (v.locked) Notice("Savings are locked. No new withdrawals; only the recovery key can unlock.", Danger)
    if (v.disputed) Notice("⚠ The RPC endpoints disagree about this vault.", Danger)
    v.warnings.forEach { Notice(it, Danger) }

    Text("Pending releases", style = TangemTheme.typography.subtitle2, color = TangemTheme.colors.text.primary1)
    if (v.withdrawals.isEmpty()) Body("None.")
    v.withdrawals.forEach { w ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Body("#${w.id}: ${w.amount} → ${if (w.toHaven) "safe haven " else ""}")
            Body(groupedAddress(w.to), mono = true)
            Body(listOfNotNull(w.stateLabel, w.countdown, if (w.disputed) "⚠ endpoints disagree" else null).joinToString(" · "))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (w.canCancel) SecondaryButton(text = "Cancel", onClick = { actions.onCancelWithdrawal(v.address, w.id) })
                if (w.canExecute) SecondaryButton(text = "Release", onClick = { actions.onExecuteWithdrawal(v.address, w.id) })
            }
        }
    }

    if (v.isPrimary || v.isRecovery) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!v.locked) SecondaryButton(text = "Lock Savings", onClick = { actions.onLock(v.address) })
            if (v.haven != null && v.isPrimary) SecondaryButton(text = "Move all to haven", onClick = { actions.onMoveToHaven(v.address) })
        }
    }
    if (v.isPrimary && !v.locked) {
        Text("Request a withdrawal", style = TangemTheme.typography.subtitle2, color = TangemTheme.colors.text.primary1)
        OutlinedTextField(
            value = v.withdrawAmount,
            onValueChange = { actions.onWithdrawAmountChange(v.address, it) },
            label = { Text("Amount (${SepoliaConfig.TOKEN_SYMBOL})") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = v.withdrawTo,
            onValueChange = { actions.onWithdrawToChange(v.address, it) },
            label = { Text("Destination address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        PrimaryButton(text = "Request withdrawal (tap card)", onClick = { actions.onRequestWithdrawal(v.address) }, modifier = Modifier.fillMaxWidth())
    }
    if (v.isPrimary) {
        Text("Add funds", style = TangemTheme.typography.subtitle2, color = TangemTheme.colors.text.primary1)
        OutlinedTextField(
            value = v.depositAmount,
            onValueChange = { actions.onDepositAmountChange(v.address, it) },
            label = { Text("Amount (${SepoliaConfig.TOKEN_SYMBOL})") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        SecondaryButton(text = "Add to Savings (2 taps)", onClick = { actions.onDeposit(v.address) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConfirmDialog(c: ConfirmUM, actions: SafeChainActions) {
    AlertDialog(
        onDismissRequest = actions::onDismissConfirm,
        title = { Text(c.title) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your card will sign exactly this. It has no screen, so check it here:")
                    c.lines.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                    Text(c.maxFee)
                }
            }
        },
        confirmButton = { PrimaryButton(text = "Tap card to sign", onClick = actions::onConfirm) },
        dismissButton = { SecondaryButton(text = "Don't sign", onClick = actions::onDismissConfirm) },
    )
}
