package com.tangem.feature.safechain.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
private val Blue = Color(0xFF3B82F6)
private val BlueBg = Color(0x1F3B82F6)

private val CardShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(50)
private const val EXPLORER = "https://safechain.rep.run/#/explorer"
private const val DISABLED_ALPHA = 0.45f

/** A page like a bank's: Checking and Savings, what's on its way, what happened, and how it's protected. */
@Composable
internal fun SafeChainScreen(state: SafeChainUM, actions: SafeChainActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(TangemTheme.colors.background.secondary)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            AppBarWithBackButton(onBackClick = actions::onBack, text = if (state.vault != null) "Accounts" else "Protected Savings")
            LazyColumn(
                // Edge to edge: the keyboard doesn't resize the window, so pad for it (setup fields live here).
                modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.demo) item { DemoBanner() }
                item { HealthBanner(state.health, state.loadError) }
                val vault = state.vault
                when {
                    state.loading && vault == null -> item { Loading() }
                    vault != null -> home(state, vault, actions)
                    state.accountAddress != null -> item { OpenSavings(state, actions) }
                }
                item { Footer(state) }
            }
        }
        FormPage(state, actions)
    }
    Sheet(state.sheet, actions)
}

private fun LazyListScope.home(state: SafeChainUM, v: VaultUM, actions: SafeChainActions) {
    item { TotalHeader(state, v) }
    item { AccountsCard(state, v) }
    item { QuickActions(v, actions) }
    v.alerts.forEach { a -> item(key = a) { Alert(a) } }
    state.previous.forEach { p -> item(key = "prev${p.address}") { PreviousSavings(p, actions) } }
    v.backupChange?.let { b -> item { BackupChange(b, actions) } }
    if (v.pending.isNotEmpty()) {
        item { SectionTitle("Upcoming") }
        item { Upcoming(v.pending, actions) }
    }
    item { SectionTitle("Activity") }
    item { ActivityList(v.activity) }
    item { SectionTitle("Protection") }
    item { Protection(state, v, actions) }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun Card(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(CardShape)
            .background(TangemTheme.colors.background.primary)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(if (padding == 0.dp) 0.dp else 12.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) = Text(
    text = text,
    style = TangemTheme.typography.subtitle1,
    color = TangemTheme.colors.text.primary1,
    modifier = Modifier.padding(start = 24.dp, top = 12.dp),
)

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(TangemTheme.colors.stroke.primary))

@Composable
private fun Pill(text: String, fg: Color, bg: Color, @DrawableRes icon: Int? = null) {
    Row(
        modifier = Modifier.clip(PillShape).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon?.let { Icon(painterResource(it), null, tint = fg, modifier = Modifier.size(14.dp)) }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A round tinted icon, the leading element of every row. */
@Composable
private fun Badge(@DrawableRes icon: Int, fg: Color, bg: Color, size: Dp = 40.dp) = Box(
    Modifier.size(size).clip(CircleShape).background(bg),
    contentAlignment = Alignment.Center,
) { Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(size * 0.5f)) }

@Composable
private fun Mono(text: String, color: Color = TangemTheme.colors.text.secondary) = Text(
    text = text,
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = color,
)

/** "24 hours" → "24h", "3 days" → "3d": for tight rows. */
private fun short(hold: String) = hold.replace(" hours", "h").replace(" days", "d")

/** "17,700 PWTEST" → big number, small symbol. */
@Composable
private fun Amount(text: String, size: Int, color: Color = TangemTheme.colors.text.primary1) {
    val number = text.substringBefore(" ")
    val symbol = text.substringAfter(" ", "")
    Row {
        Text(number, fontSize = size.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (symbol.isNotEmpty()) {
            Text(
                " $symbol",
                fontSize = (size * 0.45f).sp,
                fontWeight = FontWeight.Medium,
                color = TangemTheme.colors.text.tertiary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = TangemTheme.colors.icon.primary1)
}

@Composable
private fun DemoBanner() = Banner(
    "Demo (debug build): a pretend chain for trying the screens. No card, no network, nothing is real.",
    Amber,
    AmberBg,
)

@Composable
private fun Banner(text: String, fg: Color, bg: Color) = Text(
    text = text,
    color = fg,
    style = TangemTheme.typography.body2,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CardShape).background(bg).padding(14.dp),
)

@Composable
private fun HealthBanner(health: HealthUM, loadError: String?) {
    when {
        loadError != null -> Banner(loadError, Red, RedBg)
        health is HealthUM.Disagree -> Banner(
            "Your two Sepolia data sources disagree (${health.text}). Check an independent explorer before trusting this screen.",
            Red,
            RedBg,
        )
        health is HealthUM.Degraded -> Banner("One Sepolia data source isn't answering, so this isn't fully cross-checked.", Amber, AmberBg)
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

// ------------------------------------------------------------------ home

@Composable
private fun TotalHeader(state: SafeChainUM, v: VaultUM) = Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text("Total balance", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.tertiary)
    Amount(state.total, size = 40)
    when {
        v.locked -> Pill("Savings locked", Red, RedBg, R.drawable.ic_lock_24)
        v.backupIsThisPhone -> Pill("Protected · backup on this phone", Green, GreenBg, R.drawable.ic_shield_24)
        else -> Pill("Protected · backup wallet", Green, GreenBg, R.drawable.ic_shield_24)
    }
}

@Composable
private fun AccountsCard(state: SafeChainUM, v: VaultUM) = Card(padding = 0.dp) {
    AccountRow(
        icon = R.drawable.ic_tangem_card_24,
        tint = Blue,
        tintBg = BlueBg,
        name = "Checking",
        detail = "Your Tangem card · instant",
        amount = state.cardBalance,
        note = null,
    )
    Box(Modifier.padding(start = 72.dp)) { Divider() }
    AccountRow(
        icon = R.drawable.ic_shield_24,
        tint = if (v.locked) Red else Green,
        tintBg = if (v.locked) RedBg else GreenBg,
        name = "Savings",
        detail = if (v.locked) "Locked · nothing can leave" else "Protected · ${short(v.hold)} hold",
        amount = v.balance,
        note = if (v.partlyHeld) "${v.available.substringBefore(" ")} available" else null,
    )
}

@Composable
@Suppress("LongParameterList")
private fun AccountRow(@DrawableRes icon: Int, tint: Color, tintBg: Color, name: String, detail: String, amount: String, note: String?) = Row(
    Modifier.fillMaxWidth().padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    Badge(icon, tint, tintBg, size = 42.dp)
    Column(Modifier.weight(1f)) {
        Text(name, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        Text(detail, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
    }
    Column(horizontalAlignment = Alignment.End) {
        Amount(amount, size = 18)
        note?.let { Text(it, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary) }
    }
}

@Composable
private fun QuickActions(v: VaultUM, actions: SafeChainActions) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    QuickAction("Send", R.drawable.ic_arrow_up_24, enabled = v.isPrimary && !v.locked, onClick = actions::onOpenWithdraw)
    QuickAction("Receive", R.drawable.ic_arrow_down_24, enabled = true, onClick = actions::onOpenReceive)
    QuickAction("Move", R.drawable.ic_exchange_vertical_24, enabled = v.isPrimary, onClick = actions::onOpenMove)
}

@Composable
private fun QuickAction(label: String, @DrawableRes icon: Int, enabled: Boolean, onClick: () -> Unit) = Column(
    Modifier.clip(CardShape).clickable(enabled = enabled, onClick = onClick).padding(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    val fg = if (enabled) TangemTheme.colors.text.primary2 else TangemTheme.colors.text.disabled
    val bg = if (enabled) TangemTheme.colors.button.primary else TangemTheme.colors.background.primary
    Box(Modifier.size(56.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(24.dp))
    }
    Text(label, style = TangemTheme.typography.button, color = if (enabled) TangemTheme.colors.text.primary1 else TangemTheme.colors.text.disabled)
}

@Composable
private fun Upcoming(items: List<WithdrawalUM>, actions: SafeChainActions) = Card {
    items.forEachIndexed { i, w ->
        if (i > 0) Divider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge(R.drawable.ic_arrow_up_24, Amber, AmberBg)
            Column(Modifier.weight(1f)) {
                Text(
                    "To ${w.toLabel}",
                    style = TangemTheme.typography.subtitle1,
                    color = TangemTheme.colors.text.primary1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    w.countdown ?: "Ready to deliver",
                    style = TangemTheme.typography.caption2,
                    color = if (w.isFinal) Green else TangemTheme.colors.text.tertiary,
                )
            }
            Text("−${w.amount.substringBefore(" ")}", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        }
        if (!w.isFinal) Progress(w.progress, Amber, AmberBg)
        if (w.disputed) Text("⚠ Your data sources disagree about this payment", color = Red, style = TangemTheme.typography.caption2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (w.canCancel) SmallButton("Cancel", Red, RedBg, R.drawable.ic_close_24) { actions.onCancelWithdrawal(w.id) }
            if (w.canRelease) SmallButton("Deliver now", Green, GreenBg, R.drawable.ic_check_24) { actions.onReleaseWithdrawal(w.id) }
        }
    }
    Text(
        "Didn't make one of these? Cancel it before it arrives and the money goes straight back to Savings.",
        style = TangemTheme.typography.caption2,
        color = TangemTheme.colors.text.tertiary,
    )
}

@Composable
private fun Progress(value: Float, color: Color, track: Color) = Box(Modifier.fillMaxWidth().height(4.dp).clip(PillShape).background(track)) {
    Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(4.dp).clip(PillShape).background(color))
}

@Composable
private fun SmallButton(text: String, fg: Color, bg: Color, @DrawableRes icon: Int, onClick: () -> Unit) = Row(
    Modifier.clip(PillShape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(16.dp))
    Text(text, color = fg, style = TangemTheme.typography.button)
}

@Composable
private fun ActivityList(items: List<ActivityUM>) = Card {
    if (items.isEmpty()) {
        Text("Nothing yet.", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.tertiary)
    }
    val uri = LocalUriHandler.current
    items.forEachIndexed { i, a ->
        if (i > 0) Box(Modifier.padding(start = 52.dp)) { Divider() }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(a.url) } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (fg, bg) = when (a.tone) {
                ActivityUM.Tone.Good -> Green to GreenBg
                ActivityUM.Tone.Warning -> Amber to AmberBg
                ActivityUM.Tone.Danger -> Red to RedBg
                ActivityUM.Tone.Neutral -> TangemTheme.colors.icon.informative to TangemTheme.colors.background.secondary
            }
            val icon = when (a.icon) {
                ActivityUM.Icon.In -> R.drawable.ic_arrow_down_24
                ActivityUM.Icon.Out -> R.drawable.ic_arrow_up_24
                ActivityUM.Icon.Returned -> R.drawable.ic_refresh_24
                ActivityUM.Icon.Delivered -> R.drawable.ic_check_24
                ActivityUM.Icon.Lock, ActivityUM.Icon.Unlock -> R.drawable.ic_lock_24
                ActivityUM.Icon.Key -> R.drawable.ic_shield_24
            }
            Badge(icon, fg, bg)
            Column(Modifier.weight(1f)) {
                Text(a.title, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(a.detail, a.time).filter { it.isNotBlank() }.joinToString(" · "),
                    style = TangemTheme.typography.caption2,
                    color = TangemTheme.colors.text.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            a.amount?.let {
                Text(
                    it,
                    style = TangemTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = if (it.startsWith("+")) Green else TangemTheme.colors.text.primary1,
                )
            }
        }
    }
}

@Composable
private fun Protection(state: SafeChainUM, v: VaultUM, actions: SafeChainActions) = Card {
    var details by rememberSaveable { mutableStateOf(false) }
    InfoRow(R.drawable.ic_circle_clock_24, "Hold on every payment", v.hold)
    InfoRow(
        if (v.backupIsThisPhone) R.drawable.ic_mobile_wallet_icon_24 else R.drawable.ic_wallet_24,
        "Backup key",
        if (v.backupIsThisPhone) "This phone" else "Another wallet",
    )
    InfoRow(R.drawable.ic_tangem_card_24, "Spending key", "Your Tangem card")
    InfoRow(R.drawable.ic_shield_24, "Safe haven", if (v.haven == null) "Not set" else "Set")

    if (v.backupIsThisPhone && state.phoneKeyLow && v.isPrimary) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AmberBg).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "This phone needs a little Sepolia ETH to lock in an emergency.",
                style = TangemTheme.typography.caption2,
                color = Amber,
                modifier = Modifier.weight(1f),
            )
            SmallButton("Add", Amber, AmberBg, R.drawable.ic_plus_24, actions::onTopUpPhone)
        }
    }

    when {
        v.locked && v.backupIsThisPhone -> PrimaryButton(text = "Unlock with this phone", onClick = actions::onUnlock, modifier = Modifier.fillMaxWidth())
        v.locked -> Text("Only your backup wallet can unlock Savings.", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
        v.backupIsThisPhone -> DangerButton("Lost your card? Lock Savings", R.drawable.ic_fingerprint_24, actions::onEmergencyLock)
        v.isPrimary -> DangerButton("Lock Savings now", R.drawable.ic_lock_24, actions::onLock)
    }

    Divider()
    Row(
        Modifier.fillMaxWidth().clickable { details = !details }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Account details", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_chevron_24),
            null,
            tint = TangemTheme.colors.icon.informative,
            modifier = Modifier.size(20.dp).rotate(if (details) 180f else 0f),
        )
    }
    AnimatedVisibility(details) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AddressRow("Savings address", v.address)
            state.accountAddress?.let { AddressRow("Checking (your card)", it) }
            AddressRow(if (v.backupIsThisPhone) "Backup key (this phone)" else "Backup key", v.recovery)
            v.haven?.let { AddressRow("Safe haven", it) }
            if (v.backupIsThisPhone) KeyValue("Emergency fees on this phone", state.phoneKeyGas)
            KeyValue("Network fees on your card", state.gasBalance)
            val uri = LocalUriHandler.current
            TextButton(text = "Check it on SafeChain Explorer", onClick = { runCatching { uri.openUri("$EXPLORER/vault/${v.address}") } })
            if (v.haven != null && v.isPrimary) {
                SecondaryButton(text = "Move everything to your safe haven", onClick = actions::onMoveToHaven, modifier = Modifier.fillMaxWidth())
            }
            if (v.backupIsThisPhone && v.backupChange == null && v.isPrimary) {
                TextButton(text = "Use another wallet as the backup instead", onClick = actions::onOpenOtherWallet)
            }
            if (!v.backupIsThisPhone && v.isPrimary) {
                Text(
                    "Lost that backup, or want this phone as the backup? Open a new Savings protected by this phone and move your money there.",
                    style = TangemTheme.typography.caption2,
                    color = TangemTheme.colors.text.tertiary,
                )
                SecondaryButton(text = "New Savings with this phone as backup", onClick = actions::onMoveToNewVault, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InfoRow(@DrawableRes icon: Int, label: String, value: String) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Icon(painterResource(icon), null, tint = TangemTheme.colors.icon.informative, modifier = Modifier.size(20.dp))
    Text(label, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary, modifier = Modifier.weight(1f))
    Text(value, style = TangemTheme.typography.body2, fontWeight = FontWeight.Medium, color = TangemTheme.colors.text.primary1)
}

@Composable
private fun KeyValue(label: String, value: String) = Row {
    Text(label, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary, modifier = Modifier.weight(1f))
    Text(value, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.secondary)
}

/** A full address in groups (tap to copy): this is where the raw details live, out of the way. */
@Composable
private fun AddressRow(label: String, address: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(address)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_copy_24), "Copy", tint = TangemTheme.colors.icon.informative, modifier = Modifier.size(14.dp))
        }
        Mono(groupedAddress(address), TangemTheme.colors.text.secondary)
    }
}

@Composable
private fun DangerButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit) = Row(
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .border(1.dp, Red.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 13.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(painterResource(icon), null, tint = Red, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, color = Red, style = TangemTheme.typography.button)
}

@Composable
private fun BackupChange(b: BackupChangeUM, actions: SafeChainActions) = Card {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Badge(R.drawable.ic_shield_24, Amber, AmberBg)
        Column(Modifier.weight(1f)) {
            Text("New backup key on its way", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
            Text(
                when {
                    !b.approved && b.canApproveFromPhone -> "Waiting for this phone to approve"
                    !b.approved -> "Waiting for the other key to approve"
                    b.canFinish -> "Ready to finish"
                    else -> b.waitText.orEmpty()
                },
                style = TangemTheme.typography.caption2,
                color = TangemTheme.colors.text.tertiary,
            )
        }
    }
    Mono(groupedAddress(b.newBackup), TangemTheme.colors.text.secondary)
    when {
        !b.approved && b.canApproveFromPhone -> PrimaryButton(text = "Approve with fingerprint", onClick = actions::onApproveBackupChange, modifier = Modifier.fillMaxWidth())
        b.canFinish -> PrimaryButton(text = "Finish", onClick = actions::onFinishBackupChange, modifier = Modifier.fillMaxWidth())
    }
    TextButton(text = "Cancel this change", onClick = actions::onCancelBackupChange)
}

@Composable
private fun PreviousSavings(p: PreviousVaultUM, actions: SafeChainActions) = Card {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Badge(R.drawable.ic_exchange_vertical_24, Blue, BlueBg)
        Column(Modifier.weight(1f)) {
            Text("Your previous Savings", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
            Text("${p.address.take(6)}…${p.address.takeLast(4)}", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
        }
        Amount(p.balance, size = 16)
    }
    p.moving.forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Moving ${w.amount} here", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1, modifier = Modifier.weight(1f))
            Text(w.countdown ?: "Ready", style = TangemTheme.typography.caption2, color = if (w.isFinal) Green else Amber)
        }
        if (!w.isFinal) Progress(w.progress, Blue, BlueBg)
        if (w.canRelease) PrimaryButton(text = "Bring it here now", onClick = { actions.onReleaseFromPrevious(p.address, w.id) }, modifier = Modifier.fillMaxWidth())
    }
    if (p.canMove && p.moving.isEmpty()) {
        Text(
            "Bring it into your current Savings. It waits out the old hold first.",
            style = TangemTheme.typography.body2,
            color = TangemTheme.colors.text.secondary,
        )
        PrimaryButton(text = "Move ${p.available} here", onClick = { actions.onMoveFromPrevious(p.address) }, modifier = Modifier.fillMaxWidth())
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
    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
)

// ------------------------------------------------------------------ opening Savings

@Composable
private fun OpenSavings(state: SafeChainUM, actions: SafeChainActions) = Card(padding = 20.dp) {
    val s = state.setup
    Badge(R.drawable.ic_shield_24, Green, GreenBg, size = 56.dp)
    Text("Open a Savings account", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = TangemTheme.colors.text.primary1)
    Benefit(R.drawable.ic_circle_clock_24, "Every payment waits", "Money leaves Savings only after a hold you choose.")
    Benefit(R.drawable.ic_refresh_24, "Cancel anytime before it arrives", "The money comes straight back to you.")
    Benefit(
        if (s.otherWallet) R.drawable.ic_wallet_24 else R.drawable.ic_fingerprint_24,
        if (s.otherWallet) "Your other wallet is the backup" else "Lost your card? Your phone locks it",
        if (s.otherWallet) "It can stop payments and lock Savings." else "This phone becomes the backup key, behind your fingerprint.",
    )

    Text("Hold period", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("24" to "1 day", "72" to "3 days", "168" to "7 days").forEach { (h, label) ->
            val on = s.delayHours == h
            Text(
                label,
                color = if (on) TangemTheme.colors.text.primary2 else TangemTheme.colors.text.primary1,
                style = TangemTheme.typography.button,
                modifier = Modifier.clip(PillShape)
                    .background(if (on) TangemTheme.colors.button.primary else TangemTheme.colors.background.secondary)
                    .clickable { actions.onSetupDelayChange(h) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }

    if (!s.otherWallet) {
        PrimaryButton(text = "Open Savings", onClick = actions::onSetupCreate, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            "One fingerprint and one card tap.",
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
private fun Benefit(@DrawableRes icon: Int, title: String, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Icon(painterResource(icon), null, tint = Green, modifier = Modifier.size(22.dp).padding(top = 2.dp))
    Column {
        Text(title, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        Text(text, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
    }
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
        PrimaryButton(text = "Open Savings", onClick = actions::onSetupCreateWithOtherWallet, modifier = Modifier.fillMaxWidth())
    }
}

/** The consent link as a QR, with Share and Copy, for the device that holds the other wallet. */
@Composable
private fun ConsentLink(link: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Text(
        "Open this where that wallet is and sign. It's free and only says \"I agree to be this account's backup key\".",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    Qr(link, "Consent link QR code")
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
private fun Qr(content: String, description: String) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Image(
        painter = rememberQrPainter(content, size = 220.dp, padding = 8.dp),
        contentDescription = description,
        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, TangemTheme.colors.stroke.primary, RoundedCornerShape(16.dp)),
    )
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

/**
 * Tangem's colours for text fields. Material's defaults don't follow the app theme: focused, the border and
 * label turned white on the light theme and dark on the dark one, so a tapped field seemed to vanish.
 */
@Composable
private fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TangemTheme.colors.text.primary1,
    unfocusedTextColor = TangemTheme.colors.text.primary1,
    focusedContainerColor = TangemTheme.colors.background.primary,
    unfocusedContainerColor = TangemTheme.colors.background.primary,
    focusedBorderColor = TangemTheme.colors.icon.primary1,
    unfocusedBorderColor = TangemTheme.colors.stroke.primary,
    focusedLabelColor = TangemTheme.colors.text.secondary,
    unfocusedLabelColor = TangemTheme.colors.text.tertiary,
    cursorColor = TangemTheme.colors.icon.primary1,
    focusedSuffixColor = TangemTheme.colors.text.tertiary,
    unfocusedSuffixColor = TangemTheme.colors.text.tertiary,
    focusedTrailingIconColor = TangemTheme.colors.icon.informative,
    unfocusedTrailingIconColor = TangemTheme.colors.icon.informative,
)

@Composable
private fun AddressField(value: String, label: String, onChange: (String) -> Unit, onPaste: () -> Unit, onScan: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        colors = fieldColors(),
        shape = RoundedCornerShape(14.dp),
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

// ------------------------------------------------------------------ full-page forms

/**
 * Forms with text fields are full pages over the screen, not bottom sheets: the keyboard pushes the page's
 * content up and the button stays above it, instead of fighting a sheet for space.
 */
@Composable
private fun FormPage(state: SafeChainUM, actions: SafeChainActions) {
    val sheet = state.sheet
    val title = when (sheet) {
        is SheetUM.Withdraw -> if (sheet.toChecking) "Move to Checking" else "Send"
        is SheetUM.AddFunds -> "Move to Savings"
        is SheetUM.OtherWallet -> "Backup wallet"
        else -> return
    }
    BackHandler(onBack = actions::onCloseSheet)
    Column(
        Modifier
            .fillMaxSize()
            .background(TangemTheme.colors.background.secondary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::onCloseSheet) {
                Icon(painterResource(R.drawable.ic_close_24), "Close", tint = TangemTheme.colors.icon.primary1)
            }
            Text(title, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (sheet) {
                is SheetUM.Withdraw -> SendForm(sheet, state, actions)
                is SheetUM.AddFunds -> MoveToSavingsForm(sheet, actions)
                is SheetUM.OtherWallet -> OtherWalletForm(sheet, actions)
                else -> Unit
            }
        }
        val (label, onClick, enabled) = when (sheet) {
            is SheetUM.OtherWallet ->
                if (sheet.link == null) {
                    Triple("Continue", actions::onOtherWalletMakeLink, sheet.address.isNotBlank())
                } else {
                    Triple("Review", actions::onOtherWalletContinue, sheet.signature.isNotBlank())
                }
            is SheetUM.Withdraw -> Triple("Review", actions::onContinue, sheet.amount.isNotBlank() && (sheet.toChecking || sheet.to.isNotBlank()))
            else -> Triple("Review", actions::onContinue, (sheet as? SheetUM.AddFunds)?.amount?.isNotBlank() == true)
        }
        PrimaryButton(text = label, onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
@Suppress("LongParameterList")
private fun FromTo(@DrawableRes fromIcon: Int, fromTint: Color, fromBg: Color, from: String, fromNote: String, to: String, toNote: String) = Column(
    Modifier.fillMaxWidth().clip(CardShape).background(TangemTheme.colors.background.primary).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Badge(fromIcon, fromTint, fromBg, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text("From $from", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
            Text(fromNote, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
        }
    }
    if (to.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge(R.drawable.ic_arrow_down_24, TangemTheme.colors.icon.informative, TangemTheme.colors.background.secondary, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text("To $to", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
                Text(toNote, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            }
        }
    }
}

@Composable
private fun AmountField(amount: String, max: String, onChange: (String) -> Unit, onMax: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    OutlinedTextField(
        value = amount,
        onValueChange = onChange,
        label = { Text("Amount") },
        suffix = { Text(SepoliaConfig.TOKEN_SYMBOL) },
        colors = fieldColors(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        textStyle = TangemTheme.typography.h3,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$max ${SepoliaConfig.TOKEN_SYMBOL} available",
            style = TangemTheme.typography.caption2,
            color = TangemTheme.colors.text.tertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(text = "Max", onClick = onMax)
    }
}

@Composable
private fun Note(@DrawableRes icon: Int, text: String) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TangemTheme.colors.background.primary).padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    Icon(painterResource(icon), null, tint = TangemTheme.colors.icon.informative, modifier = Modifier.size(18.dp))
    Text(text, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.secondary)
}

@Composable
private fun SendForm(sh: SheetUM.Withdraw, state: SafeChainUM, actions: SafeChainActions) {
    val clipboard = LocalClipboardManager.current
    val hold = state.vault?.hold ?: "the hold"
    FromTo(
        R.drawable.ic_shield_24,
        Green,
        GreenBg,
        "Savings",
        "Protected · ${short(hold)} hold",
        to = if (sh.toChecking) "Checking" else "",
        toNote = if (sh.toChecking) "Your Tangem card" else "",
    )
    AmountField(sh.amount, sh.max, actions::onAmountChange, actions::onMax)
    if (!sh.toChecking) {
        AddressField(
            value = sh.to,
            label = "To address",
            onChange = actions::onDestinationChange,
            onPaste = { clipboard.getText()?.text?.let(actions::onDestinationChange) },
            onScan = actions::onScanDestination,
        )
    }
    Note(R.drawable.ic_circle_clock_24, "Arrives in $hold. Until then you can cancel it, and the money comes straight back to Savings.")
    sh.error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
}

@Composable
private fun MoveToSavingsForm(sh: SheetUM.AddFunds, actions: SafeChainActions) {
    FromTo(
        R.drawable.ic_tangem_card_24,
        Blue,
        BlueBg,
        "Checking",
        "Your Tangem card",
        to = "Savings",
        toNote = "Protected from the moment it arrives",
    )
    AmountField(sh.amount, sh.max, actions::onAmountChange, actions::onMax)
    Note(R.drawable.ic_information_24, "Instant, with one card tap.")
    sh.error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
}

@Composable
private fun OtherWalletForm(o: SheetUM.OtherWallet, actions: SafeChainActions) {
    val clipboard = LocalClipboardManager.current
    Text(
        "Your card proposes it, this phone approves with your fingerprint, and it takes effect after the hold. Until then this phone stays the backup.",
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
    o.link?.let { link ->
        ConsentLink(link)
        AddressField(
            value = o.signature,
            label = "That wallet's signature",
            onChange = actions::onOtherWalletSignatureChange,
            onPaste = { clipboard.getText()?.text?.let(actions::onOtherWalletSignatureChange) },
            onScan = actions::onScanOtherWalletSignature,
        )
    }
    o.error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
}

// ------------------------------------------------------------------ bottom sheets (no text input)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(sheet: SheetUM, actions: SafeChainActions) {
    when (sheet) {
        SheetUM.None, is SheetUM.Withdraw, is SheetUM.AddFunds, is SheetUM.OtherWallet -> return
        else -> Unit
    }
    val canDismiss = sheet !is SheetUM.Working
    ModalBottomSheet(
        onDismissRequest = { if (canDismiss) actions.onCloseSheet() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { canDismiss }),
        containerColor = TangemTheme.colors.background.primary,
        contentWindowInsets = { WindowInsetsZero },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (sheet) {
                is SheetUM.Move -> MoveSheet(sheet.savingsLocked, actions)
                is SheetUM.Receive -> ReceiveSheet(sheet)
                is SheetUM.Review -> ReviewSheet(sheet, actions)
                is SheetUM.Working -> WorkingSheet(sheet)
                is SheetUM.Result -> ResultSheet(sheet, actions)
                else -> Unit
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, color: Color = TangemTheme.colors.text.primary1) =
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color)

@Composable
private fun MoveSheet(savingsLocked: Boolean, actions: SafeChainActions) {
    SheetTitle("Move money")
    MoveOption(R.drawable.ic_shield_24, Green, GreenBg, "Checking → Savings", "Instant. Protected from then on.", enabled = true, actions::onMoveToSavings)
    MoveOption(
        R.drawable.ic_tangem_card_24,
        Blue,
        BlueBg,
        "Savings → Checking",
        if (savingsLocked) "Not while Savings is locked. Unlock it first." else "Arrives after the hold; you can cancel until then.",
        enabled = !savingsLocked,
        actions::onMoveToChecking,
    )
}

@Composable
@Suppress("LongParameterList")
private fun MoveOption(@DrawableRes icon: Int, fg: Color, bg: Color, title: String, text: String, enabled: Boolean, onClick: () -> Unit) = Row(
    Modifier
        .fillMaxWidth()
        .clip(CardShape)
        .background(TangemTheme.colors.background.secondary)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(16.dp)
        .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    Badge(icon, fg, bg)
    Column(Modifier.weight(1f)) {
        Text(title, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        Text(text, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
    }
    Icon(painterResource(R.drawable.ic_chevron_right_24), null, tint = TangemTheme.colors.icon.informative)
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
                    value.split("\n").forEach { line ->
                        if (line.startsWith("0x ")) {
                            Mono(line, TangemTheme.colors.text.primary1)
                        } else {
                            Text(line, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.primary1)
                        }
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
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Badge(if (r.ok) R.drawable.ic_check_24 else R.drawable.ic_close_24, if (r.ok) Green else Red, if (r.ok) GreenBg else RedBg, size = 72.dp)
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
    SheetTitle("Receive into Savings")
    Text(
        "Anything sent to this address lands in Savings, protected. ${SepoliaConfig.NAME} test tokens (${SepoliaConfig.TOKEN_SYMBOL}) only.",
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    Qr(r.address, "Savings address QR code")
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
