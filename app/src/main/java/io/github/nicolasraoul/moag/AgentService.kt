package io.github.nicolasraoul.moag

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onCreate() {
        super.onCreate()
        
        try {
            com.google.android.gms.security.ProviderInstaller.installIfNeeded(this)
            android.util.Log.d("MOAG", "ProviderInstaller succeeded")
        } catch (e: Exception) {
            android.util.Log.e("MOAG", "ProviderInstaller failed", e)
        }
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Moag::AgentWakeLock")
        wakeLock?.acquire()
        
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "moag_channel")
            .setContentTitle("Moag Agent")
            .setContentText("Initializing...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                } else {
                    startForeground(1, notification)
                }
            } catch (e: Exception) {
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("MOAG", "AgentService wakeup (onStartCommand)")
        
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        val encryptedPrefs = EncryptedSharedPreferences.create(
            this,
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        
        val prefs = getSharedPreferences("moag_prefs", Context.MODE_PRIVATE)
        val apiKey = intent?.getStringExtra("api_key") ?: encryptedPrefs.getString("gemini_key", "") ?: prefs.getString("gemini_api_key", "")
        val tabId = intent?.getStringExtra("tab_id")
        
        if (!tabId.isNullOrBlank()) {
            scope.launch {
                updateNotification("Agent working on tab...")
                GeminiAgent.runAgentLoop(this@AgentService, tabId, apiKey ?: "")
                
                val anyWorking = AppState.tabs.value.any { it.status == TabStatus.WORKING }
                if (!anyWorking) {
                    updateNotification("All tasks complete")
                    if (isActive) { android.util.Log.d("MOAG", "AgentService stopSelfResult called! startId: $startId"); stopSelfResult(startId) }
                }
            }
        } else {
            val anyWorking = AppState.tabs.value.any { it.status == TabStatus.WORKING }
            if (!anyWorking) { android.util.Log.d("MOAG", "AgentService stopSelfResult called! startId: $startId"); stopSelfResult(startId) }
        }
        return START_STICKY
    }
    
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, "moag_channel")
            .setContentTitle("Moag Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }
    
    override fun onDestroy() {
        android.util.Log.d("MOAG", "AgentService onDestroy called!")
        super.onDestroy()
        job.cancel()
        wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("moag_channel", "Moag Agent", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
