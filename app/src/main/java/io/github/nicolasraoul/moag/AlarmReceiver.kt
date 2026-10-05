package io.github.nicolasraoul.moag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tabId = intent.getStringExtra("tab_id") ?: return
        val prompt = intent.getStringExtra("prompt") ?: return
        
        AppState.appendMessage(tabId, Message(role="user", content="[Scheduled Task] $prompt"))
        AppState.updateTab(tabId) { it.copy(status = TabStatus.WORKING) }
        
        val serviceIntent = Intent(context, AgentService::class.java).apply {
            putExtra("tab_id", tabId)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
