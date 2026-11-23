package app.deskpanel

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** How long without a touch before the lyrics / time-lapse screen takes over while music plays. */
private const val IDLE_MS = 12_000L

private fun stateColor(state: String): Color = when (state) {
    "working" -> ClaudeOrange
    "waiting" -> Amber
    "input" -> Teal
    "done" -> Green
    else -> Muted
}

@Composable
fun PanelScreen(vm: PanelViewModel) {
    val s by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val now by produceState(SystemClock.uptimeMillis()) {
        while (true) {
            delay(500)
            value = SystemClock.uptimeMillis()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.uptimeMillis()
                    }
                }
            },
    ) {
        val stats = s.stats
        if (stats == null || s.conn == Conn.Pairing || s.conn == Conn.Rejected) {
            ConnectView(s, onRetry = vm::retryPairing, onAddress = vm::useAddress)
            return@Box
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Header(stats, s.conn, tab, onTab = { tab = it })
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) LaptopTab(s, stats, vm) else ClaudeTab(stats, vm)
            }
        }

        val session = stats.sessions.firstOrNull { it.pending != null }
        val pending = session?.pending
        val media = stats.media
        val ambient = media != null && media.playing && pending == null && now - lastTouch > IDLE_MS
        AnimatedVisibility(visible = ambient, enter = fadeIn(tween(900)), exit = fadeOut(tween(250))) {
            if (media != null) AmbientView(media, s.art, s.lyrics, s.mediaAt)
        }
        if (session != null && pending != null) {
            val index = stats.sessions.indexOf(session)
            PermissionOverlay(session, index, pending, stats.clockOffset) { decision -> vm.decide(pending.id, decision) }
        }
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(st: Stats, conn: Conn, tab: Int, onTab: (Int) -> Unit) {
    val waiting = st.sessions.count { it.state == "waiting" || it.state == "input" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (conn == Conn.Connected) Green else Amber))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(st.host.uppercase(), fontFamily = PixelHead, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (conn == Conn.Connected) "LINK OK · WI-FI · UP ${uptime(st.uptime).uppercase()}" else "RECONNECTING…",
                fontSize = 16.sp, color = Muted, maxLines = 1,
            )
        }
        TabButton("LAPTOP", tab == 0) { onTab(0) }
        Spacer(Modifier.width(8.dp))
        TabButton(if (waiting > 0) "CLAUDE ($waiting!)" else "CLAUDE ${st.sessions.size}", tab == 1, alert = waiting > 0) { onTab(1) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Clock() }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, alert: Boolean = false, onClick: () -> Unit) {
    val color = when {
        alert -> Amber
        selected -> TextMain
        else -> Muted
    }
    Box(
        Modifier
            .clip(ButtonShape)
            .background(if (selected) Track else Color.Transparent)
            .border(2.dp, if (selected) color else Track, ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, fontFamily = PixelHead, fontSize = 9.sp, color = color)
    }
}

@Composable
private fun Clock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val date = remember { SimpleDateFormat("EEE dd MMM", Locale.getDefault()) }
    Column(horizontalAlignment = Alignment.End) {
        Text(time.format(Date(now)), fontSize = 38.sp, lineHeight = 34.sp)
        Text(date.format(Date(now)).uppercase(), fontFamily = PixelHead, fontSize = 7.sp, color = Muted)
    }
}

// ---------------------------------------------------------------- laptop tab

@Composable
private fun LaptopTab(s: PanelState, st: Stats, vm: PanelViewModel) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SystemPanel(st, s.cpuHistory, Modifier.weight(1.35f).fillMaxWidth())
            NetPanel(st, s.downHistory, s.upHistory, Modifier.weight(1f).fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MediaPanel(st.media, s.art, s.lyrics != null, vm, Modifier.weight(1f).fillMaxWidth())
            st.volume?.let { VolumePanel(it, vm) }
        }
    }
}

@Composable
private fun SystemPanel(st: Stats, cpuHistory: List<Float>, modifier: Modifier) {
    PixelPanel("System", Blue, modifier, right = "1s refresh") {
        val peak = cpuHistory.maxOrNull() ?: st.cpu
        val avg = if (cpuHistory.isEmpty()) st.cpu else cpuHistory.average().toFloat()
        StatRow("CPU", st.cpu / 100f, "${st.cpu.roundToInt()}%", "AVG ${avg.roundToInt()}% · PEAK ${peak.roundToInt()}% (60S)", Blue)
        val ram = if (st.ramTotal > 0) st.ramUsed.toFloat() / st.ramTotal else 0f
        StatRow("MEM", ram, "${(ram * 100).roundToInt()}%", "${gb(st.ramUsed)} USED OF ${gb(st.ramTotal)}", Purple)
        val disk = if (st.diskTotal > 0) st.diskUsed.toFloat() / st.diskTotal else 0f
        StatRow("DSK", disk, "${(disk * 100).roundToInt()}%", "${gb(st.diskTotal - st.diskUsed)} FREE OF ${gb(st.diskTotal)}", Teal)
        st.battery?.let { b ->
            val color = if (b.percent <= 20 && !b.charging) Red else Green
            StatRow("BAT", b.percent / 100f, "${b.percent}%", if (b.charging) "AC POWER · CHARGING" else "ON BATTERY", color)
        }
        Spacer(Modifier.height(4.dp))
        BlockGraph(cpuHistory.map { it / 100f }, Blue, Modifier.fillMaxWidth().weight(1f).heightIn(max = 30.dp))
    }
}

@Composable
private fun StatRow(label: String, fraction: Float, value: String, detail: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = PixelHead, fontSize = 9.sp, color = color, modifier = Modifier.width(46.dp))
        Column(Modifier.weight(1f)) {
            SegBar(fraction, color, Modifier.fillMaxWidth().height(10.dp), segments = 24)
            Text(detail, fontSize = 15.sp, lineHeight = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, fontSize = 28.sp, lineHeight = 28.sp, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
    }
}

@Composable
private fun NetPanel(st: Stats, down: List<Long>, up: List<Long>, modifier: Modifier) {
    val max = maxOf((down + up).maxOrNull() ?: 0L, 64L * 1024).toFloat()
    PixelPanel("Network", Amber, modifier, right = "peak ${speed(max.toLong())}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DN", fontFamily = PixelHead, fontSize = 8.sp, color = Blue)
            Spacer(Modifier.width(6.dp))
            Text(speed(st.netDown), fontSize = 30.sp)
            Spacer(Modifier.width(22.dp))
            Text("UP", fontFamily = PixelHead, fontSize = 8.sp, color = Amber)
            Spacer(Modifier.width(6.dp))
            Text(speed(st.netUp), fontSize = 30.sp)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            BlockGraph(down.map { it / max }, Blue, Modifier.fillMaxSize())
            BlockGraph(up.map { it / max }, Amber.copy(alpha = 0.8f), Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MediaPanel(m: Media?, art: ImageBitmap?, hasLyrics: Boolean, vm: PanelViewModel, modifier: Modifier) {
    val right = when {
        m == null -> "idle"
        hasLyrics -> "lyrics ready"
        else -> "time-lapse ready"
    }
    PixelPanel("Now playing", Pink, modifier, right = right) {
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Muted, modifier = Modifier.size(36.dp))
                    Text("NOTHING PLAYING", fontFamily = PixelHead, fontSize = 8.sp, color = Muted)
                    Text("PLAY MUSIC ON THE LAPTOP", fontSize = 16.sp, color = Muted)
                }
            }
            return@PixelPanel
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val artMod = Modifier.size(78.dp).clip(ButtonShape).border(2.dp, Pink.copy(alpha = 0.6f), ButtonShape)
            if (art != null) {
                Image(art, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.None, modifier = artMod)
            } else {
                Box(artMod.background(Track), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Muted, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title.ifEmpty { "UNKNOWN TRACK" }, fontSize = 24.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.artist.isNotEmpty()) Text(m.artist, fontSize = 18.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(if (m.playing) "▶ playing" else "❚❚ paused", if (m.playing) Green else Muted)
                    if (m.app.isNotEmpty()) Tag(m.app, Blue)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (m.duration > 0) {
            SegBar((m.position / m.duration).toFloat(), Pink, Modifier.fillMaxWidth().height(8.dp), segments = 40)
            Row(Modifier.fillMaxWidth()) {
                Text(dur(m.position), fontSize = 16.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                Text("-" + dur(m.duration - m.position), fontSize = 16.sp, color = Muted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton({ vm.media("prev") }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", tint = TextMain)
            }
            PixelButton({ vm.media("playpause") }, Modifier.weight(1.4f), bg = Pink.copy(alpha = 0.85f)) {
                Icon(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text(if (m.playing) "PAUSE" else "PLAY", fontFamily = PixelHead, fontSize = 9.sp, color = Color.Black)
            }
            PixelButton({ vm.media("next") }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = TextMain)
            }
        }
    }
}

@Composable
private fun VolumePanel(v: Vol, vm: PanelViewModel) {
    var local by remember { mutableFloatStateOf(v.level.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(v.level) { if (!dragging) local = v.level.toFloat() }
    fun set(fraction: Float) {
        local = (fraction * 100f).coerceIn(0f, 100f)
        vm.setVolume(local.roundToInt())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(CardBg)
            .border(2.dp, Teal.copy(alpha = 0.5f), PanelShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelButton({ vm.mute(!v.muted) }, bg = if (v.muted) Red.copy(alpha = 0.8f) else Track) {
            Icon(
                if (v.muted || local < 1f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                "Mute", tint = TextMain,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (v.muted) "VOLUME · MUTED" else "VOLUME", fontFamily = PixelHead, fontSize = 7.sp, color = Teal)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(Unit) { detectTapGestures { o -> set(o.x / size.width) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false; vm.setVolume(local.roundToInt()) },
                            onDragCancel = { dragging = false },
                        ) { change, _ -> set(change.position.x / size.width) }
                    },
            ) {
                SegBar(local / 100f, if (v.muted) Muted else Teal, Modifier.fillMaxSize(), segments = 25)
            }
        }
        Text("${local.roundToInt()}%", fontSize = 30.sp, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
    }
}

// ---------------------------------------------------------------- claude tab

@Composable
private fun ClaudeTab(st: Stats, vm: PanelViewModel) {
    val working = st.sessions.count { it.state == "working" }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelPanel(
            "Claude arena",
            ClaudeOrange,
            Modifier.weight(1.35f).fillMaxHeight(),
            right = when {
                st.sessions.isEmpty() -> "no open sessions"
                working >= 2 -> "$working brawling"
                else -> "${st.sessions.size} open"
            },
        ) {
            Box(Modifier.fillMaxSize().clip(ButtonShape)) {
                Arena(st.sessions, Modifier.fillMaxSize())
                if (st.sessions.isEmpty()) {
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NO CLAUDE SESSIONS OPEN", fontFamily = PixelHead, fontSize = 9.sp)
                        Text("Start Claude Code on the laptop and it shows up here", fontSize = 18.sp, color = Muted)
                    }
                }
            }
        }
        PixelPanel("Leaderboard", Amber, Modifier.weight(1f).fillMaxHeight(), right = "by xp") {
            val ranked = st.sessions.withIndex().sortedByDescending { it.value.stats.xp }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(ranked, key = { _, it -> it.value.id }) { rank, item ->
                    LeaderRow(rank + 1, item.value, item.index, st.clockOffset)
                }
            }
        }
    }
}

@Composable
private fun LeaderRow(rank: Int, s: Session, colorIndex: Int, clockOffset: Long) {
    val color = stateColor(s.state)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val colors = mascotColors(colorIndex)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .background(Bg.copy(alpha = 0.6f))
            .then(if (s.state == "waiting") Modifier.border(2.dp, Amber, ButtonShape) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#$rank", fontFamily = PixelHead, fontSize = 10.sp, color = if (rank == 1) Amber else Muted, modifier = Modifier.width(34.dp))
        Mascot(s.state, Modifier.width(34.dp).height(40.dp), colors)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name.uppercase(), fontFamily = PixelHead, fontSize = 8.sp, color = colors.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text("LV${s.stats.level}", fontFamily = PixelHead, fontSize = 7.sp, color = Muted)
            }
            SegBar(s.stats.levelProgress, colors.body, Modifier.fillMaxWidth().padding(vertical = 3.dp).height(6.dp), segments = 16)
            Text(
                "${stateLabel(s.state).uppercase()} ${ago(now + clockOffset - s.since).uppercase()} · ${s.stats.xp} XP",
                fontSize = 15.sp, color = color, maxLines = 1,
            )
            Text(
                "TOOLS ${s.stats.tools}  EDITS ${s.stats.edits}  CMDS ${s.stats.commands}  ASKS ${s.stats.prompts}",
                fontSize = 15.sp, color = Muted, maxLines = 1,
            )
            Text(s.activity, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PermissionOverlay(s: Session, index: Int, p: Pending, clockOffset: Long, onDecide: (String) -> Unit) {
    val now by produceState(System.currentTimeMillis(), p.id) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val left = ((p.expires - (now + clockOffset)) / 1000).coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.94f)
                .clip(PanelShape)
                .background(CardBg)
                .border(3.dp, Amber, PanelShape)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mascot("waiting", Modifier.width(52.dp).height(62.dp), mascotColors(index))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PERMISSION REQUEST", fontFamily = PixelHead, fontSize = 11.sp, color = Amber)
                    Text(
                        "${s.name.uppercase()} WANTS TO USE ${p.tool.uppercase()}",
                        fontSize = 19.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${left}S", fontFamily = PixelHead, fontSize = 13.sp, color = Amber)
                    Text("THEN TERMINAL", fontSize = 14.sp, color = Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            SegBar(left / ClaudeTimeoutSeconds, Amber, Modifier.fillMaxWidth().height(6.dp), segments = 30)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 110.dp)
                    .clip(ButtonShape)
                    .background(Bg)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text("> " + p.detail.ifEmpty { p.tool }, fontSize = 22.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelButton({ onDecide("deny") }, Modifier.weight(1f).height(54.dp), bg = Track) {
                    Text("✕ DENY", fontFamily = PixelHead, fontSize = 12.sp, color = Red)
                }
                PixelButton({ onDecide("allow") }, Modifier.weight(1f).height(54.dp), bg = Green) {
                    Text("✓ ALLOW", fontFamily = PixelHead, fontSize = 12.sp, color = Color.Black)
                }
            }
        }
    }
}

private const val ClaudeTimeoutSeconds = 60f

// ---------------------------------------------------------------- connecting / pairing

@Composable
private fun ConnectView(s: PanelState, onRetry: () -> Unit, onAddress: (String) -> Boolean) {
    var ask by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        TimeLapse(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val mascot = when (s.conn) {
                Conn.Pairing -> "waiting"
                Conn.Rejected -> "idle"
                else -> "working"
            }
            Mascot(mascot, Modifier.width(96.dp).height(114.dp))
            Spacer(Modifier.height(14.dp))
            val (title, body) = when (s.conn) {
                Conn.Pairing -> "APPROVE THIS PHONE" to "A box popped up on the laptop. Click Yes there. You only do this once."
                Conn.Rejected -> "PHONE NOT ALLOWED" to "Tap Try again, then click Yes on the laptop."
                Conn.Connecting, Conn.Reconnecting -> "CONNECTING…" to (s.address ?: "")
                else -> "SEARCHING FOR LAPTOP…" to "Make sure DeskPanel is running on the laptop and both are on the same Wi-Fi."
            }
            Text(title, fontFamily = PixelHead, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 22.sp, color = TextMain.copy(alpha = 0.75f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            if (s.conn == Conn.Rejected) {
                PixelButton(onRetry, bg = Blue) { Text("TRY AGAIN", fontFamily = PixelHead, fontSize = 9.sp) }
            } else if (s.conn != Conn.Pairing) {
                PixelButton({ ask = true }) { Text("ENTER LAPTOP IP", fontFamily = PixelHead, fontSize = 9.sp, color = Blue) }
            }
        }
    }
    if (ask) {
        var ip by remember { mutableStateOf("") }
        var bad by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("LAPTOP IP ADDRESS", fontFamily = PixelHead, fontSize = 11.sp) },
            text = {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it; bad = false },
                    singleLine = true,
                    isError = bad,
                    supportingText = if (bad) ({ Text("Use an address like 192.168.1.3 or 192.168.1.3:47811") }) else null,
                    placeholder = { Text("192.168.1.3") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onAddress(ip)) ask = false else bad = true
                }) { Text("CONNECT") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("CANCEL") } },
        )
    }
}
