package io.github.nicolasraoul.moag

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class MainActivity : ComponentActivity() {

    private var tts: android.speech.tts.TextToSpeech? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Assume permissions granted for POC
        }

    private val _sharedFiles = mutableStateListOf<String>()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        intent.getStringExtra("auto_prompt")?.let { prompt ->
            if (prompt.isNotBlank()) {
                val tabId = AppState.addTab()
                AppState.activeTabId.value = tabId
                val tab = AppState.getTab(tabId)!!
                val sharedPrefs = getSharedPreferences("moag_prefs", android.content.Context.MODE_PRIVATE)
                
                intent.getStringExtra("api_key")?.let { newKey ->
                    sharedPrefs.edit().putString("gemini_api_key", newKey).apply()
                }
                val apiKey = sharedPrefs.getString("gemini_api_key", "") ?: ""
                
                AppState.appendMessage(tab.id, Message(role="user", content=prompt))
                AppState.updateTab(tab.id) { it.copy(status = TabStatus.WORKING) }
                // Let the agent handle the title
                
                val serviceIntent = android.content.Intent(this, AgentService::class.java).apply {
                    if (apiKey.isNotBlank()) {
                        putExtra("api_key", apiKey)
                    }
                    putExtra("tab_id", tab.id)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                intent.removeExtra("auto_prompt")
            }
        }

        val newSharedFiles = mutableListOf<String>()
        if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                try {
                    val filename = "shared_${System.currentTimeMillis()}"
                    val file = java.io.File(filesDir, filename)
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    newSharedFiles.add(file.absolutePath)
                } catch (e: Exception) {}
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            uris?.forEach { uri ->
                try {
                    val filename = "shared_${System.currentTimeMillis()}"
                    val file = java.io.File(filesDir, filename)
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    newSharedFiles.add(file.absolutePath)
                } catch (e: Exception) {}
            }
        }
        
        if (newSharedFiles.isNotEmpty()) {
            val tabId = AppState.addTab()
            val text = "I received ${newSharedFiles.size} shared files:\\n${newSharedFiles.joinToString("\\n")}\\n\\nWhat should I do with them?"
            AppState.appendMessage(tabId, Message(role="model", rawJson="{\"parts\":[{\"text\":\"$text\"}]}"))
            AppState.activeTabId.value = tabId
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        SkillManager.ensureDefaultSkills()
        enableEdgeToEdge()
        
        handleIntent(intent)
        
        // auto_prompt is now handled by handleIntent
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }

        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.getDefault()
            }
        }

        setContent {
            MaterialTheme {
                MoagApp(this, _sharedFiles, speak = { text -> 
                    tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "TTS_REQUEST_ID")
                })
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoagApp(context: Context, sharedFiles: List<String>, speak: (String) -> Unit = {}) {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    var apiKey by remember { mutableStateOf(encryptedPrefs.getString("gemini_key", "") ?: "") }
    val prefs = context.getSharedPreferences("moag_prefs", Context.MODE_PRIVATE)
    var imapUsername by remember { mutableStateOf(encryptedPrefs.getString("imap_username", "") ?: "") }
    var imapPassword by remember { mutableStateOf(encryptedPrefs.getString("imap_password", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var askPermission by remember { mutableStateOf(prefs.getBoolean("ask_permission", true)) }
    
    var allowlistedApps by remember { 
        mutableStateOf<List<String>>(prefs.getStringSet("allowlisted_apps", emptySet<String>())?.toList()?.sorted() ?: emptyList()) 
    }
    var allowlistedIntents by remember { 
        mutableStateOf<List<String>>(prefs.getStringSet("allowlisted_intents", emptySet<String>())?.toList()?.sorted() ?: emptyList()) 
    }
    var newAppInput by remember { mutableStateOf("") }
    var newIntentInput by remember { mutableStateOf("") }
    var skillCount by remember { mutableIntStateOf(0) }
    var loadedSkills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    
    val tabs by AppState.tabs.collectAsState()
    val activeTabId by AppState.activeTabId.collectAsState()
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(activeTabId, tabs) {
        if (activeTabId != null) {
            val index = tabs.indexOfFirst { it.id == activeTabId }
            if (index >= 0) {
                selectedTabIndex = index
                AppState.activeTabId.value = null // reset
            }
        } else if (selectedTabIndex >= tabs.size && tabs.isNotEmpty()) {
            selectedTabIndex = tabs.size - 1
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moag") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = {
                            selectedTabIndex = index 
                            AppState.markTabSeen(tab.id)
                        },
                        text = { 
                            Row(
                                modifier = Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            selectedTabIndex = index
                                            AppState.markTabSeen(tab.id)
                                        },
                                        onLongPress = {
                                            AppState.removeTab(tab.id)
                                            if (selectedTabIndex >= tabs.size - 1) {
                                                selectedTabIndex = maxOf(0, tabs.size - 2)
                                            }
                                        }
                                    )
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tab.title)
                                Spacer(Modifier.width(4.dp))
                                when (tab.status) {
                                    TabStatus.WORKING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    TabStatus.UNSEEN_RESULTS -> Icon(Icons.Default.CheckCircle, "Done", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    TabStatus.AWAITING_PERMISSION -> Icon(Icons.Default.Warning, "Permission Needed", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    else -> {}
                                }
                            }
                        }
                    )
                }
                Tab(
                    selected = false,
                    onClick = { 
                        AppState.addTab()
                        selectedTabIndex = tabs.size
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = "New Tab") }
                )
            }
            
            if (tabs.isNotEmpty()) {
                val currentTab = tabs[selectedTabIndex]
                
                if (currentTab.localLlmReason != null) {
                    Surface(color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Using local LLM because ${currentTab.localLlmReason}",
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                
                TabContent(context, currentTab, apiKey, sharedFiles, speak)
            }
        }
    }
    
    if (showSettings) {
        LaunchedEffect(Unit) {
            allowlistedApps = prefs.getStringSet("allowlisted_apps", emptySet<String>())?.toList()?.sorted() ?: emptyList()
            allowlistedIntents = prefs.getStringSet("allowlisted_intents", emptySet<String>())?.toList()?.sorted() ?: emptyList()
            skillCount = SkillManager.getSdcardSkillCount()
            loadedSkills = SkillManager.getSkillsFromSdcard()
        }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { 
                            apiKey = it
                            encryptedPrefs.edit().putString("gemini_key", it).apply()
                        },
                        label = { Text("Gemini API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Optional: For background email access (via IMAP), provide your Gmail address and an App Password (not your regular password). To generate one, go to your Google Account Security settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = imapUsername,
                        onValueChange = { 
                            imapUsername = it
                            encryptedPrefs.edit().putString("imap_username", it).apply()
                        },
                        label = { Text("Gmail Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = imapPassword,
                        onValueChange = { 
                            imapPassword = it
                            encryptedPrefs.edit().putString("imap_password", it).apply()
                        },
                        label = { Text("Gmail App Password") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = askPermission,
                            onCheckedChange = { 
                                askPermission = it
                                prefs.edit().putBoolean("ask_permission", it).apply()
                            }
                        )
                        Text("Ask for permission before acting on other apps", style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Allowlisted Apps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Apps that the agent can launch without asking for permission:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (allowlistedApps.isEmpty()) {
                        Text("No allowlisted apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        allowlistedApps.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(app, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        val updated = allowlistedApps.filter { it != app }
                                        allowlistedApps = updated
                                        prefs.edit().putStringSet("allowlisted_apps", updated.toSet()).apply()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove $app", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newAppInput,
                            onValueChange = { newAppInput = it },
                            label = { Text("Add app package") },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = {
                                if (newAppInput.isNotBlank() && !allowlistedApps.contains(newAppInput.trim())) {
                                    val updated = (allowlistedApps + newAppInput.trim()).sorted()
                                    allowlistedApps = updated
                                    prefs.edit().putStringSet("allowlisted_apps", updated.toSet()).apply()
                                    newAppInput = ""
                                }
                            },
                            enabled = newAppInput.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Allowlisted Intents", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Specific intents that the agent can launch without asking for permission:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (allowlistedIntents.isEmpty()) {
                        Text("No allowlisted intents.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        allowlistedIntents.forEach { intent ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(intent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        val updated = allowlistedIntents.filter { it != intent }
                                        allowlistedIntents = updated
                                        prefs.edit().putStringSet("allowlisted_intents", updated.toSet()).apply()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove $intent", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newIntentInput,
                            onValueChange = { newIntentInput = it },
                            label = { Text("Add intent") },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = {
                                if (newIntentInput.isNotBlank() && !allowlistedIntents.contains(newIntentInput.trim())) {
                                    val updated = (allowlistedIntents + newIntentInput.trim()).sorted()
                                    allowlistedIntents = updated
                                    prefs.edit().putStringSet("allowlisted_intents", updated.toSet()).apply()
                                    newIntentInput = ""
                                }
                            },
                            enabled = newIntentInput.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Skills", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$skillCount skill${if (skillCount == 1) "" else "s"} presently in /sdcard/skills/",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (loadedSkills.isNotEmpty()) {
                        loadedSkills.forEach { skill ->
                            Text(
                                "• ${skill.id}${if (skill.condition.isNotBlank()) ": ${skill.condition}" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                            )
                        }
                    } else {
                        Text(
                            "Put skill directories containing SKILL.md in /sdcard/skills/ following the Agent Skills format (https://github.com/agentskills/agentskills).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("OK") }
            }
        )
    }
}

@Composable
fun TabContent(context: Context, tab: ConversationTab, apiKey: String, sharedFiles: List<String>, speak: (String) -> Unit = {}) {
    var taskInput by remember { mutableStateOf("") }
    var showThoughts by remember { mutableStateOf(false) }
    var alwaysAllowIntent by remember(tab.permissionRequest?.id) { mutableStateOf(false) }
    var alwaysAllowApp by remember(tab.permissionRequest?.id) { mutableStateOf(false) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentCameraTabId by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
        currentCameraTabId?.let { tid ->
            if (success) {
                AppState.updateTab(tid) { it.copy(permissionRequest = null, status = TabStatus.WORKING) }
            } else {
                AppState.updateTab(tid) { it.copy(permissionRequest = null, actionResult = "denied", status = TabStatus.WORKING) }
            }
        }
        currentCameraTabId = null
    }
    
    // No longer using global sharedFiles field since files are logged as a message directly
    
    if (tab.status == TabStatus.AWAITING_PERMISSION && tab.permissionRequest != null) {
        val req = tab.permissionRequest
        AlertDialog(
            onDismissRequest = { 
                AppState.updateTab(tab.id) { it.copy(permissionRequest = null, status = TabStatus.IDLE) }
            },
            title = { Text("Permission Requested") },
            text = {
                Column {
                    Text("Agent wants to ${req.type}:\n\n${req.details}")
                    
                    if (!req.intentIdentifier.isNullOrBlank() || !req.packageName.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        
                        if (!req.intentIdentifier.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { alwaysAllowIntent = !alwaysAllowIntent }
                            ) {
                                Checkbox(
                                    checked = alwaysAllowIntent,
                                    onCheckedChange = { alwaysAllowIntent = it }
                                )
                                Text(
                                    text = "Always allow for this intent",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        
                        if (!req.packageName.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { alwaysAllowApp = !alwaysAllowApp }
                            ) {
                                Checkbox(
                                    checked = alwaysAllowApp,
                                    onCheckedChange = { alwaysAllowApp = it }
                                )
                                Text(
                                    text = "Always allow for this app",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val prefs = context.getSharedPreferences("moag_prefs", Context.MODE_PRIVATE)

                    if (alwaysAllowIntent && !req.intentIdentifier.isNullOrBlank()) {
                        val currentIntents = prefs.getStringSet("allowlisted_intents", emptySet())?.toMutableSet() ?: mutableSetOf()
                        currentIntents.add(req.intentIdentifier)
                        prefs.edit().putStringSet("allowlisted_intents", currentIntents).apply()
                    }

                    if (alwaysAllowApp && !req.packageName.isNullOrBlank()) {
                        val currentApps = prefs.getStringSet("allowlisted_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
                        currentApps.add(req.packageName)
                        prefs.edit().putStringSet("allowlisted_apps", currentApps).apply()
                    }

                    if (req.type == "Camera") {
                        currentCameraTabId = tab.id
                        val photoFile = java.io.File(context.filesDir, "camera_capture_${System.currentTimeMillis()}.jpg")
                        val photoUri = androidx.core.content.FileProvider.getUriForFile(context, "io.github.nicolasraoul.moag.fileprovider", photoFile)
                        AppState.updateTab(tab.id) { it.copy(actionResult = photoFile.absolutePath) }
                        cameraLauncher.launch(photoUri)
                    } else if (req.type == "Share File") {
                        val file = java.io.File(req.payload)
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "io.github.nicolasraoul.moag.fileprovider", file)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share via"))
                        AppState.updateTab(tab.id) { it.copy(permissionRequest = null, actionResult = "success", status = TabStatus.WORKING) }
                    } else {
                        AppState.updateTab(tab.id) { it.copy(permissionRequest = null, status = TabStatus.WORKING) }
                    }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppState.updateTab(tab.id) { it.copy(permissionRequest = null, actionResult = "denied", status = TabStatus.IDLE) }
                }) { Text("Deny") }
            }
        )
    }
    
    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        OutlinedTextField(
            value = taskInput,
            onValueChange = { taskInput = it },
            label = { Text("What should the agent do?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = tab.status != TabStatus.WORKING
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                if (taskInput.isNotBlank()) {
                    AppState.appendMessage(tab.id, Message(role="user", content=taskInput))
                    
                    AppState.updateTab(tab.id) { it.copy(status = TabStatus.WORKING) }
                    
                    val intent = Intent(context, AgentService::class.java).apply {
                        if (apiKey.isNotBlank()) putExtra("api_key", apiKey)
                        putExtra("tab_id", tab.id)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    taskInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = taskInput.isNotBlank() && tab.status != TabStatus.WORKING
        ) {
            Text(if (tab.status == TabStatus.WORKING) "Working..." else "Send to Agent")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                
                tab.messages.forEach { msg ->
                    if (msg.role == "user" && msg.content != null) {
                        Text("You: ${msg.content}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (msg.role == "model") {
                        val agentTexts = mutableListOf<String>()
                        if (msg.content != null) {
                            agentTexts.add(msg.content)
                        } else if (msg.rawJson != null) {
                            try {
                                val json = org.json.JSONObject(msg.rawJson)
                                val parts = json.optJSONArray("parts") ?: org.json.JSONArray()
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)
                                    if (part.has("text")) {
                                        val text = part.getString("text").replace("TASK_COMPLETE", "").trim()
                                        if (text.isNotEmpty()) {
                                            agentTexts.add(text)
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                        
                        agentTexts.forEach { text ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Agent: $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { speak(text) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Speak", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                
                if (tab.messages.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (tab.thoughts.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showThoughts = !showThoughts },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showThoughts || tab.status == TabStatus.WORKING) "▼ Hide Thoughts" else "▶ View Thoughts",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (showThoughts || tab.status == TabStatus.WORKING) {
                        Text(
                            text = tab.thoughts,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
