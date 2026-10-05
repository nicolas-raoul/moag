package io.github.nicolasraoul.moag

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class TabStatus {
    IDLE, WORKING, UNSEEN_RESULTS, AWAITING_PERMISSION
}

data class PermissionRequest(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,
    val details: String,
    val payload: String = "",
    val packageName: String? = null,
    val intentIdentifier: String? = null
)

data class Message(
    val role: String, // "user", "model", "function"
    val content: String? = null,
    val rawJson: String? = null
)

data class ConversationTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Task",
    val status: TabStatus = TabStatus.IDLE,
    val messages: List<Message> = emptyList(),
    val thoughts: String = "",
    val finalResponse: String = "",
    val permissionRequest: PermissionRequest? = null,
    val actionResult: String? = null,
    val localLlmReason: String? = null
)

object AppState {
    private val _tabs = MutableStateFlow<List<ConversationTab>>(emptyList())
    val tabs: StateFlow<List<ConversationTab>> = _tabs.asStateFlow()
    
    val activeTabId = MutableStateFlow<String?>(null)
    
    private val gson = Gson()
    private lateinit var file: File
    
    fun init(context: Context) {
        file = File(context.filesDir, "moag_tabs.json")
        if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<ConversationTab>>() {}.type
                val loaded: List<ConversationTab> = gson.fromJson(json, type) ?: emptyList()
                _tabs.value = loaded
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (_tabs.value.isEmpty()) {
            addTab()
        }
    }
    
    @Synchronized
    private fun save() {
        if (!::file.isInitialized) return
        try {
            val sanitizedTabs = _tabs.value.map { tab ->
                tab.copy(
                    messages = tab.messages.map { msg ->
                        if (msg.rawJson != null && msg.rawJson.length > 50000) {
                            msg.copy(rawJson = null, content = msg.content ?: "[media content]")
                        } else msg
                    }
                )
            }
            val json = gson.toJson(sanitizedTabs)
            file.writeText(json)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
    
    fun addTab(): String {
        val newTab = ConversationTab(title = "Tab ${_tabs.value.size + 1}")
        _tabs.update { it + newTab }
        save()
        return newTab.id
    }
    
    fun removeTab(id: String) {
        _tabs.update { list -> list.filter { it.id != id } }
        save()
    }
    
    fun updateTab(id: String, block: (ConversationTab) -> ConversationTab) {
        _tabs.update { list ->
            list.map { if (it.id == id) block(it) else it }
        }
        save()
    }
    
    fun getTab(id: String): ConversationTab? {
        return _tabs.value.find { it.id == id }
    }
    
    fun appendMessage(id: String, message: Message) {
        updateTab(id) { tab ->
            tab.copy(messages = tab.messages + message)
        }
    }
    
    fun appendThought(id: String, thoughtLine: String) {
        updateTab(id) { tab ->
            tab.copy(thoughts = tab.thoughts + thoughtLine + "\n")
        }
    }
    
    fun markTabSeen(id: String) {
        updateTab(id) { 
            if (it.status == TabStatus.UNSEEN_RESULTS) it.copy(status = TabStatus.IDLE) else it
        }
    }
    
    suspend fun waitForPermission(
        context: android.content.Context, 
        tabId: String, 
        type: String, 
        details: String,
        packageName: String? = null,
        intentIdentifier: String? = null
    ): Boolean {
        val prefs = context.getSharedPreferences("moag_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ask_permission", true)) {
            return true
        }
        val allowlistedApps = prefs.getStringSet("allowlisted_apps", emptySet()) ?: emptySet()
        val allowlistedIntents = prefs.getStringSet("allowlisted_intents", emptySet()) ?: emptySet()
        
        if (!packageName.isNullOrBlank() && allowlistedApps.contains(packageName)) {
            return true
        }
        if (!intentIdentifier.isNullOrBlank() && allowlistedIntents.contains(intentIdentifier)) {
            return true
        }

        val reqId = java.util.UUID.randomUUID().toString()
        updateTab(tabId) { 
            it.copy(
                permissionRequest = PermissionRequest(
                    id = reqId, 
                    type = type, 
                    details = details,
                    packageName = packageName,
                    intentIdentifier = intentIdentifier
                ), 
                status = TabStatus.AWAITING_PERMISSION
            ) 
        }
        
        var approved = false
        while (true) {
            val tab = getTab(tabId) ?: break
            if (tab.permissionRequest == null) {
                approved = tab.status == TabStatus.WORKING
                break
            }
            kotlinx.coroutines.delay(500)
        }
        return approved
    }

    suspend fun requestActionFromUI(
        tabId: String, 
        type: String, 
        details: String, 
        payload: String = "",
        packageName: String? = null,
        intentIdentifier: String? = null
    ): String? {
        val reqId = java.util.UUID.randomUUID().toString()
        updateTab(tabId) { 
            it.copy(
                permissionRequest = PermissionRequest(
                    id = reqId, 
                    type = type, 
                    details = details, 
                    payload = payload,
                    packageName = packageName,
                    intentIdentifier = intentIdentifier
                ), 
                actionResult = null, 
                status = TabStatus.AWAITING_PERMISSION
            ) 
        }
        
        var result: String? = null
        while (true) {
            val tab = getTab(tabId) ?: break
            if (tab.permissionRequest == null) {
                if (tab.status == TabStatus.WORKING) {
                    result = tab.actionResult
                }
                break
            }
            kotlinx.coroutines.delay(500)
        }
        return result
    }
}
