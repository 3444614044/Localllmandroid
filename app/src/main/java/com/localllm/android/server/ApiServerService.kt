package com.localllm.android.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localllm.android.LocalLlmApp
import com.localllm.android.MainActivity
import com.localllm.android.R
import com.localllm.android.data.ModelStorageManager
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType

/**
 * Foreground service hosting the Ollama/OpenAI-compatible API server (port 11434).
 *
 * Why a service: the server used to live in MainViewModel, so leaving the app
 * (activity destroy → ViewModel.onCleared) killed the "phone as IDE server" the
 * README advertises. A started foreground service keeps the process alive with a
 * visible notification (with a Stop action) after the UI is gone.
 *
 * Ownership: the single [com.localllm.android.engine.LlmEngine] lives in
 * [LocalLlmApp] and is borrowed here — never duplicated (two engines would mean
 * two native contexts in RAM). Models/settings/key are re-read from
 * ModelStorageManager + SharedPreferences on every START, so restarting the
 * service picks up model downloads and setting changes without the UI.
 *
 * Death semantics: START_NOT_STICKY. If the process dies, the server stays dead
 * (a sticky restart would revive it with a fresh, model-less engine and serve
 * "no model" answers). The user restarts it from the app or the notification.
 */
class ApiServerService : Service() {

    data class Snapshot(
        val running: Boolean,
        val message: String?,
        val requestCount: Int,
        val apiKey: String,
        val boundHost: String
    )

    inner class ServerBinder : Binder() {
        fun snapshot(): Snapshot = currentSnapshot()
        fun reloadApiKey() = this@ApiServerService.reloadApiKey()
        fun setListener(listener: ((Snapshot) -> Unit)?) {
            stateListener = listener
            listener?.invoke(currentSnapshot())
        }
    }

    companion object {
        const val ACTION_START = "com.localllm.android.server.ApiServerService.START"
        const val ACTION_STOP = "com.localllm.android.server.ApiServerService.STOP"
        private const val TAG = "ApiServerService"
        private const val NOTIF_ID = 11434
        private const val CHANNEL_ID = "api_server_channel"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ApiServerService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ApiServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val binder = ServerBinder()
    private var server: OllamaApiServer? = null
    private var models: List<LlmModel> = emptyList()
    private var settings: GenerationSettings = GenerationSettings()
    private var statusMessage: String? = null

    @Volatile
    private var stateListener: ((Snapshot) -> Unit)? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("app_settings_prefs", MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        // Disk-fresh state: downloads and setting edits made in the UI apply
        // without needing the ViewModel (which may be long gone).
        val storage = ModelStorageManager(this)
        models = storage.loadModels()
        val activeId = storage.getActiveModelId()
        val activeModel = models.firstOrNull { it.id == activeId && it.isDownloaded }
            ?: models.firstOrNull { it.isDownloaded }
        settings = readServingSettings()
        val key = prefs.getString("api_server_key", null)?.takeIf { it.isNotBlank() }
            ?: OllamaApiServer.generateSecureApiKey().also {
                prefs.edit().putString("api_server_key", it).apply()
            }

        val engine = (application as LocalLlmApp).llmEngine
        val active = activeModel
        val current = models
        val serving = settings
        val host = serving.apiServerBindAddress.ifBlank { "127.0.0.1" }

        val svc = OllamaApiServer(
            context = this,
            llmEngine = engine,
            getActiveModel = { active },
            getAllModels = { current.filter { it.isDownloaded } },
            getSettings = { serving },
            initialApiKey = key
        )
        svc.onRequestProcessed = { pushSnapshot() }
        server = svc
        startForeground(NOTIF_ID, buildNotification(host))
        svc.start { _, msg ->
            statusMessage = msg
            pushSnapshot()
            updateNotification()
        }
        isRunning = true
        pushSnapshot()
    }

    private fun stopServer() {
        try {
            server?.stop { _, _ -> }
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed: ${t.message}")
        }
        server = null
        isRunning = false
        pushSnapshot()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun reloadApiKey() {
        val key = prefs.getString("api_server_key", null)?.takeIf { it.isNotBlank() } ?: return
        server?.setApiKey(key)
        pushSnapshot()
    }

    private fun currentSnapshot(): Snapshot = Snapshot(
        running = isRunning,
        message = statusMessage,
        requestCount = server?.requestCount ?: 0,
        apiKey = server?.currentApiKey
            ?: prefs.getString("api_server_key", null).orEmpty(),
        boundHost = server?.boundHost ?: settings.apiServerBindAddress.ifBlank { "127.0.0.1" }
    )

    private fun pushSnapshot() {
        try {
            stateListener?.invoke(currentSnapshot())
        } catch (t: Throwable) {
            Log.w(TAG, "listener failed: ${t.message}")
        }
    }

    private fun readServingSettings(): GenerationSettings {
        val runtime = try {
            val stored = prefs.getString("runtime", null)
            if (stored.isNullOrBlank()) ModelRuntimeType.LLAMA_CPP else ModelRuntimeType.valueOf(stored)
        } catch (_: Exception) {
            ModelRuntimeType.LLAMA_CPP
        }
        return GenerationSettings(
            runtime = runtime,
            contextWindow = prefs.getInt("context_window", 4096),
            temperature = prefs.getFloat("temperature", 0.7f),
            topP = prefs.getFloat("top_p", 0.9f),
            topK = prefs.getInt("top_k", 40),
            apiServerBindAddress = prefs.getString("api_server_bind_address", "127.0.0.1") ?: "127.0.0.1",
            apiServerRequireAuth = prefs.getBoolean("api_server_require_auth", true)
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.api_server_notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(host: String): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ApiServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val port = server?.getPort() ?: OllamaApiServer.DEFAULT_PORT
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.api_server_notif_title))
            .setContentText(getString(R.string.api_server_notif_text, host, port))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.api_server_notif_stop),
                stopIntent
            )
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIF_ID, buildNotification(currentSnapshot().boundHost))
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed: ${t.message}")
        }
    }
}
