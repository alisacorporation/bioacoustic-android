package com.whispercppdemo.ui.main

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.data.Entry
import com.whispercppdemo.data.HistoryStore
import com.whispercppdemo.recorder.AudioFrame
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.ui.theme.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "voice2clip"

/** Default whisper model — the same one the desktop app uses. */
const val DEFAULT_MODEL = "ggml-large-v3-turbo-q4_0.bin"

/** Preferred model when several are present: turbo q4_0 (fast ARM path), other turbo, small, anything (newest first). */
private fun modelRank(name: String): Int = when {
    "large-v3-turbo" in name && "q4_0" in name -> 0
    "large-v3-turbo" in name -> 1
    "small" in name -> 2
    else -> 3
}

private const val POST_ROLL_MS = 650L
private const val TAIL_PAD_SAMPLES = 16000 * 4 / 10     // 0.4 s at 16 kHz

enum class Phase { Loading, NoModel, Idle, Recording, Transcribing }

class MainScreenViewModel(private val app: Application) : ViewModel() {
    private val prefs = app.getSharedPreferences("voice2clip", Context.MODE_PRIVATE)

    var phase by mutableStateOf(Phase.Loading); private set
    var handsFree by mutableStateOf(false); private set          // tap-started recording (no holding)
    var recordStartedAt by mutableStateOf(0L); private set
    var lastText by mutableStateOf(""); private set
    var lastMeta by mutableStateOf("—"); private set
    var history by mutableStateOf<List<Entry>>(emptyList()); private set
    var pinned by mutableStateOf<Entry?>(null); private set
    var showHistory by mutableStateOf(false); private set
    var themeId by mutableStateOf(prefs.getString("theme", "umbrella")!!); private set
    var mode by mutableStateOf(prefs.getString("mode", "wave")!!); private set
    var language by mutableStateOf(prefs.getString("lang", "ru")!!); private set
    var modelName by mutableStateOf("—"); private set
    var modelHint by mutableStateOf(""); private set
    /** Model currently being loaded (for the loading card); empty while still looking for one. */
    var loadingModel by mutableStateOf(""); private set
    /** whisper threads: 0 = auto (fast cores only), else a fixed count — to compare on the phone. */
    var threads by mutableStateOf(prefs.getInt("threads", 0)); private set
    val autoThreads: Int get() = com.whispercpp.whisper.WhisperCpuConfig.preferredThreadCount
    val cores: Int = Runtime.getRuntime().availableProcessors()

    /** No model found and the app can't look into Downloads yet (needs "All files access"). */
    var needsFilesAccess by mutableStateOf(false); private set
    /** CPU used by this app (whisper + UI), % of all cores. Android 8+ hides system-wide load from apps. */
    var cpuLoad by mutableStateOf(0f); private set

    private val _audio = MutableStateFlow(AudioFrame.SILENT)
    val audio = _audio.asStateFlow()
    private val _toasts = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 4)   // (text, warning)
    val toasts = _toasts.asSharedFlow()

    /** Models: adb push to the app's external files dir, or imported via the file picker. */
    val externalModelsDir: File = File(app.getExternalFilesDir(null), "models")
    private val internalModelsDir = File(app.filesDir, "models")
    private val downloadsDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun hasFilesAccess() = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** ggml-*.bin in Downloads and its direct subfolders (KDE Connect may use one); skips half-downloaded files. */
    private fun downloadedModels(): List<File> {
        if (!hasFilesAccess()) return emptyList()
        val dirs = listOf(downloadsDir) + (downloadsDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
        return dirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.name.startsWith("ggml-") && it.name.endsWith(".bin") && it.length() > 30_000_000 }
    }
    private val store = HistoryStore(File(app.filesDir, "transcriptions"))
    private val recorder = Recorder()
    private var whisper: WhisperContext? = null

    init {
        viewModelScope.launch {
            history = withContext(Dispatchers.IO) { store.load() }
            loadModel()
        }
        viewModelScope.launch(Dispatchers.IO) { monitorCpu() }
    }

    /** /proc/self/stat utime+stime deltas → % of all cores (system-wide /proc/stat is closed to apps since Android 8). */
    private suspend fun monitorCpu() {
        val tck = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).toDouble().coerceAtLeast(1.0)
        val cores = Runtime.getRuntime().availableProcessors()
        fun ticks(): Long = runCatching {
            val f = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
            f[11].toLong() + f[12].toLong()      // utime, stime (fields 14/15 counted from the start)
        }.getOrDefault(0L)
        var prevT = ticks(); var prevW = System.nanoTime()
        while (true) {
            kotlinx.coroutines.delay(1000)
            val t = ticks(); val w = System.nanoTime()
            val pct = ((t - prevT) / tck) / ((w - prevW) / 1e9) / cores * 100
            prevT = t; prevW = w
            withContext(Dispatchers.Main) { cpuLoad = pct.toFloat().coerceIn(0f, 100f) }
        }
    }

    // ------------------------------------------------------------------ model
    private suspend fun loadModel() {
        phase = Phase.Loading
        loadingModel = ""
        val file = withContext(Dispatchers.IO) {
            externalModelsDir.mkdirs(); internalModelsDir.mkdirs()
            (listOf(externalModelsDir, internalModelsDir)
                .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile && it.name.endsWith(".bin") } + downloadedModels())
                .sortedWith(compareBy<File>({ modelRank(it.name) }, { -it.lastModified() }))
                .firstOrNull()
        }
        val bundled = runCatching { app.assets.list("models")?.toList() }.onFailure { Log.w(LOG_TAG, it) }.getOrNull()
        Log.i(LOG_TAG, "user model: ${file?.absolutePath}; bundled assets: $bundled")
        if (file == null && bundled?.contains(DEFAULT_MODEL) == true) {
            // no user-supplied model → use the one bundled in the APK
            try {
                loadingModel = DEFAULT_MODEL.removePrefix("ggml-").removeSuffix(".bin") + " · встроенная"
                whisper?.release()
                whisper = withContext(Dispatchers.IO) { WhisperContext.createContextFromAsset(app.assets, "models/$DEFAULT_MODEL") }
                modelName = DEFAULT_MODEL.removePrefix("ggml-").removeSuffix(".bin")
                phase = Phase.Idle
                return
            } catch (e: Exception) {
                Log.w(LOG_TAG, e)
                toast("Встроенная модель не загрузилась: ${e.localizedMessage}", true)
            }
        }
        if (file == null) {
            modelHint = "adb push $DEFAULT_MODEL ${externalModelsDir.absolutePath}/"
            needsFilesAccess = !hasFilesAccess()
            phase = Phase.NoModel
            return
        }
        needsFilesAccess = false
        Log.i(LOG_TAG, "loading model ${file.absolutePath}")
        try {
            loadingModel = file.nameWithoutExtension.removePrefix("ggml-")
            whisper?.release()
            whisper = withContext(Dispatchers.IO) { WhisperContext.createContextFromFile(file.absolutePath) }
            modelName = file.nameWithoutExtension.removePrefix("ggml-")
            phase = Phase.Idle
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            toast("Не удалось загрузить модель: ${e.localizedMessage}", true)
            phase = Phase.NoModel
        }
    }

    /** Back from Settings / the file manager: look for a model again. */
    fun onResume() {
        if (phase == Phase.NoModel) viewModelScope.launch { loadModel() }
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        phase = Phase.Loading
        try {
            withContext(Dispatchers.IO) {
                val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "model.bin"
                internalModelsDir.mkdirs()
                val dest = File(internalModelsDir, if (name.endsWith(".bin")) name else "$name.bin")
                app.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
            loadModel()
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            toast("Импорт модели не удался: ${e.localizedMessage}", true)
            phase = Phase.NoModel
        }
    }

    // -------------------------------------------------------------- recording
    /** Finger down on the record button. Starts recording right away (hold-to-talk). */
    fun pressStart() {
        if (phase == Phase.Recording && handsFree) return        // second tap: handled by release
        if (phase != Phase.Idle) return
        handsFree = false
        pinned = null
        recorder.start(
            onFrame = { _audio.value = it },
            onError = { e -> viewModelScope.launch { toast("Микрофон: ${e.localizedMessage}", true); phase = Phase.Idle } },
        )
        recordStartedAt = System.currentTimeMillis()
        phase = Phase.Recording
    }

    /** Finger up. A short tap switches to hands-free; a hold (or a tap while hands-free) stops & transcribes. */
    fun pressEnd(heldMs: Long) {
        if (phase != Phase.Recording) return
        if (!handsFree && heldMs < 350) { handsFree = true; return }
        stopAndTranscribe()
    }

    private var stopping = false

    private fun stopAndTranscribe() = viewModelScope.launch {
        if (stopping) return@launch
        stopping = true
        handsFree = false
        // post-roll: the finger usually lifts while the last word is still sounding
        kotlinx.coroutines.delay(POST_ROLL_MS)
        val audio = withContext(Dispatchers.IO) { recorder.stop() }
        _audio.value = AudioFrame.SILENT
        // trailing silence: whisper drops/garbles a word that ends right at the edge of the audio
        stopping = false
        transcribe(audio, padTail = true)
    }

    /** Debug builds only (see MainActivity): run a 16 kHz mono WAV through the same path as the mic. */
    fun transcribeFile(path: String) = viewModelScope.launch {
        while (phase == Phase.Loading) kotlinx.coroutines.delay(200)
        if (phase != Phase.Idle) return@launch
        val audio = withContext(Dispatchers.IO) { com.whispercppdemo.media.decodeWaveFile(File(path)) }
        transcribe(audio)
    }

    private suspend fun transcribe(audio: FloatArray, padTail: Boolean = false) {
        val seconds = audio.size / 16000f
        if (seconds < 0.3f) { phase = Phase.Idle; return }
        phase = Phase.Transcribing
        try {
            val t0 = System.currentTimeMillis()
            val nThreads = if (threads > 0) threads else autoThreads
            val input = if (padTail) audio + FloatArray(TAIL_PAD_SAMPLES) else audio
            val raw = whisper?.transcribeData(input, printTimestamp = false, language = language, threads = nThreads).orEmpty()
            val text = clean(raw)
            val took = (System.currentTimeMillis() - t0) / 1000f
            if (text.isBlank()) {
                toast("Речь не распознана", true)
            } else {
                val entry = withContext(Dispatchers.IO) { store.save(text, seconds, modelName) }
                history = listOf(entry) + history
                lastText = text
                lastMeta = "голос %.1f s · whisper %.1f s · %dT".format(seconds, took, nThreads)
                copy(text)
                toast("Скопировано в буфер обмена", false)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            toast("Ошибка whisper: ${e.localizedMessage}", true)
        }
        phase = Phase.Idle
    }

    /** whisper's non-speech markers like [музыка] / (silence) and stray whitespace. */
    private fun clean(s: String): String =
        s.replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ").replace(Regex("\\s+"), " ").trim()

    // --------------------------------------------------------------- actions
    fun copy(text: String, announce: Boolean = false) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("voice2clip", text))
        if (announce) toast("Скопировано в буфер обмена", false)
    }

    fun togglePin(e: Entry) { pinned = if (pinned == e) null else e }
    fun unpin() { pinned = null }
    fun toggleHistory() { showHistory = !showHistory }

    fun cycleTheme() {
        val i = Themes.indexOfFirst { it.id == themeId }
        themeId = Themes[(i + 1) % Themes.size].id
        prefs.edit().putString("theme", themeId).apply()
    }

    /** auto → 4 → 6 → 8 → auto (counts above the core count are skipped). */
    fun cycleThreads() {
        val order = listOf(0, 4, 6, 8).filter { it <= cores }
        threads = order[(order.indexOf(threads).coerceAtLeast(0) + 1) % order.size]
        prefs.edit().putInt("threads", threads).apply()
        toast("Потоки whisper: " + if (threads == 0) "авто ($autoThreads)" else "$threads", false)
    }

    fun chooseMode(m: String) { mode = m; prefs.edit().putString("mode", m).apply() }

    fun cycleLanguage() {
        val order = listOf("ru", "en", "auto")
        language = order[(order.indexOf(language) + 1) % order.size]
        prefs.edit().putString("lang", language).apply()
    }

    private fun toast(text: String, warn: Boolean) { _toasts.tryEmit(text to warn) }

    override fun onCleared() {
        runBlocking { whisper?.release(); whisper = null }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                MainScreenViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
            }
        }
    }
}
