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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListState
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
private const val DISABLED_ALPHA = 0.45f

/** A page like a bank's: Checking and Savings, what's on its way, what happened, and how it's protected. */
@Composable
internal fun SafeChainScreen(state: SafeChainUM, actions: SafeChainActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(TangemTheme.colors.background.secondary)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val page = state.page.takeIf { state.vault != null }
            AppBarWithBackButton(
                onBackClick = actions::onBack,
                text = when {
                    page == Source.Checking -> "Checking"
                    page == Source.Savings -> "Savings"
                    state.vault != null -> "Accounts"
                    else -> "Protected Savings"
                },
            )
            if (page != null) BackHandler(onBack = actions::onCloseAccount)
            // Each page starts at its top, and Accounts keeps its own place.
            val listState = rememberSaveable(page, saver = LazyListState.Saver) { LazyListState() }
            LazyColumn(
                state = listState,
                // Edge to edge: the keyboard doesn't resize the window, so pad for it (setup fields live here).
                modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.demo && page == null) item { DemoBanner() }
                item { HealthBanner(state.health, state.loadError) }
                val vault = state.vault
                when {
                    state.loading && vault == null -> item { Loading() }
                    vault != null && page != null -> accountPage(state, vault, page, actions)
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
    item { TotalHeader(state, v, actions) }
    item { QuickActions(v, actions) }
    item { AccountsCard(state, v, actions) }
    if (v.upgradeAvailable) item { UpgradeCard(actions) }
    v.alerts.forEach { a -> item(key = a) { Alert(a) } }
    v.backupChange?.let { b -> item { BackupChange(b, actions) } }
    if (state.upcoming.isNotEmpty()) {
        item { SectionTitle("Upcoming") }
        item { Upcoming(state.upcoming, actions) }
        item { UpcomingHint(state.upcoming) }
    }
    val held = state.assets.filter { it.inChecking || it.inSavings || it.isEth }
    if (held.isNotEmpty()) {
        item { SectionTitle("Assets") }
        item { AssetsCard(held, account = null, onClick = null) }
    }
    item { SectionTitle("Activity") }
    item { ActivityList(state.activity.take(HOME_ACTIVITY_ROWS)) }
}

/** One account: its balance and actions, what it holds, what's on its way, and its own history. */
private fun LazyListScope.accountPage(state: SafeChainUM, v: VaultUM, page: Source, actions: SafeChainActions) {
    val savings = page == Source.Savings
    item { AccountHeader(state, v, page) }
    item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val canSpend = v.isPrimary && !(savings && v.locked)
            QuickAction("Send", R.drawable.ic_arrow_up_24, enabled = canSpend, onClick = { actions.onOpenSend(page, null) })
            QuickAction("Receive", R.drawable.ic_arrow_down_24, enabled = true, onClick = actions::onOpenReceive)
            if (savings) {
                QuickAction("To Checking", R.drawable.ic_exchange_vertical_24, enabled = canSpend, onClick = actions::onMoveToChecking)
            } else {
                QuickAction("To Savings", R.drawable.ic_exchange_vertical_24, enabled = v.isPrimary, onClick = actions::onMoveToSavings)
            }
        }
    }
    val assets = state.assets.filter { if (savings) it.inSavings else it.inChecking || it.isEth }
    item { SectionTitle("Assets") }
    item {
        if (assets.isEmpty()) {
            Card {
                Text(
                    if (savings) "Nothing here yet. Move tokens from Checking to protect them." else "Nothing here yet. Receive tokens to get started.",
                    style = TangemTheme.typography.body2,
                    color = TangemTheme.colors.text.tertiary,
                )
            }
        } else {
            AssetsCard(assets, account = page, onClick = if (v.isPrimary && !(savings && v.locked)) { key -> actions.onOpenSend(page, key) } else null)
        }
    }
    if (savings) {
        item {
            Text(
                "ETH stays in Checking.",
                style = TangemTheme.typography.caption2,
                color = TangemTheme.colors.text.tertiary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
    val upcoming = state.upcoming.filter { it.source == page }
    if (upcoming.isNotEmpty()) {
        item { SectionTitle("Upcoming") }
        item { Upcoming(upcoming, actions, showSource = false) }
        item { UpcomingHint(upcoming) }
    }
    item { SectionTitle("Activity") }
    item { ActivityList(state.activity.filter { it.source == page }) }
    if (savings) {
        item { SectionTitle("Protection") }
        item { Protection(state, v, actions) }
    } else {
        item {
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable { actions.onOpenAccount(Source.Savings) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Badge(R.drawable.ic_shield_24, Green, GreenBg)
                    Text(
                        "Want a safety net? Send with a hold, or keep money in Savings.",
                        fontSize = 14.sp,
                        color = TangemTheme.colors.text.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(painterResource(R.drawable.ic_chevron_right_24), null, tint = TangemTheme.colors.icon.informative)
                }
            }
        }
    }
}

private const val HOME_ACTIVITY_ROWS = 5

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
    style = TangemTheme.typography.subtitle2,
    color = TangemTheme.colors.text.tertiary,
    modifier = Modifier.padding(start = 20.dp, top = 12.dp),
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

/** An amount in a list row: "+200 USDC" as a 16sp number and a small grey symbol. */
@Composable
private fun ListAmount(text: String, color: Color = TangemTheme.colors.text.primary1) = Row {
    Text(text.substringBefore(" "), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, modifier = Modifier.alignByBaseline())
    text.substringAfter(" ", "").takeIf { it.isNotEmpty() }?.let {
        Text(" $it", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TangemTheme.colors.text.tertiary, maxLines = 1, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = TangemTheme.colors.icon.primary1)
}

@Composable
private fun DemoBanner() = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Pill("Demo · nothing here is real", Amber, AmberBg)
}

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

/** A balance: the big headline, then (smaller) what has no price. */
@Composable
private fun Balance(b: BalanceUM, size: Int, center: Boolean = false) = Column(
    horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Amount(b.headline, size = size)
    b.extra?.let { Text(it, style = TangemTheme.typography.body2, color = TangemTheme.colors.text.tertiary) }
}

@Composable
private fun TotalHeader(state: SafeChainUM, v: VaultUM, actions: SafeChainActions) = Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    Text("Total balance", style = TangemTheme.typography.body2, color = TangemTheme.colors.text.tertiary)
    Balance(state.total, size = 40, center = true)
    Box(Modifier.clip(PillShape).clickable { actions.onOpenAccount(Source.Savings) }) {
        when {
            v.locked -> Pill("Savings locked", Red, RedBg, R.drawable.ic_lock_24)
            v.backupIsThisPhone -> Pill("Protected · backup on this phone", Green, GreenBg, R.drawable.ic_shield_24)
            else -> Pill("Protected · backup wallet", Green, GreenBg, R.drawable.ic_shield_24)
        }
    }
}

@Composable
private fun AccountsCard(state: SafeChainUM, v: VaultUM, actions: SafeChainActions) = Card(padding = 0.dp) {
    val checkingAssets = state.assets.filter { it.inChecking }
    val savingsAssets = state.assets.filter { it.inSavings }
    AccountRow(
        icon = R.drawable.ic_tangem_card_24,
        tint = Blue,
        tintBg = BlueBg,
        name = "Checking",
        detail = "Instant · ${count(checkingAssets.size)}",
        balance = state.checking,
        tokens = checkingAssets.map { it.symbol },
        note = null,
        onClick = { actions.onOpenAccount(Source.Checking) },
    )
    Box(Modifier.padding(start = 72.dp, end = 16.dp)) { Divider() }
    AccountRow(
        icon = if (v.locked) R.drawable.ic_lock_24 else R.drawable.ic_shield_24,
        tint = if (v.locked) Red else Green,
        tintBg = if (v.locked) RedBg else GreenBg,
        name = "Savings",
        detail = if (v.locked) "Locked · nothing can leave" else "${short(v.hold)} hold · ${count(savingsAssets.size)}",
        balance = v.balance,
        tokens = savingsAssets.map { it.symbol },
        note = v.available,
        onClick = { actions.onOpenAccount(Source.Savings) },
    )
    state.previous.forEach { p ->
        Box(Modifier.padding(start = 72.dp, end = 16.dp)) { Divider() }
        PreviousRow(p, actions)
    }
}

/** Offered while Savings is an older version without "release now". */
@Composable
private fun UpgradeCard(actions: SafeChainActions) = Card {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Badge(R.drawable.ic_fingerprint_24, Green, GreenBg)
        Column(Modifier.weight(1f)) {
            Text("New: release now with both keys", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
            Text(
                "Move money from Savings to Checking without the wait: your card asks, your fingerprint agrees. Payments to anyone else still wait.",
                style = TangemTheme.typography.caption2,
                color = TangemTheme.colors.text.tertiary,
            )
        }
    }
    SecondaryButton(text = "Upgrade Savings", onClick = actions::onUpgradeSavings, modifier = Modifier.fillMaxWidth())
}

/** An older Savings that still holds money: one row, with a small Move (its hold applies on the way out). */
@Composable
private fun PreviousRow(p: PreviousVaultUM, actions: SafeChainActions) = Row(
    Modifier.fillMaxWidth().padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    val moving = p.moving.firstOrNull()
    Badge(R.drawable.ic_exchange_vertical_24, Blue, BlueBg, size = 42.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Old Savings", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        Text(
            when {
                moving == null -> "Move it into Savings"
                moving.isFinal -> "Ready to bring over"
                else -> "Moving · ${moving.countdown?.replaceFirst("Arrives in", "in").orEmpty()}"
            },
            style = TangemTheme.typography.caption2,
            color = TangemTheme.colors.text.tertiary,
            maxLines = 1,
        )
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.balance, style = TangemTheme.typography.body2, fontWeight = FontWeight.SemiBold, color = TangemTheme.colors.text.primary1, maxLines = 1)
        when {
            moving?.canRelease == true -> CompactButton("Bring now", Green, GreenBg) { actions.onReleaseFromPrevious(p.address, moving.id) }
            // Like every other move: cancellable until it arrives.
            moving?.canCancel == true -> CompactButton("Cancel", Red, RedBg) { actions.onCancelFromPrevious(p.address, moving.id) }
            p.canMove && moving == null -> CompactButton("Move", TangemTheme.colors.text.primary1, TangemTheme.colors.button.secondary) {
                actions.onMoveFromPrevious(p.address)
            }
        }
    }
}

private fun count(n: Int) = if (n == 1) "1 asset" else "$n assets"

@Composable
@Suppress("LongParameterList")
private fun AccountRow(
    @DrawableRes icon: Int,
    tint: Color,
    tintBg: Color,
    name: String,
    detail: String,
    balance: BalanceUM,
    tokens: List<String>,
    note: String?,
    onClick: () -> Unit,
) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    Badge(icon, tint, tintBg, size = 42.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        Text(detail, style = TangemTheme.typography.caption2, color = if (tint == Red) Red else TangemTheme.colors.text.tertiary)
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Amount(balance.headline, size = 18)
        when {
            note != null -> Text(note, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            tokens.isNotEmpty() -> TokenStack(tokens)
        }
    }
    Icon(painterResource(R.drawable.ic_chevron_right_24), null, tint = TangemTheme.colors.icon.informative, modifier = Modifier.size(20.dp))
}

/** Up to three small overlapping token icons. */
@Composable
private fun TokenStack(symbols: List<String>) = Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
    symbols.take(3).forEach { s ->
        Box(Modifier.size(20.dp).clip(CircleShape).background(TangemTheme.colors.background.primary).padding(1.5.dp)) {
            TokenIcon(s, 17.dp)
        }
    }
}

/** ETH and USDC use Tangem's own icons; any other token gets a letter badge. */
@Composable
private fun TokenIcon(symbol: String, size: Dp = 40.dp) {
    val res = when (symbol) {
        "ETH" -> R.drawable.img_eth_22
        "USDC" -> R.drawable.img_usdc_16
        else -> null
    }
    if (res != null) {
        Image(painterResource(res), symbol, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Amber), contentAlignment = Alignment.Center) {
            Text(symbol.take(1), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
        }
    }
}

/**
 * Tokens, the way Tangem lists them: icon and name on the left, value and amount on the right. [account] null is
 * both accounts together (Home), where the second line says what part sits in Savings.
 */
@Composable
private fun AssetsCard(assets: List<AssetUM>, account: Source?, onClick: ((String) -> Unit)?) = Card(padding = 0.dp) {
    assets.forEachIndexed { i, a ->
        if (i > 0) Box(Modifier.padding(start = 72.dp, end = 16.dp)) { Divider() }
        val amount = when (account) {
            Source.Checking -> a.checking
            Source.Savings -> a.savings ?: "0"
            null -> a.total
        }
        val fiat = when (account) {
            Source.Checking -> a.checkingFiat
            Source.Savings -> a.savingsFiat
            null -> a.totalFiat
        }
        val sub = when {
            a.isEth -> "Pays network fees"
            // Unpriced tokens already show the total on the right, so only the Savings part is news here.
            account == null && a.inSavings && a.inChecking && fiat == null -> "${a.savings} in Savings"
            account == null && a.inSavings && a.inChecking -> "${a.checking} Checking · ${a.savings} Savings"
            account == null && a.inSavings -> "All in Savings"
            account == Source.Savings && a.partlyHeld -> "${a.savingsAvailable} available"
            fiat != null -> "$1.00"
            else -> "Test token"
        }
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick(a.key) } else Modifier).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TokenIcon(a.symbol)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.name, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (fiat != null) {
                    Text(fiat, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1, maxLines = 1)
                    Text("$amount ${a.symbol}", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary, maxLines = 1)
                } else {
                    ListAmount("$amount ${a.symbol}")
                }
            }
        }
    }
}

/** The top of an account page: its badge, balance, and how it behaves. */
@Composable
private fun AccountHeader(state: SafeChainUM, v: VaultUM, page: Source) = Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    val savings = page == Source.Savings
    when {
        !savings -> Badge(R.drawable.ic_tangem_card_24, Blue, BlueBg, size = 56.dp)
        v.locked -> Badge(R.drawable.ic_lock_24, Red, RedBg, size = 56.dp)
        else -> Badge(R.drawable.ic_shield_24, Green, GreenBg, size = 56.dp)
    }
    val balance = if (savings) v.balance else state.checking
    Amount(balance.headline, size = 40)
    // One quiet line under the number: what has no price, and what can leave now.
    listOfNotNull(balance.extra, if (savings) v.available?.let { "$it now" } else null)
        .takeIf { it.isNotEmpty() }
        ?.let { Text(it.joinToString(" · "), style = TangemTheme.typography.body2, color = TangemTheme.colors.text.tertiary, textAlign = TextAlign.Center) }
    when {
        !savings -> Pill("Instant · your Tangem card", Blue, BlueBg, R.drawable.ic_tangem_card_24)
        v.locked -> Pill("Locked · nothing can leave", Red, RedBg, R.drawable.ic_lock_24)
        else -> Pill("Every payment waits ${v.hold}", Green, GreenBg, R.drawable.ic_circle_clock_24)
    }
    val address = if (savings) v.address else state.accountAddress
    if (address != null) {
        val clipboard = LocalClipboardManager.current
        Row(
            Modifier.clip(PillShape).clickable { clipboard.setText(AnnotatedString(address)) }.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${address.take(6)}…${address.takeLast(4)}", style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            Icon(painterResource(R.drawable.ic_copy_24), "Copy address", tint = TangemTheme.colors.icon.informative, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun QuickActions(v: VaultUM, actions: SafeChainActions) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    QuickAction("Send", R.drawable.ic_arrow_up_24, enabled = v.isPrimary, onClick = actions::onOpenWithdraw)
    QuickAction("Receive", R.drawable.ic_arrow_down_24, enabled = true, onClick = actions::onOpenReceive)
    QuickAction("Move", R.drawable.ic_exchange_vertical_24, enabled = v.isPrimary, onClick = actions::onOpenMove)
}

/** A round action, dark like Tangem's main screen; white is kept for the one primary button on a page. */
@Composable
private fun QuickAction(label: String, @DrawableRes icon: Int, enabled: Boolean, onClick: () -> Unit) = Column(
    Modifier.clip(CardShape).clickable(enabled = enabled, onClick = onClick).padding(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    val fg = if (enabled) TangemTheme.colors.icon.primary1 else TangemTheme.colors.text.disabled
    Box(Modifier.size(56.dp).clip(CircleShape).background(TangemTheme.colors.button.secondary), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(24.dp))
    }
    Text(label, style = TangemTheme.typography.button, color = if (enabled) TangemTheme.colors.text.primary1 else TangemTheme.colors.text.disabled)
}

@Composable
private fun Upcoming(items: List<WithdrawalUM>, actions: SafeChainActions, showSource: Boolean = true) = Card {
    val uri = LocalUriHandler.current
    items.forEachIndexed { i, w ->
        if (i > 0) Divider()
        Row(
            Modifier.clickable { runCatching { uri.openUri(w.url) } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (w.incoming) Badge(R.drawable.ic_arrow_down_24, Green, GreenBg) else Badge(R.drawable.ic_arrow_up_24, Amber, AmberBg)
            Column(Modifier.weight(1f)) {
                Text(
                    if (w.incoming) "From ${w.toLabel}" else "To ${w.toLabel}",
                    style = TangemTheme.typography.subtitle1,
                    color = TangemTheme.colors.text.primary1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        w.countdown?.replaceFirst("Arrives in", "In") ?: "Ready to deliver",
                        when {
                            !showSource -> null
                            w.incoming -> "to Checking"
                            w.source == Source.Checking -> "from Checking"
                            else -> "from Savings"
                        },
                    ).joinToString(" · "),
                    style = TangemTheme.typography.caption2,
                    color = if (w.isFinal) Green else TangemTheme.colors.text.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ListAmount((if (w.incoming) "+" else "−") + w.amount, if (w.incoming) Green else TangemTheme.colors.text.primary1)
        }
        // Progress and the action share one line, so every row is the same height.
        Row(Modifier.padding(start = 52.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (!w.isFinal) Progress(w.progress, if (w.incoming) Green else Amber, if (w.incoming) GreenBg else AmberBg)
            }
            // A move to your own Checking: both keys can bring it in now.
            if (w.canReleaseNow) CompactButton("Release now", Green, GreenBg) { actions.onReleaseNow(w.id) }
            when {
                w.canCancel -> CompactButton("Cancel", Red, RedBg) {
                    if (w.source == Source.Checking) actions.onCancelPayment(w.id) else actions.onCancelWithdrawal(w.id)
                }
                w.canRelease -> CompactButton("Deliver", Green, GreenBg) {
                    if (w.source == Source.Checking) actions.onSettlePayment(w.id) else actions.onReleaseWithdrawal(w.id)
                }
            }
        }
        if (w.disputed) Text("⚠ Your data sources disagree about this payment", color = Red, style = TangemTheme.typography.caption2)
    }
}

/** What the Upcoming card means, under it, while anything in it can be cancelled. */
@Composable
private fun UpcomingHint(items: List<WithdrawalUM>) {
    if (items.none { it.canCancel }) return
    Text(
        "Didn't make one of these? Cancel it before it arrives and the money goes straight back to the account it came from.",
        style = TangemTheme.typography.caption2,
        color = TangemTheme.colors.text.tertiary,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun CompactButton(text: String, fg: Color, bg: Color, onClick: () -> Unit) = Box(
    Modifier.padding(start = 12.dp).height(28.dp).clip(PillShape).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
) {
    Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Progress(value: Float, color: Color, track: Color) = Box(Modifier.fillMaxWidth().height(4.dp).clip(PillShape).background(track)) {
    Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(4.dp).clip(PillShape).background(color))
}

@Composable
private fun SmallButton(text: String, fg: Color, bg: Color, @DrawableRes icon: Int, onClick: () -> Unit) = Row(
    Modifier.clip(PillShape).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
) {
    Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(14.dp))
    Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                ActivityUM.Tone.Neutral -> TangemTheme.colors.icon.informative to TangemTheme.colors.button.secondary
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
                    a.detail.ifBlank { a.time },
                    style = TangemTheme.typography.caption2,
                    color = TangemTheme.colors.text.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Bank style: the amount, and when it happened under it.
            Column(horizontalAlignment = Alignment.End) {
                a.amount?.let { ListAmount(it, if (a.tone == ActivityUM.Tone.Good) Green else TangemTheme.colors.text.primary1) }
                if (a.detail.isNotBlank()) Text(a.time, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary, maxLines = 1)
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
            TextButton(text = "Check it on SafeChain Explorer", onClick = { runCatching { uri.openUri("${SepoliaConfig.EXPLORER}/vault/${v.address}") } })
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
private fun Footer(state: SafeChainUM) = Text(
    text = if (state.demo) "Demo · pretend data, no network" else "${SepoliaConfig.NAME} test network · test tokens, no real value",
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
        is SheetUM.Send -> "Send"
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
                is SheetUM.Send -> SendPage(sheet, actions)
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
            is SheetUM.Send -> Triple(
                "Review",
                actions::onContinue,
                sheet.amount.isNotBlank() && sheet.to.isNotBlank() &&
                    (sheet.from == Source.Savings || sheet.asset?.isEth == true || sheet.hold != HOLD_CUSTOM || sheet.customHours.isNotBlank()),
            )
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
            Badge(R.drawable.ic_arrow_down_24, TangemTheme.colors.icon.informative, TangemTheme.colors.button.secondary, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text("To $to", style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
                Text(toNote, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
            }
        }
    }
}

/** A quiet label over a form group, matching the section titles on Accounts. */
@Composable
private fun FormLabel(text: String) = Text(text, style = TangemTheme.typography.subtitle2, color = TangemTheme.colors.text.tertiary)

/** The token to move: one chip per token the account holds, with how much of it there is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenChoice(choices: List<AssetUM>, selected: String, amountOf: (AssetUM) -> String, onPick: (String) -> Unit) {
    FormLabel("Asset")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { a ->
            val on = a.key == selected
            Row(
                Modifier
                    .clip(PillShape)
                    .background(TangemTheme.colors.background.primary)
                    .border(1.5.dp, if (on) TangemTheme.colors.icon.primary1 else Color.Transparent, PillShape)
                    .clickable { onPick(a.key) }
                    .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TokenIcon(a.symbol, 26.dp)
                Column {
                    Text(a.symbol, style = TangemTheme.typography.button, color = TangemTheme.colors.text.primary1)
                    Text(amountOf(a), style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AmountField(amount: String, max: String, symbol: String, onChange: (String) -> Unit, onMax: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    OutlinedTextField(
        value = amount,
        onValueChange = onChange,
        label = { Text("Amount") },
        suffix = { Text(symbol) },
        colors = fieldColors(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        textStyle = TangemTheme.typography.h3,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$max $symbol available",
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
    TokenChoice(sh.assets.filter { it.inSavings }, sh.token, { it.savingsAvailable ?: it.savings ?: "0" }, actions::onTokenChange)
    AmountField(sh.amount, sh.max, sh.asset?.symbol.orEmpty(), actions::onAmountChange, actions::onMax)
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

private const val HOLD_CUSTOM = "custom"

/** Hold choices for a Checking send: hours → label. "0" is instant (no escrow, can't be undone). */
private val HOLDS = listOf("1" to "1 hour", "24" to "1 day", "72" to "3 days", "168" to "1 week", HOLD_CUSTOM to "Custom", "0" to "Instant")

/**
 * Send from either account. Checking sends instantly or with a hold you choose (through the escrow, cancellable
 * until it ends); Savings always uses its own hold. One sentence under the choices says exactly what happens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendPage(sh: SheetUM.Send, actions: SafeChainActions) {
    val clipboard = LocalClipboardManager.current
    FormLabel("From")
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(TangemTheme.colors.background.primary),
    ) {
        AccountChoice(
            icon = R.drawable.ic_tangem_card_24,
            tint = Blue,
            tintBg = BlueBg,
            name = "Checking",
            detail = sh.checkingTotal,
            selected = sh.from == Source.Checking,
            enabled = true,
            onClick = { actions.onSendFrom(Source.Checking) },
        )
        Box(Modifier.padding(start = 68.dp)) { Divider() }
        AccountChoice(
            icon = R.drawable.ic_shield_24,
            tint = Green,
            tintBg = GreenBg,
            name = "Savings",
            detail = when {
                sh.savingsHold == null -> "Open Savings first"
                sh.savingsLocked -> "Locked"
                else -> "${sh.savingsTotal} · ${sh.savingsHold} hold"
            },
            selected = sh.from == Source.Savings,
            enabled = sh.savingsHold != null && !sh.savingsLocked,
            onClick = { actions.onSendFrom(Source.Savings) },
        )
    }

    val asset = sh.asset
    val eth = asset?.isEth == true
    TokenChoice(
        sh.choices,
        sh.token,
        { if (sh.from == Source.Checking) it.checking else it.savingsAvailable ?: it.savings ?: "0" },
        actions::onTokenChange,
    )
    AmountField(sh.amount, sh.max, asset?.symbol.orEmpty(), actions::onAmountChange, actions::onMax)
    AddressField(
        value = sh.to,
        label = "To address",
        onChange = actions::onDestinationChange,
        onPaste = { clipboard.getText()?.text?.let(actions::onDestinationChange) },
        onScan = actions::onScanDestination,
    )

    if (sh.from == Source.Checking && !eth) {
        FormLabel("Hold")
        // Every choice visible at once (wrapping), with Instant last: protection is the default.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HOLDS.forEach { (value, label) ->
                val on = sh.hold == value
                Text(
                    label,
                    color = if (on) TangemTheme.colors.text.primary2 else TangemTheme.colors.text.primary1,
                    style = TangemTheme.typography.button,
                    modifier = Modifier.clip(PillShape)
                        .background(if (on) TangemTheme.colors.button.primary else TangemTheme.colors.background.primary)
                        .clickable { actions.onHoldChange(value) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        if (sh.hold == HOLD_CUSTOM) {
            OutlinedTextField(
                value = sh.customHours,
                onValueChange = actions::onCustomHoursChange,
                label = { Text("Hold for") },
                suffix = { Text(sh.customUnit.label) },
                supportingText = { Text("5 minutes to 30 days. Decimals work, like 0.5 hours.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            // Unit as a segmented control: one row, the chosen one filled.
            Row(
                Modifier.fillMaxWidth().clip(PillShape).background(TangemTheme.colors.background.primary).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HoldUnit.entries.forEach { unit ->
                    val on = sh.customUnit == unit
                    Text(
                        unit.label.replaceFirstChar(Char::uppercase),
                        color = if (on) TangemTheme.colors.text.primary2 else TangemTheme.colors.text.primary1,
                        style = TangemTheme.typography.button,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(PillShape)
                            .background(if (on) TangemTheme.colors.button.primary else Color.Transparent)
                            .clickable { actions.onCustomUnitChange(unit) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }

    val customSeconds = holdSeconds(sh.customHours, sh.customUnit)?.takeIf { it in MIN_HOLD_SECONDS..MAX_HOLD_SECONDS }
    val holdLabel = when {
        sh.from == Source.Savings -> sh.savingsHold ?: "the Savings hold"
        sh.hold == HOLD_CUSTOM -> customSeconds?.let(::holdText) ?: "the hold you choose"
        else -> HOLDS.firstOrNull { it.first == sh.hold }?.second ?: ""
    }
    val what = sh.amount.takeIf { it.isNotBlank() }?.let { "the $it ${asset?.symbol}" } ?: "the money"
    when {
        eth -> Warning("ETH always sends instantly and can't be undone. For a payment you can cancel, send a token like USDC.")
        sh.from == Source.Checking && sh.hold == "0" ->
            Warning("Arrives immediately and can't be undone. Pick a hold if you might want to cancel.")
        else -> Note(
            R.drawable.ic_circle_clock_24,
            "Arrives in $holdLabel. Until then you can cancel it, and $what comes straight back to " +
                (if (sh.from == Source.Checking) "Checking." else "Savings."),
        )
    }
    sh.error?.let { Text(it, color = Red, style = TangemTheme.typography.body2) }
}

@Composable
@Suppress("LongParameterList")
private fun AccountChoice(
    @DrawableRes icon: Int,
    tint: Color,
    tintBg: Color,
    name: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) = Row(
    Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp)
        .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    Badge(icon, tint, tintBg, size = 38.dp)
    Column(Modifier.weight(1f)) {
        Text(name, style = TangemTheme.typography.subtitle1, color = TangemTheme.colors.text.primary1)
        Text(detail, style = TangemTheme.typography.caption2, color = TangemTheme.colors.text.tertiary)
    }
    // A radio mark: filled ring when chosen.
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) TangemTheme.colors.icon.primary1 else TangemTheme.colors.stroke.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(TangemTheme.colors.icon.primary1))
    }
}

@Composable
private fun Warning(text: String) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AmberBg).padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    Icon(painterResource(R.drawable.ic_warning_20), null, tint = Amber, modifier = Modifier.size(18.dp))
    Text(text, style = TangemTheme.typography.body2, color = Amber)
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
    TokenChoice(sh.assets.filter { it.inChecking }, sh.token, { it.checking }, actions::onTokenChange)
    AmountField(sh.amount, sh.max, sh.asset?.symbol.orEmpty(), actions::onAmountChange, actions::onMax)
    Note(R.drawable.ic_information_24, "Instant, with one card tap. ETH stays in Checking to pay network fees.")
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
        // Forms are full pages (FormPage), not sheets.
        SheetUM.None, is SheetUM.Send, is SheetUM.Withdraw, is SheetUM.AddFunds, is SheetUM.OtherWallet -> return
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
                is SheetUM.Receive -> ReceiveSheet(sheet, actions)
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
                // A warning row ("⚠ Look-alike address") reads in red, so it can't be skimmed past.
                val warn = label.startsWith("⚠")
                Column {
                    Text(label, style = TangemTheme.typography.caption2, color = if (warn) Red else TangemTheme.colors.text.tertiary)
                    value.split("\n").forEach { line ->
                        if (line.startsWith("0x ")) {
                            Mono(line, TangemTheme.colors.text.primary1)
                        } else {
                            Text(line, style = TangemTheme.typography.body2, color = if (warn) Red else TangemTheme.colors.text.primary1)
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
    r.share?.let { message ->
        val context = LocalContext.current
        PrimaryButton(
            text = "Share with recipient",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                context.startActivity(Intent.createChooser(send, "Tell the recipient to check"))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (r.share != null) {
        SecondaryButton(text = "Done", onClick = actions::onCloseSheet, modifier = Modifier.fillMaxWidth())
    } else {
        PrimaryButton(text = "Done", onClick = actions::onCloseSheet, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReceiveSheet(r: SheetUM.Receive, actions: SafeChainActions) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val savings = r.into == Source.Savings && r.savings != null
    SheetTitle("Receive")
    if (r.savings != null) {
        // Which account the money lands in: one address each.
        Row(
            Modifier.fillMaxWidth().clip(PillShape).background(TangemTheme.colors.background.secondary).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(Source.Checking to "Checking", Source.Savings to "Savings").forEach { (s, label) ->
                val on = r.into == s
                Text(
                    label,
                    color = if (on) TangemTheme.colors.text.primary2 else TangemTheme.colors.text.primary1,
                    style = TangemTheme.typography.button,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(PillShape)
                        .background(if (on) TangemTheme.colors.button.primary else Color.Transparent)
                        .clickable { actions.onReceiveInto(s) }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
    Text(
        if (savings) {
            "Tokens sent here land in Savings, protected from the moment they arrive."
        } else {
            "Your card's address. ETH, USDC, PWTEST or any ${SepoliaConfig.NAME} token lands in Checking."
        },
        style = TangemTheme.typography.body2,
        color = TangemTheme.colors.text.secondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        (if (savings) listOf("USDC", "PWTEST") else listOf("ETH", "USDC", "PWTEST")).forEach { TokenIcon(it, 28.dp) }
    }
    if (savings) Warning("Tokens only. Don't send ETH to Savings: it can't hold ETH. Send ETH to Checking.")
    Qr(r.address, if (savings) "Savings address QR code" else "Checking address QR code")
    SelectionContainer { Mono(groupedAddress(r.address), TangemTheme.colors.text.primary1) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            text = "Share",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.address)
                context.startActivity(Intent.createChooser(send, "Share your address"))
            },
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(text = "Copy", onClick = { clipboard.setText(AnnotatedString(r.address)) }, modifier = Modifier.weight(1f))
    }
}
