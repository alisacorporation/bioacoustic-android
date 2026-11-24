package com.whispercppdemo.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.MutableLongState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.whispercppdemo.R
import com.whispercppdemo.data.Entry
import com.whispercppdemo.ui.theme.LocalV2
import com.whispercppdemo.ui.theme.Themes
import com.whispercppdemo.ui.theme.V2Colors
import com.whispercppdemo.ui.theme.V2Fonts
import com.whispercppdemo.ui.theme.V2Theme
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ----------------------------------------------------------------- helpers
private fun mono(size: TextUnit, color: Color, spacing: TextUnit = 1.2.sp, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = V2Fonts.mono, fontSize = size, color = color, letterSpacing = spacing, fontWeight = weight)

private fun body(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = V2Fonts.body, fontSize = size, color = color, fontWeight = weight, lineHeight = size * 1.5f)

/** Click without ripple — square, quiet UI. */
private fun Modifier.tap(onClick: () -> Unit) = composed { clickable(remember { MutableInteractionSource() }, null, onClick = onClick) }

private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

private fun dayTitle(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "СЕГОДНЯ"
        today.minusDays(1) -> "ВЧЕРА"
        else -> "${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}".uppercase()
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

// ------------------------------------------------------------------ screen
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(vm: MainScreenViewModel) {
    val c = Themes.firstOrNull { it.id == vm.themeId } ?: Themes[1]
    val motion = remember { WaveMotion() }
    val clock = remember { mutableLongStateOf(0L) }
    val recording = vm.phase == Phase.Recording
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { clock.longValue = it; motion.step(it, vm.audio.value, vm.phase == Phase.Recording) }
    }
    var toast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(Unit) { vm.toasts.collect { toast = it; delay(if (it.second) 3500 else 1800); toast = null } }
    val mic = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
    val haptics = LocalHapticFeedback.current
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }

    V2Theme(c) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Header(vm)
                if (vm.showHistory) {
                    CardSection(vm, onPickModel = { pickModel.launch(arrayOf("*/*")) })
                    HistorySection(vm, Modifier.weight(1f))
                } else {
                    Scope(vm, motion, clock, Modifier.weight(1f))
                    SysRow(vm)
                    CardSection(vm, onPickModel = { pickModel.launch(arrayOf("*/*")) })
                }
                Dock(
                    vm, motion, clock,
                    onPress = {
                        if (mic.status.isGranted) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.pressStart() }
                        else mic.launchPermissionRequest()
                    },
                    onRelease = { held -> if (recording || vm.phase == Phase.Recording) vm.pressEnd(held) },
                )
            }
            if (c.corp) Scanlines()
            AnimatedVisibility(toast != null, Modifier.align(Alignment.TopCenter).padding(top = 92.dp), enter = fadeIn(), exit = fadeOut()) {
                val (text, warn) = toast ?: ("" to false)
                val col = if (warn) c.busy else c.ok
                Text(
                    (if (warn) "⚠  " else "✓  ") + text,
                    style = body(13.sp, col),
                    modifier = Modifier.background(c.panel2).border(1.dp, col.copy(alpha = 0.4f)).padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun Scanlines() {
    Canvas(Modifier.fillMaxSize()) {
        var y = 0f
        val step = 3.dp.toPx()
        while (y < size.height) { drawLine(Color.White.copy(alpha = 0.018f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
    }
}

// ------------------------------------------------------------------ header
@Composable
private fun Header(vm: MainScreenViewModel) {
    val c = LocalV2.current
    Row(
        Modifier.fillMaxWidth().height(58.dp).drawBehind { drawLine(c.headerLine, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (c.corp) Image(painterResource(R.drawable.emblem), null, Modifier.size(32.dp))
        else NeonLogo(Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title, maxLines = 1,
                style = TextStyle(fontFamily = V2Fonts.display, fontWeight = FontWeight.SemiBold, color = c.text,
                    fontSize = if (c.corp) 11.sp else 14.sp, letterSpacing = if (c.corp) 1.5.sp else 0.4.sp),
            )
            Text(c.subtitle, style = mono(9.sp, c.dim, 1.4.sp, FontWeight.Normal), maxLines = 1)
        }
        Chip(vm.language.uppercase(), onClick = vm::cycleLanguage)
        Spacer(Modifier.width(6.dp))
        val (label, dot) = when (vm.phase) {
            Phase.Recording -> "ЗАПИСЬ" to c.rec
            Phase.Transcribing -> "РАСПОЗНАЮ" to c.busy
            Phase.Loading -> "ЗАГРУЗКА" to c.busy
            Phase.NoModel -> "НЕТ МОДЕЛИ" to c.busy
            Phase.Idle -> "ГОТОВ" to c.ok
        }
        Row(Modifier.border(1.dp, c.line).background(c.panel).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(7.dp)) { drawCircle(dot) }
            Spacer(Modifier.width(7.dp))
            Text(label, style = mono(10.sp, c.muted))
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val c = LocalV2.current
    Text(text, style = mono(10.sp, c.text), modifier = Modifier.border(1.dp, c.line).background(c.panel).tap(onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun NeonLogo(modifier: Modifier) {
    val c = LocalV2.current
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(c.accent, c.accent2)))
        val s = size.width / 24f
        listOf(6f to 1f, 9f to 4f, 12f to 7f, 15f to 4f, 18f to 1f).forEach { (x, hh) ->
            drawLine(c.onAccent, Offset(x * s, (12 - hh) * s), Offset(x * s, (12 + hh) * s), 2.2f * s)
        }
    }
}

// ------------------------------------------------------------------- scope
@Composable
private fun Scope(vm: MainScreenViewModel, motion: WaveMotion, clock: MutableLongState, modifier: Modifier) {
    val c = LocalV2.current
    val frame by vm.audio.collectAsState()
    val nanos = clock.longValue
    Box(
        modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp).border(1.dp, c.line)
            .background(Brush.radialGradient(listOf(c.glow, Color.Transparent))).background(c.panel.copy(alpha = 0.6f)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (vm.mode == "spectrum") drawSpectrum(motion, frame, c) else drawSineWaves(motion, c, nanos / 1e9f)
            drawBrackets(c.accent, 6.dp.toPx(), 14.dp.toPx(), 2.dp.toPx())
        }
        Text("ГОЛОС · LIVE", style = mono(9.5.sp, c.label, 1.6.sp), modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 14.dp))
        Column(Modifier.align(Alignment.TopEnd).padding(end = 22.dp, top = 12.dp), horizontalAlignment = Alignment.End) {
            Text("CH-01 · 16 kHz", style = mono(9.5.sp, c.dim, 1.6.sp))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.border(1.dp, c.line).background(c.bg)) {
                for ((id, label) in listOf("wave" to "Волна", "spectrum" to "Спектр")) {
                    val on = vm.mode == id
                    Text(label, style = mono(9.5.sp, if (on) c.text else c.dim),
                        modifier = Modifier.background(if (on) c.panel2 else Color.Transparent).tap { vm.chooseMode(id) }.padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
        }
        val secs = if (vm.phase == Phase.Recording) (System.currentTimeMillis() - vm.recordStartedAt) / 1000f else 0f
        Text(
            String.format(Locale.US, "%02d:%04.1f", (secs / 60).toInt(), secs % 60),
            style = TextStyle(fontFamily = V2Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = 2.sp,
                color = if (vm.phase == Phase.Recording) c.text else c.dim, textAlign = TextAlign.Center),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 28.dp).height(3.dp).background(c.panel2)) {
            Box(Modifier.fillMaxWidth(((frame.db + 60f) / 60f).coerceIn(0.02f, 1f)).height(3.dp).background(Brush.horizontalGradient(listOf(c.accent2, c.accent))))
        }
        Text("-60 dB", style = mono(9.sp, c.dim), modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 12.dp))
        Text("%.0f dB".format(frame.db), style = mono(9.sp, c.dim), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 12.dp))
    }
}

/** CPU load bar + where whisper runs (on Android it is CPU-only: no GPU backend in this build). */
@Composable
private fun SysRow(vm: MainScreenViewModel) {
    val c = LocalV2.current
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("CPU", style = mono(9.5.sp, c.dim))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(4.dp).background(c.panel2)) {
            Box(Modifier.fillMaxWidth((vm.cpuLoad / 100f).coerceIn(0.01f, 1f)).height(4.dp)
                .background(Brush.horizontalGradient(listOf(c.accent2, c.accent))))
        }
        Spacer(Modifier.width(8.dp))
        Text("%3.0f%%".format(vm.cpuLoad), style = mono(9.5.sp, c.muted))
        Spacer(Modifier.width(14.dp))
        // tap: cycle whisper threads (auto / 4 / 6 / 8)
        Text("WHISPER: CPU · " + if (vm.threads == 0) "A${vm.autoThreads}T" else "${vm.threads}T",
            style = mono(9.5.sp, c.label, 1.4.sp, FontWeight.SemiBold), modifier = Modifier.tap(vm::cycleThreads))
    }
}

// -------------------------------------------------------------------- card
@Composable
private fun CardSection(vm: MainScreenViewModel, onPickModel: () -> Unit) {
    val c = LocalV2.current
    val pinned = vm.pinned
    val text = pinned?.text ?: vm.lastText
    val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val wordsLabel = when {
        words == 0 -> ""
        words % 100 in 11..14 -> "$words СЛОВ"
        words % 10 == 1 -> "$words СЛОВО"
        words % 10 in 2..4 -> "$words СЛОВА"
        else -> "$words СЛОВ"
    }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .drawBehind {
                    drawRect(Brush.verticalGradient(listOf(c.panel2, c.panel)))
                    drawBrackets(if (pinned != null) c.accent2 else c.accent, 6.dp.toPx(), 14.dp.toPx(), 2.dp.toPx())
                }
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pinned != null) "ВЫБРАНО · ${dayTitle(pinned.time.toLocalDate())} ${pinned.time.format(hhmm)}" else "СЕЙЧАС",
                    style = mono(9.5.sp, c.label, 1.6.sp), modifier = Modifier.weight(1f),
                )
                if (wordsLabel.isNotEmpty() && vm.phase != Phase.NoModel && vm.phase != Phase.Loading) {
                    Text(wordsLabel, style = mono(9.5.sp, c.dim, 1.6.sp))
                }
                if (pinned != null) Text("  ✕", style = mono(10.sp, c.dim), modifier = Modifier.tap(vm::unpin))
            }
            Spacer(Modifier.height(10.dp))
            when {
                vm.phase == Phase.Transcribing && pinned == null -> Shimmer()
                vm.phase == Phase.NoModel -> NoModel(vm, onPickModel)
                vm.phase == Phase.Loading -> ModelLoading(vm)
                text.isEmpty() -> Text("Удерживайте кнопку и говорите — текст появится здесь.", style = body(15.sp, c.dim))
                else -> SelectionContainer {
                    Text(text, style = body(15.5.sp, c.text), modifier = Modifier.heightIn(min = 66.dp, max = 132.dp).verticalScroll(rememberScrollState()))
                }
            }
            if (vm.phase != Phase.NoModel && vm.phase != Phase.Loading) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    AccentButton("Копировать", enabled = text.isNotEmpty()) { vm.copy(text, announce = true) }
                }
                // corner readout, like the scope's dB labels: recorded length · whisper time · threads
                if (pinned == null && vm.lastMeta != "—") {
                    Text(vm.lastMeta.uppercase(), style = mono(9.5.sp, c.dim, 1.6.sp), maxLines = 1,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/** Model is loading (a big one can take a while): slowly spinning emblem + a sweeping indeterminate bar. */
@Composable
private fun ModelLoading(vm: MainScreenViewModel) {
    val c = LocalV2.current
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { t = it } }
    val secs = t / 1e9f
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            if (c.corp) Image(painterResource(R.drawable.emblem), null, Modifier.size(48.dp).rotate((secs * 45f) % 360f))
            else NeonLogo(Modifier.size(34.dp).rotate((secs * 45f) % 360f))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Загружаю модель whisper", style = body(15.5.sp, c.text, FontWeight.SemiBold))
            Text(vm.loadingModel.ifEmpty { "поиск модели…" }.uppercase() + " · ПЕРВЫЙ ЗАПУСК ДОЛЬШЕ",
                style = mono(9.5.sp, c.dim, 1.2.sp, FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.panel2).drawBehind {
                val seg = size.width * 0.32f
                val x = ((secs * 0.55f) % 1f) * (size.width + seg) - seg
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, c.accent, Color.Transparent), startX = x, endX = x + seg),
                    topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(seg, size.height),
                )
            })
        }
    }
}

@Composable
private fun NoModel(vm: MainScreenViewModel, onPickModel: () -> Unit) {
    val c = LocalV2.current
    Text("Модель whisper не найдена", style = body(15.5.sp, c.text, FontWeight.SemiBold))
    Spacer(Modifier.height(6.dp))
    if (vm.needsFilesAccess) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Text("Разрешите доступ к файлам — приложение само найдёт ggml-*.bin в «Загрузках».", style = body(13.sp, c.muted))
        Spacer(Modifier.height(10.dp))
        AccentButton("Разрешить доступ к файлам", enabled = true) {
            val pkg = android.net.Uri.parse("package:${ctx.packageName}")
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
                .onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        Text("или выбрать файл вручную", style = mono(10.sp, c.dim), modifier = Modifier.padding(top = 10.dp).tap(onPickModel))
    } else {
        Text("Положите ggml-*.bin в «Загрузки», выберите файл вручную или закиньте с компьютера:", style = body(13.sp, c.muted))
        SelectionContainer { Text(vm.modelHint, style = mono(10.sp, c.dim, 0.2.sp, FontWeight.Normal), modifier = Modifier.padding(vertical = 8.dp)) }
        AccentButton("Выбрать файл модели", enabled = true, onClick = onPickModel)
    }
}

@Composable
private fun AccentButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalV2.current
    Text(
        "⧉  $label", style = body(13.sp, if (enabled) c.onAccent else c.dim, FontWeight.SemiBold),
        modifier = Modifier
            .background(if (enabled) Brush.horizontalGradient(listOf(c.accent, c.accent2)) else SolidColor(c.panel2))
            .then(if (enabled) Modifier.tap(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    )
}

@Composable
private fun Shimmer() {
    val c = LocalV2.current
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { t = it } }
    val pos = (t / 1.3e9f) % 1f
    Column(Modifier.heightIn(min = 66.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        for (frac in listOf(0.94f, 0.8f, 0.52f)) {
            Box(Modifier.fillMaxWidth(frac).height(11.dp).drawBehind {
                drawRect(Brush.horizontalGradient(listOf(c.panel2, c.headerLine, c.panel2),
                    startX = size.width * (pos * 3 - 1.5f), endX = size.width * (pos * 3 - 0.5f)))
            })
        }
    }
}

// ----------------------------------------------------------------- history
private sealed interface Row_ { data class Day(val title: String) : Row_; data class Item(val e: Entry) : Row_ }

@Composable
private fun HistorySection(vm: MainScreenViewModel, modifier: Modifier) {
    val c = LocalV2.current
    var query by remember { mutableStateOf("") }
    val rows = remember(vm.history, query) {
        val q = query.trim().lowercase()
        val list = if (q.isEmpty()) vm.history else vm.history.filter { q in it.text.lowercase() }
        buildList {
            var day: LocalDate? = null
            for (e in list) {
                if (e.time.toLocalDate() != day) { day = e.time.toLocalDate(); add(Row_.Day(dayTitle(day))) }
                add(Row_.Item(e))
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ИСТОРИЯ", style = mono(9.5.sp, c.label, 2.sp, FontWeight.SemiBold))
            Spacer(Modifier.width(10.dp))
            Text("%,d".format(vm.history.size).replace(',', ' '), style = mono(10.5.sp, c.dim, 0.5.sp, FontWeight.Normal))
            Spacer(Modifier.weight(1f))
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = body(13.sp, c.text), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.width(160.dp).border(1.dp, c.line).background(c.panel).padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("⌕  Поиск…", style = body(13.sp, c.dim)); inner() } },
            )
        }
        Spacer(Modifier.height(6.dp))
        if (rows.isEmpty()) {
            Text(if (query.isEmpty()) "Пока пусто — продиктуйте что-нибудь" else "Ничего не найдено",
                style = body(13.sp, c.dim), modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { r -> if (r is Row_.Item) r.e.time.toString() + r.e.text.hashCode() else (r as Row_.Day).title }) { r ->
                when (r) {
                    is Row_.Day -> Text(r.title, style = mono(9.sp, c.dim, 2.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
                    is Row_.Item -> HistoryRow(r.e, selected = vm.pinned == r.e, onSelect = { vm.togglePin(r.e) }, onCopy = { vm.copy(r.e.text, announce = true) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: Entry, selected: Boolean, onSelect: () -> Unit, onCopy: () -> Unit) {
    val c = LocalV2.current
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .then(if (selected) Modifier.background(c.panel2).border(1.dp, c.accent2).drawBehind { drawRect(c.accent, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) } else Modifier)
            .tap(onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.time.format(hhmm), style = mono(11.sp, c.dim, 0.sp, FontWeight.Normal), modifier = Modifier.width(54.dp).padding(start = 10.dp))
        Text(e.text, style = body(14.sp, if (selected) c.text else c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp, 48.dp).tap(onCopy), contentAlignment = Alignment.Center) { CopyIcon(if (selected) c.accent else c.dim, 16.dp) }
    }
}

@Composable
private fun CopyIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(2f * s)
        drawRect(color, Offset(9 * s, 9 * s), androidx.compose.ui.geometry.Size(12 * s, 12 * s), style = st)
        drawLine(color, Offset(5 * s, 15 * s), Offset(5 * s, 3 * s), 2f * s)
        drawLine(color, Offset(5 * s, 3 * s), Offset(17 * s, 3 * s), 2f * s)
    }
}

// -------------------------------------------------------------------- dock
@Composable
private fun Dock(vm: MainScreenViewModel, motion: WaveMotion, clock: MutableLongState, onPress: () -> Unit, onRelease: (Long) -> Unit) {
    val c = LocalV2.current
    val hint = when {
        vm.phase == Phase.Recording && vm.handsFree -> "ЗАПИСЬ БЕЗ РУК · ТАП — СТОП"
        vm.phase == Phase.Recording -> "ОТПУСТИТЕ — РАСПОЗНАТЬ"
        vm.phase == Phase.Transcribing -> "WHISPER РАБОТАЕТ…"
        vm.showHistory -> "ТАП ПО СТРОКЕ — ВЫБРАТЬ · ⧉ КОПИРОВАТЬ"
        else -> "УДЕРЖИВАЙТЕ · ИЛИ ТАП"
    }
    Box(
        Modifier.fillMaxWidth().height(136.dp).padding(top = 14.dp)
            .drawBehind {
                drawLine(c.headerLine, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                drawRect(Brush.verticalGradient(listOf(c.bg, c.panel)))
                if (c.corp) {     // hazard stripe
                    val h = 5.dp.toPx(); var x = -h * 4; val w = 8.dp.toPx()
                    while (x < size.width) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(x, size.height); lineTo(x + w, size.height); lineTo(x + w + h, size.height - h); lineTo(x + h, size.height - h); close()
                        }
                        drawPath(p, c.stripe); x += 2 * w
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DockButton("ИСТОРИЯ", vm.showHistory, Modifier.weight(1f), onClick = vm::toggleHistory) { col ->
                Canvas(Modifier.size(22.dp)) {
                    val s = size.width / 24f
                    listOf(6f to 20f, 12f to 20f, 18f to 14f).forEach { (y, x2) -> drawLine(col, Offset(4 * s, y * s), Offset(x2 * s, y * s), 1.8f * s) }
                }
            }
            RecordButton(c, vm.phase == Phase.Recording, enabled = vm.phase == Phase.Idle || vm.phase == Phase.Recording,
                motion = motion, clock = clock, onPress = onPress, onRelease = onRelease)
            DockButton("ТЕМА", false, Modifier.weight(1f), onClick = vm::cycleTheme) { col ->
                Canvas(Modifier.size(22.dp)) {
                    val s = size.width / 24f; val st = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * s)
                    val sz = androidx.compose.ui.geometry.Size(8 * s, 8 * s)
                    drawRect(col, Offset(3 * s, 3 * s), sz, style = st); drawRect(col, Offset(13 * s, 3 * s), sz, style = st)
                    drawRect(col, Offset(3 * s, 13 * s), sz, style = st); drawRect(col, Offset(13 * s, 13 * s), sz)
                }
            }
        }
        Text(hint, style = mono(9.sp, c.dim, 1.6.sp), textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp))
    }
}

@Composable
private fun DockButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val c = LocalV2.current
    val col = if (on) c.accent else c.muted
    Column(modifier.tap(onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon(col)
        Spacer(Modifier.height(6.dp))
        Text(label, style = mono(9.sp, col, 1.6.sp))
    }
}
