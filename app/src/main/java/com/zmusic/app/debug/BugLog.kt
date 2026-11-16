package com.zmusic.app.debug

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's own console. Everything that goes wrong (and a few milestones, for context) is
 * recorded here and shown on the Bugs tab.
 *
 * - Entries are kept in memory as a [StateFlow] and appended to a small file in the app's private
 *   storage, so the log survives a restart. That matters most for crashes: an uncaught exception
 *   is written synchronously before the process dies and is there to read on the next launch.
 * - Nothing leaves the device. The user can copy the log out from the Bugs tab.
 * - Safe to call from any thread, and before [install] (those entries are just not persisted).
 */
object BugLog {

    enum class Level(val tag: String) { Info("INFO"), Warn("WARN"), Error("ERR"), Fatal("FATAL") }

    @Immutable
    data class Entry(
        val id: Long,
        val timeMs: Long,
        val level: Level,
        val source: String,
        val message: String,
        /** Stack trace or other long text, shown when the line is expanded. */
        val detail: String?,
    ) {
        val isProblem: Boolean get() = level == Level.Error || level == Level.Fatal
    }

    private const val MAX_ENTRIES = 400
    private const val MAX_DETAIL_CHARS = 6000
    private const val FILE_NAME = "buglog.txt"
    private const val LOGCAT_TAG = "Zmusic"

    private val nextId = AtomicLong(1)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "buglog").apply { isDaemon = true } }

    @Volatile
    private var file: File? = null

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Loads what earlier runs recorded and starts catching crashes. Call once from Application. */
    fun install(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f

        // Read on the log's own thread so app start never waits on the disk. It is queued ahead
        // of every append, so the file is read before anything new is written to it, and the
        // lines it holds go in front of whatever has been logged in the meantime.
        io.execute {
            val restored = runCatching {
                if (f.exists()) f.readLines().mapNotNull(::decode) else emptyList()
            }.getOrDefault(emptyList())
            if (restored.isNotEmpty()) {
                val kept = restored.takeLast(MAX_ENTRIES)
                _entries.update { current -> (kept + current).takeLast(MAX_ENTRIES) }
                // Keep the file from growing forever.
                if (restored.size > MAX_ENTRIES) {
                    runCatching { f.writeText(kept.joinToString("") { encode(it) + "\n" }) }
                }
            }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written on the dying thread: the executor may never get to run again.
            runCatching {
                val entry = entry(Level.Fatal, "Crash", "Uncaught ${error.javaClass.simpleName} on ${thread.name}: ${error.message}", error)
                f.appendText(encode(entry) + "\n")
            }
            previous?.uncaughtException(thread, error)
        }

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        i("App", "Zmusic $version started on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun i(source: String, message: String) = add(Level.Info, source, message, null)

    fun w(source: String, message: String, error: Throwable? = null) = add(Level.Warn, source, message, error)

    fun e(source: String, message: String, error: Throwable? = null) = add(Level.Error, source, message, error)

    /** Empties the console and the file behind it. */
    fun clear() {
        _entries.value = emptyList()
        val f = file ?: return
        io.execute { runCatching { f.writeText("") } }
    }

    /** The whole log as plain text, for copying into a bug report. */
    fun dump(entries: List<Entry> = _entries.value): String = buildString {
        for (e in entries) {
            append(formatDateTime(e.timeMs)).append("  ").append(e.level.tag.padEnd(5)).append("  ")
            append(e.source).append(": ").append(e.message).append('\n')
            if (e.detail != null) append(e.detail.trimEnd()).append('\n')
        }
    }

    // A SimpleDateFormat is slow to build and not thread safe, so each thread keeps its own.
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val dateTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    fun formatTime(timeMs: Long): String = format(timeFormat, timeMs)

    private fun formatDateTime(timeMs: Long): String = format(dateTimeFormat, timeMs)

    /** Follows the phone's time zone, as a formatter built for each call would. */
    private fun format(cached: ThreadLocal<SimpleDateFormat>, timeMs: Long): String {
        val f = cached.get()!!
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(timeMs))
    }

    private fun entry(level: Level, source: String, message: String, error: Throwable?): Entry =
        Entry(nextId.getAndIncrement(), System.currentTimeMillis(), level, source, message, error?.let(::stackTrace))

    private fun add(level: Level, source: String, message: String, error: Throwable?) {
        val entry = entry(level, source, message, error)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        // android.util.Log is not available in plain JVM unit tests.
        runCatching {
            when (level) {
                Level.Info -> Log.i(LOGCAT_TAG, "$source: $message")
                Level.Warn -> Log.w(LOGCAT_TAG, "$source: $message", error)
                else -> Log.e(LOGCAT_TAG, "$source: $message", error)
            }
        }
        val f = file ?: return
        io.execute { runCatching { f.appendText(encode(entry) + "\n") } }
    }

    private fun stackTrace(error: Throwable): String {
        val out = StringWriter()
        error.printStackTrace(PrintWriter(out))
        return out.toString().replace("\t", "    ").take(MAX_DETAIL_CHARS)
    }

    // One entry per line: time, level, source, message, detail, separated by tabs.

    private fun encode(e: Entry): String =
        listOf(e.timeMs.toString(), e.level.name, escape(e.source), escape(e.message), escape(e.detail.orEmpty()))
            .joinToString("\t")

    private fun decode(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 5) return null
        val time = parts[0].toLongOrNull() ?: return null
        val level = Level.entries.firstOrNull { it.name == parts[1] } ?: return null
        return Entry(
            id = nextId.getAndIncrement(),
            timeMs = time,
            level = level,
            source = unescape(parts[2]),
            message = unescape(parts[3]),
            detail = unescape(parts[4]).ifEmpty { null },
        )
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
