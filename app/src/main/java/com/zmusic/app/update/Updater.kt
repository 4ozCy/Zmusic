package com.zmusic.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import com.zmusic.app.debug.BugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    /** Nothing to show: up to date, not checked yet, or the user said "Later". */
    data object Idle : UpdateState

    data class Available(val release: AppRelease) : UpdateState

    /** [progress] is 0..1, or -1 when the size is unknown. */
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState

    /** Handed to Android. Its confirmation screen is up, or about to be. */
    data class Installing(val release: AppRelease) : UpdateState

    data class Failed(val release: AppRelease, val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for a build newer than this one, downloads its APK straight into an
 * Android [PackageInstaller] session and commits it. Android then shows its own "Update this
 * app?" screen. Every build is signed with the same key, so it installs over this one and keeps
 * the library, queue and settings.
 *
 * The repo is public, so no token is needed. GitHub allows 60 unauthenticated API calls an hour
 * per IP; checks are spaced [CHECK_EVERY_MS] apart, so this uses a handful a day.
 */
class Updater(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Android's confirmation screen, waiting to be started. The activity starts it, not the
     * broadcast receiver, so it still opens if the app was in the background when the download
     * finished (Android blocks activity starts from the background).
     */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm.asStateFlow()

    val installedVersion: Long by lazy {
        PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
    }

    /** Debug builds are signed with the debug key and could not install a release over themselves. */
    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private var lastCheck = 0L
    private var checking: Job? = null
    private var download: Job? = null
    private var session = -1

    /** Version the user said "Later" to. Not shown again until a newer one or a fresh launch. */
    private var snoozed = -1

    /** Whether Android lets this app install packages ("Install unknown apps" for Zmusic). */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** Cheap to call on every resume: does nothing until [CHECK_EVERY_MS] has passed. */
    fun checkIfDue() {
        if (debuggable || checking?.isActive == true || _state.value !is UpdateState.Idle) return
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        checking = scope.launch {
            val release = try {
                withContext(Dispatchers.IO) { fetchLatest() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline, or GitHub is slow. Not worth bothering anyone about; try again later.
                BugLog.i("Update", "Could not check for updates: ${e.message}")
                null
            } ?: return@launch
            if (release.versionCode > installedVersion && release.versionCode != snoozed && _state.value is UpdateState.Idle) {
                BugLog.i("Update", "Found ${release.versionName} (installed: $installedVersion)")
                _state.value = UpdateState.Available(release)
            }
        }
    }

    fun later() {
        val release = releaseOf(_state.value) ?: return
        cancelDownload()
        snoozed = release.versionCode
        _state.value = UpdateState.Idle
    }

    fun install() {
        val release = releaseOf(_state.value) ?: return
        if (download?.isActive == true) return
        _state.value = UpdateState.Downloading(release, if (release.apkSize > 0) 0f else -1f)
        download = scope.launch {
            try {
                withContext(Dispatchers.IO) { downloadAndCommit(release) }
                _state.value = UpdateState.Installing(release)
            } catch (e: CancellationException) {
                abandonSession()
                throw e
            } catch (e: Exception) {
                abandonSession()
                BugLog.w("Update", "Update download failed", e)
                _state.value = UpdateState.Failed(release, "The download didn't finish. Check your connection and try again.")
            }
        }
    }

    fun cancelDownload() {
        download?.cancel()
        download = null
        val release = releaseOf(_state.value) ?: return
        if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Available(release)
    }

    /** Called once the activity has started [confirm]. */
    fun onConfirmShown() {
        _confirm.value = null
    }

    /** From [InstallResultReceiver]. */
    internal fun onInstallStatus(status: Int, message: String?, confirmIntent: Intent?) {
        val release = releaseOf(_state.value)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmIntent != null) _confirm.value = confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // The new version replaces this process, so this is rarely seen.
            PackageInstaller.STATUS_SUCCESS -> {
                session = -1
                _state.value = UpdateState.Idle
            }
            // "Cancel" on Android's screen. Back to the offer, no error.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                session = -1
                if (release != null) _state.value = UpdateState.Available(release)
            }
            else -> {
                session = -1
                BugLog.w("Update", "Install failed: status $status, $message")
                if (release != null) _state.value = UpdateState.Failed(release, failureText(status))
            }
        }
    }

    private fun failureText(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android wouldn't install it over this version. The new APK may be signed differently. Install it from the Releases page instead."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough free space for the update."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install. Check that Zmusic is allowed to install apps."
        else -> "Android couldn't install the update. Try again, or install it from the Releases page."
    }

    private fun fetchLatest(): AppRelease? {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Zmusic-Android-updater")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val version = Releases.versionFromTag(tag) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
                return AppRelease(
                    versionCode = version,
                    versionName = Releases.displayName(tag),
                    notes = Releases.cleanNotes(json.optString("body")),
                    apkUrl = asset.getString("browser_download_url"),
                    apkSize = asset.optLong("size", -1L),
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadAndCommit(release: AppRelease) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (release.apkSize > 0) setSize(release.apkSize)
            // From Android 12, once Zmusic installed itself once (so it is the "installer of
            // record"), later updates may go through without the confirmation screen. If Android
            // says no, it simply asks as usual.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        session = id
        installer.openSession(id).use { s ->
            // GitHub redirects the download to its file host; HttpURLConnection follows https->https.
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Zmusic-Android-updater")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Download answered ${conn.responseCode}")
                val total = if (release.apkSize > 0) release.apkSize else conn.contentLengthLong
                conn.inputStream.use { input ->
                    s.openWrite("zmusic.apk", 0, if (total > 0) total else -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != shown) {
                                    shown = pct
                                    _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
            } finally {
                conn.disconnect()
            }
            val callback = Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName)
            // Mutable: PackageInstaller fills in the status and the confirmation intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, id, callback, flags)
            s.commit(pending.intentSender)
        }
    }

    private fun abandonSession() {
        val id = session
        session = -1
        if (id < 0) return
        try {
            app.packageManager.packageInstaller.abandonSession(id)
        } catch (_: Exception) {
            // Already committed or gone.
        }
    }

    private fun releaseOf(state: UpdateState): AppRelease? = when (state) {
        UpdateState.Idle -> null
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Failed -> state.release
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/4ozCy/Zmusic/releases/latest"
        const val CHECK_EVERY_MS = 60 * 60 * 1000L
    }
}
