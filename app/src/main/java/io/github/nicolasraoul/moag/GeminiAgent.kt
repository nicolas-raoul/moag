package io.github.nicolasraoul.moag

import android.content.Context
import android.location.LocationManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

object GeminiAgent {
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
        
    fun isNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Throwable) {
            true
        }
    }
        
    private fun generateShortTitle(apiKey: String, text: String): String {
        if (apiKey.isBlank()) return "Task"
        
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Summarize this task into EXACTLY ONE WORD. Do not use quotes, punctuation, or multiple words. Examples: 'take a picture every minute' -> 'time', 'what is the tide' -> 'tide', 'how tall is the eiffel tower' -> 'tower'. Task: $text")
                        })
                    })
                })
            })
        }
        
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .header("User-Agent", "Moag 1.0")
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
            
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBodyStr = response.body?.string() ?: ""
                val responseJson = JSONObject(responseBodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val message = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = message?.optJSONArray("parts") ?: JSONArray()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("text")) {
                            return part.getString("text").trim().split("\\s+".toRegex()).firstOrNull()?.replace("[^a-zA-Z0-9]".toRegex(), "") ?: "Task"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Task"
    }

    suspend fun runAgentLoop(context: Context, tabId: String, apiKey: String) {
        try {
            AppState.appendThought(tabId, "AGENT LOOP STARTED!!!")
                        
            var tab = AppState.getTab(tabId) ?: return
            if (tab.status != TabStatus.IDLE && tab.status != TabStatus.WORKING) {
                return
            }
            
            if (tab.title.startsWith("Tab")) {
                val firstUserMsg = tab.messages.firstOrNull { it.role == "user" }?.content
                if (firstUserMsg != null) {
                    val shortTitle = generateShortTitle(apiKey, firstUserMsg)
                    AppState.updateTab(tabId) { it.copy(title = shortTitle) }
                    tab = AppState.getTab(tabId)!!
                }
            }
            
            Log.d("MOAG", "Wakeup / Agent loop start. Tab: $tabId")
            AppState.updateTab(tabId) { it.copy(status = TabStatus.WORKING) }
            
            var isDone = false
            var step = 0
            var sessionUsedLocalLlm = false
            
            val allUserPrompts = tab.messages.filter { it.role == "user" }.mapNotNull { it.content }.joinToString("\n")
            val allSkills = SkillManager.getSkills(context)
            val selectedSkills = if (allUserPrompts.isNotBlank()) {
                SkillManager.analyzeSkillsForPrompt(apiKey, allUserPrompts, allSkills)
            } else emptyList()
            
            val skillsText = if (selectedSkills.isNotEmpty()) {
                "Pay attention to the following user preferences:\n" + selectedSkills.joinToString("\n") { "- ${it.advice}" }
            } else ""
            Log.d("MOAG", "Injected skills: $skillsText")
            
            while (!isDone && step < 15) {
                try {
                    step++
                val currentTab = AppState.getTab(tabId) ?: break
                
                val history = JSONArray()
                for (msg in currentTab.messages) {
                    if (msg.rawJson != null) {
                        history.put(JSONObject(msg.rawJson))
                    } else if (msg.content != null) {
                        history.put(JSONObject().apply {
                            put("role", msg.role)
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", msg.content) })
                            })
                        })
                    }
                }
                
                val requestBodyJson = buildRequestBody(history, skillsText)
                Log.d("MOAG", "LLM call request: $requestBodyJson")
                
                var responseJson: JSONObject? = null
                var usedLocalLlm = sessionUsedLocalLlm
                
                if (apiKey.isBlank()) {
                    usedLocalLlm = true
                    sessionUsedLocalLlm = true
                    AppState.updateTab(tabId) { it.copy(localLlmReason = "No API key configured") }
                }
                
                if (!usedLocalLlm) {
                    if (!isNetworkConnected(context)) {
                        AppState.appendThought(tabId, "No internet connection detected. Falling back to local LLM immediately.")
                        usedLocalLlm = true
                        sessionUsedLocalLlm = true
                        AppState.updateTab(tabId) { it.copy(localLlmReason = "there is no internet connection") }
                    } else {
                        val retryStart = System.currentTimeMillis()
                        val maxRetryDurationMs = 120_000L // ~2 minutes
                        var attempt = 0
                        var delayMs = 2_000L

                        while (responseJson == null && !usedLocalLlm) {
                            if (!isNetworkConnected(context)) {
                                AppState.appendThought(tabId, "Lost internet connection. Falling back to local LLM immediately.")
                                usedLocalLlm = true
                                sessionUsedLocalLlm = true
                                AppState.updateTab(tabId) { it.copy(localLlmReason = "there is no internet connection") }
                                break
                            }

                            attempt++
                            try {
                                val client = OkHttpClient.Builder()
                                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                    .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()

                                val request = Request.Builder()
                                    .header("User-Agent", "Moag 1.0")
                                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey")
                                    .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                                    .build()
                                    
                                val response = client.newCall(request).execute()
                                if (!response.isSuccessful) {
                                    val errorBody = response.body?.string() ?: ""
                                    Log.e("MOAG", "HTTP Error: ${response.code} body: $errorBody (attempt $attempt)")
                                    val isRetryable = response.code >= 500 || response.code == 429
                                    val elapsedMs = System.currentTimeMillis() - retryStart
                                    if (isRetryable && (elapsedMs + delayMs < maxRetryDurationMs) && isNetworkConnected(context)) {
                                        val remainingSec = ((maxRetryDurationMs - elapsedMs) / 1000).coerceAtLeast(1)
                                        AppState.appendThought(tabId, "Online API returned HTTP ${response.code}. Retrying in ${delayMs / 1000}s (will retry for ~${remainingSec}s before falling back to local LLM)...")
                                        kotlinx.coroutines.delay(delayMs)
                                        delayMs = (delayMs * 1.5).toLong().coerceAtMost(15_000L)
                                        continue
                                    }
                                    AppState.appendThought(tabId, "Online API returned HTTP ${response.code} after retrying for ${elapsedMs / 1000}s. Falling back to local LLM.")
                                    usedLocalLlm = true
                                    sessionUsedLocalLlm = true
                                    AppState.updateTab(tabId) { it.copy(localLlmReason = "Online API failed (HTTP ${response.code})") }
                                } else {
                                    val responseBodyStr = response.body?.string() ?: ""
                                    responseJson = JSONObject(responseBodyStr)
                                    AppState.updateTab(tabId) { it.copy(localLlmReason = null) }
                                }
                            } catch (e: Exception) {
                                val errMsg = e.message ?: e.toString()
                                Log.e("MOAG", "Online LLM attempt $attempt failed with exception: $errMsg", e)
                                if (e is java.net.UnknownHostException || !isNetworkConnected(context)) {
                                    AppState.appendThought(tabId, "No internet connection ($errMsg). Falling back to local LLM immediately.")
                                    usedLocalLlm = true
                                    sessionUsedLocalLlm = true
                                    AppState.updateTab(tabId) { it.copy(localLlmReason = "there is no internet connection") }
                                    break
                                }
                                val elapsedMs = System.currentTimeMillis() - retryStart
                                if ((elapsedMs + delayMs < maxRetryDurationMs) && isNetworkConnected(context)) {
                                    val remainingSec = ((maxRetryDurationMs - elapsedMs) / 1000).coerceAtLeast(1)
                                    AppState.appendThought(tabId, "Online API call failed ($errMsg). Retrying in ${delayMs / 1000}s (will retry for ~${remainingSec}s before falling back)...")
                                    kotlinx.coroutines.delay(delayMs)
                                    delayMs = (delayMs * 1.5).toLong().coerceAtMost(15_000L)
                                    continue
                                }
                                AppState.appendThought(tabId, "Online API failed after retrying for ${elapsedMs / 1000}s ($errMsg). Falling back to local LLM.")
                                usedLocalLlm = true
                                sessionUsedLocalLlm = true
                                AppState.updateTab(tabId) { it.copy(localLlmReason = "Online API failed (${e.javaClass.simpleName})") }
                            }
                        }
                    }
                }
                
                if (usedLocalLlm) {
                    var localSuccess = false
                    var attempts = 0
                    while (!localSuccess && attempts < 3) {
                        attempts++
                        try {
                            val edgeModel = com.google.ai.edge.aicore.GenerativeModel(
                                com.google.ai.edge.aicore.generationConfig {
                                    this.context = context.applicationContext
                                }
                            )
                            val historyStr = history.toString()
                            val safeHistory = if (historyStr.length > 6000) historyStr.takeLast(6000) else historyStr
                            val localPrompt = "You are Moag, a mobile agent. Use tools to achieve the user's task. Output 'TASK_COMPLETE' when done.\n" +
                                "Available tools: web_request, get_time, get_gps, read_text_file, read_image_file, write_text_file, take_picture, send_intent_to_app, generate_image, fetch_recent_emails.\n" +
                                "To use a tool, output JSON like this: { \"functionCall\": { \"name\": \"tool_name\", \"args\": { ... } } } otherwise just output your text.\n" +
                                "Conversation history:\n$safeHistory\nSkills: $skillsText\nRespond now:"
                                
                            var accumulatedText = ""
                            edgeModel.generateContentStream(localPrompt).collect { res ->
                                accumulatedText += res.text ?: ""
                            }
                            edgeModel.close()
                            
                            if (accumulatedText.isBlank()) {
                                throw Exception("AICore returned empty response (RESPONSE_PROCESSING_ERROR).")
                            }
                            
                            var isFunctionCall = false
                            var funcName = ""
                            var funcArgs = JSONObject()
                            try {
                                if (accumulatedText.contains("functionCall")) {
                                    val match = Regex("\\{.*\"functionCall\".*\\}", RegexOption.DOT_MATCHES_ALL).find(accumulatedText)
                                    if (match != null) {
                                        val obj = JSONObject(match.value).getJSONObject("functionCall")
                                        funcName = obj.getString("name")
                                        funcArgs = obj.optJSONObject("args") ?: JSONObject()
                                        isFunctionCall = true
                                    }
                                }
                            } catch (e: Exception) {}
                            
                            responseJson = JSONObject().apply {
                                put("candidates", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("content", JSONObject().apply {
                                            put("parts", JSONArray().apply {
                                                if (isFunctionCall) {
                                                    put(JSONObject().apply {
                                                        put("functionCall", JSONObject().apply {
                                                            put("name", funcName)
                                                            put("args", funcArgs)
                                                        })
                                                    })
                                                } else {
                                                    put(JSONObject().apply {
                                                        put("text", accumulatedText)
                                                    })
                                                }
                                            })
                                        })
                                    })
                                })
                            }
                            
                            localSuccess = true
                            
                        } catch (e: Exception) {
                            val errMsg = e.message ?: e.toString()
                            Log.e("MOAG", "Local LLM attempt $attempts failed: $errMsg", e)
                            
                            val isPermanent = errMsg.contains("BINDING_FAILURE") || 
                                              errMsg.contains("failed to bind") || 
                                              errMsg.contains("not available") ||
                                              errMsg.contains("unsupported", ignoreCase = true)
                            
                            if (isPermanent) {
                                AppState.appendThought(tabId, "Local LLM permanent failure: $errMsg. Device does not support AICore.")
                                AppState.appendMessage(tabId, Message(role = "model", content = "Error: Local LLM fallback failed because your device does not support Gemini Nano (AICore). Details: $errMsg"))
                                AppState.updateTab(tabId) { it.copy(status = TabStatus.IDLE) }
                                isDone = true
                                break
                            } else {
                                if (attempts < 3) {
                                    AppState.appendThought(tabId, "Local LLM transient failure: $errMsg. Retrying in 2 seconds (Attempt $attempts/3)...")
                                    kotlinx.coroutines.delay(2000)
                                } else {
                                    AppState.appendThought(tabId, "Local LLM failed after 3 attempts: $errMsg")
                                    AppState.appendMessage(tabId, Message(role = "model", content = "Error: Local LLM fallback failed after multiple attempts. Details: $errMsg"))
                                    AppState.updateTab(tabId) { it.copy(status = TabStatus.IDLE) }
                                    isDone = true
                                    break
                                }
                            }
                        }
                    }
                    
                    if (!localSuccess) {
                        isDone = true
                        continue
                    }
                }
                    
                    val candidates = responseJson!!.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val message = candidates.getJSONObject(0).optJSONObject("content")
                        if (message != null) {
                            val parts = message.optJSONArray("parts") ?: JSONArray()
                            var madeToolCall = false
                            var finalString = ""
                            
                            val functionResponses = JSONArray()
                            var injectedImageBase64: String? = null
                            
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.has("functionCall")) {
                                    madeToolCall = true
                                    val functionCall = part.getJSONObject("functionCall")
                                    val name = functionCall.getString("name")
                                    val args = functionCall.optJSONObject("args") ?: JSONObject()
                                    val callId = functionCall.optString("id", "")
                                    
                                    AppState.appendThought(tabId, "Invoking tool: $name\nArgs: $args")
                                    
                                    val result = executeTool(context, tabId, name, args, apiKey)
                                    
                                    if (result.has("base64_data")) {
                                        injectedImageBase64 = result.getString("base64_data")
                                        result.remove("base64_data")
                                        result.put("status", "image provided to vision context")
                                    }
                                    
                                    if (name != "web_request") {
                                        val resStr = result.toString()
                                        val shortRes = if (resStr.length > 500) resStr.take(500) + "..." else resStr
                                        AppState.appendThought(tabId, "Tool response: $shortRes")
                                    }
                                    
                                    functionResponses.put(JSONObject().apply {
                                        put("functionResponse", JSONObject().apply {
                                            put("name", name)
                                            put("response", result)
                                            if (callId.isNotEmpty()) put("id", callId)
                                        })
                                    })
                                } else if (part.has("text")) {
                                    val text = part.getString("text")
                                    
                                    val cleanText = text.replace("TASK_COMPLETE", "").trim()
                                    if (cleanText.isNotEmpty()) {
                                        finalString += cleanText + "\n"
                                        AppState.appendThought(tabId, "Thinking: $cleanText")
                                    }
                                    if (text.contains("TASK_COMPLETE")) {
                                        isDone = true
                                    }
                                }
                            }
                        
                        AppState.appendMessage(tabId, Message(role="model", rawJson=message.toString()))
                        
                        if (madeToolCall) {
                            val userParts = JSONArray()
                            for (idx in 0 until functionResponses.length()) {
                                userParts.put(functionResponses.getJSONObject(idx))
                            }
                            if (injectedImageBase64 != null) {
                                userParts.put(JSONObject().apply {
                                    put("inlineData", JSONObject().apply {
                                        put("mimeType", "image/jpeg")
                                        put("data", injectedImageBase64)
                                    })
                                })
                                userParts.put(JSONObject().apply { put("text", "Here is the image you requested to read.") })
                            }
                            if (step >= 4) {
                                userParts.put(JSONObject().apply { put("text", "Please summarize your findings and provide your final response to the user now, ending with TASK_COMPLETE.") })
                            }
                            val funcObj = JSONObject().apply {
                                put("role", "user")
                                put("parts", userParts)
                            }
                            AppState.appendMessage(tabId, Message(role="user", rawJson=funcObj.toString()))
                        }
                        
                        if (isDone && finalString.isNotEmpty()) {
                            AppState.updateTab(tabId) { it.copy(finalResponse = finalString, status = TabStatus.UNSEEN_RESULTS) }
                        }
                        
                        if (!madeToolCall && !isDone) {
                            isDone = true
                            if (finalString.isNotEmpty()) {
                                AppState.updateTab(tabId) { it.copy(finalResponse = finalString, status = TabStatus.UNSEEN_RESULTS) }
                            }
                        }
                    } else {
                        isDone = true
                    }
                } else {
                    isDone = true
                }
                
            } catch (e: Throwable) {
                Log.e("MOAG", "Exception during LLM call", e)
                AppState.appendThought(tabId, "Exception: ${e.message}")
                Thread.sleep(5000)
            }
        }
        } catch (e: Exception) {
            Log.e("MOAG", "Fatal Exception in runAgentLoop", e)
            AppState.appendThought(tabId, "Fatal Error: ${e.message}")
        }
        Log.d("MOAG", "Agent loop finished.")
        AppState.updateTab(tabId) { if (it.status == TabStatus.WORKING) it.copy(status = TabStatus.IDLE) else it }
    }
    
    private fun buildRequestBody(history: JSONArray, skillsText: String = ""): JSONObject {
        val tools = JSONArray().apply {
            put(JSONObject().apply {
                put("functionDeclarations", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "web_request")
                        put("description", "Perform an HTTP GET request to fetch content")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("url", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("url") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_time")
                        put("description", "Get the current time on the device")
                    })
                    put(JSONObject().apply {
                        put("name", "get_gps")
                        put("description", "Get current GPS coordinates")
                    })
                    put(JSONObject().apply {
                        put("name", "read_text_file")
                        put("description", "Read a text file from internal storage")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("filename", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("filename") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "read_image_file")
                        put("description", "Read an image file from internal storage or a given path. The image will be provided visually to you in the next message.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("filename", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("filename") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "write_text_file")
                        put("description", "Write text to a file in internal storage or downloads")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("filename", JSONObject().apply { put("type", "STRING") })
                                put("content", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("filename"); put("content") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "read_media_storage")
                        put("description", "Get the file paths of the most recent photos in the device gallery. Optionally provide a folder_filter to only include photos from a specific folder (e.g. 'DCIM/Camera').")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("folder_filter", JSONObject().apply { put("type", "STRING") })
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "take_picture")
                        put("description", "Launch the camera to take a picture and return the file path")
                    })
                    put(JSONObject().apply {
                        put("name", "send_intent_to_app")
                        put("description", "Share a file path to another app via Android ACTION_SEND intent")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("file_path", JSONObject().apply { put("type", "STRING") })
                                put("mime_type", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("file_path"); put("mime_type") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "generate_image")
                        put("description", "Generate an image and save it to a file. Include 'Download' in filename to save to Downloads folder. Returns the file path.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("prompt", JSONObject().apply { put("type", "STRING") })
                                put("filename", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("prompt"); put("filename") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "move_file")
                        put("description", "Move or rename a file")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("source_path", JSONObject().apply { put("type", "STRING") })
                                put("destination_path", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("source_path"); put("destination_path") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "list_installed_apps")
                        put("description", "Get a list of installed apps and their package names. Use this to find the correct package before launching an intent.")
                    })
                    put(JSONObject().apply {
                        put("name", "set_alarm")
                        put("description", "Set an alarm using the device's default clock app. Uses 24-hour format.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("hour", JSONObject().apply { put("type", "INTEGER") })
                                put("minute", JSONObject().apply { put("type", "INTEGER") })
                                put("message", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("hour"); put("minute") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "launch_intent")
                        put("description", "Launch an application or intent.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("package_name", JSONObject().apply { put("type", "STRING") })
                                put("action", JSONObject().apply { put("type", "STRING") })
                                put("data_uri", JSONObject().apply { put("type", "STRING") })
                                put("mime_type", JSONObject().apply { put("type", "STRING") })
                                put("extra_stream_uri", JSONObject().apply { put("type", "STRING") })
                                put("extra_text", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("package_name") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "schedule_agent_task")
                        put("description", "Schedule a task for the agent to wake up and execute in the future. Useful for recurring tasks. You MUST call this tool explicitly if the user asks for periodic actions.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("delay_minutes", JSONObject().apply { put("type", "INTEGER") })
                                put("prompt", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply { put("delay_minutes"); put("prompt") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "fetch_recent_emails")
                        put("description", "Fetch recent unread emails using IMAP. If this returns an error about credentials, inform the user to tap the gear icon to enter their Gmail Address and App Password, and output TASK_COMPLETE immediately. Do NOT search for files.")
                    })
                })
            })
        }
        
        return JSONObject().apply {
            put("contents", history)
            put("tools", tools)
            put("systemInstruction", JSONObject().apply {
                put("role", "system")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        val baseInstruction = "You are Moag, a mobile agent on Android. Use tools to achieve the user's task. Always provide a clear, concise conversational final response to the user in text and output 'TASK_COMPLETE' when you are totally finished.\n\nCRITICAL INTEGRITY & HONESTY RULES:\n- NEVER fake, generate, or mock data, files, or photos when the user asks for their existing files, photos, or device state.\n- If a requested item (such as a specific photo or file) is missing or not found on the device, or if a task is impossible, clearly and honestly explain why to the user. Never substitute, fake, or generate mock content.\n- If the user asks about tides and the GPS location is inland or tide info is not applicable, state so conversationally and ask which coastal city they want to check."
                        val finalInstruction = if (skillsText.isNotBlank()) "$baseInstruction\n\n$skillsText" else baseInstruction
                        put("text", finalInstruction)
                    })
                })
            })
        }
    }
    
    private suspend fun executeTool(context: Context, tabId: String, name: String, args: JSONObject, apiKey: String): JSONObject {
        try {
            when (name) {
                "fetch_recent_emails" -> {
                    val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                        .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                        .build()

                    val encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        context,
                        "secret_shared_prefs",
                        masterKey,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    
                    val username = encryptedPrefs.getString("imap_username", "") ?: ""
                    val password = encryptedPrefs.getString("imap_password", "") ?: ""
                    
                    if (username.isEmpty() || password.isEmpty()) {
                        return JSONObject().apply { put("error", "Gmail Address or App Password is not set in Settings.") }
                    }
                    
                    val emails = EmailFetcher.fetchRecentUnreadEmails(username, password)
                    val emailsJson = JSONArray()
                    emails.forEach { emailsJson.put(it) }
                    return JSONObject().apply { put("emails", emailsJson) }
                }
                "get_time" -> {
                    return JSONObject().apply { put("time", Date().toString()) }
                }
                "web_request" -> {
                    val url = args.optString("url")
                    val request = Request.Builder().header("User-Agent", "Moag 1.0").url(url).build()
                    val response = client.newCall(request).execute()
                    val code = response.code
                    val body = response.body?.string()?.take(4000) ?: ""
                    val shortBody = body.take(1024)
                    AppState.appendThought(tabId, "Web Request: GET $url\nCode: $code\nBody Preview:\n$shortBody")
                    return JSONObject().apply { put("content", body) }
                }
                "get_gps" -> {
                    if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        try {
                            return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    lm.getCurrentLocation(
                                        LocationManager.GPS_PROVIDER,
                                        null,
                                        context.mainExecutor
                                    ) { l -> 
                                        val loc = l ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                                            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                                        if (loc != null) {
                                            cont.resumeWith(Result.success(JSONObject().apply {
                                                put("latitude", loc.latitude.toString())
                                                put("longitude", loc.longitude.toString())
                                            }))
                                        } else {
                                            cont.resumeWith(Result.success(JSONObject().apply { put("error", "Location unavailable.") }))
                                        }
                                    }
                                } else {
                                    val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                    if (l != null) {
                                        cont.resumeWith(Result.success(JSONObject().apply {
                                            put("latitude", l.latitude.toString())
                                            put("longitude", l.longitude.toString())
                                        }))
                                    } else {
                                        cont.resumeWith(Result.success(JSONObject().apply { put("error", "Last known location unavailable.") }))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            return JSONObject().apply { put("error", "Failed to get location: ${e.message}") }
                        }
                    } else {
                        return JSONObject().apply { put("error", "Location permission denied") }
                    }
                }
                "write_text_file" -> {
                    val filename = args.optString("filename")
                    val content = args.optString("content")
                    
                    val details = "File: $filename\nPreview: ${content.take(100)}..."
                    val approved = AppState.waitForPermission(context, tabId, "Write Text File", details)
                    if (!approved) {
                        return JSONObject().apply { put("error", "User denied permission") }
                    }
                    
                    val file = if (filename.startsWith("/")) File(filename) else {
                        if (filename.contains("Download", ignoreCase = true)) {
                            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), filename.substringAfterLast("/"))
                        } else {
                            File(context.filesDir, filename)
                        }
                    }
                    try {
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                        try {
                            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                        } catch (e: Exception) {}
                        return JSONObject().apply { put("success", true) }
                    } catch (e: Exception) {
                        return JSONObject().apply { put("error", "Failed to write file: ${e.message}") }
                    }
                }
                "read_text_file" -> {
                    val filename = args.optString("filename")
                    val file = if (filename.startsWith("/")) File(filename) else File(context.filesDir, filename)
                    val content = if (file.exists()) file.readText() else "File not found"
                    return JSONObject().apply { put("content", content) }
                }
                "read_image_file" -> {
                    val filename = args.optString("filename")
                    val file = if (filename.startsWith("/")) File(filename) else File(context.filesDir, filename)
                    if (!file.exists()) {
                        return JSONObject().apply { put("error", "Image file not found") }
                    }
                    try {
                        val options = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                        val maxDim = 1024
                        var sampleSize = 1
                        val maxOriginal = Math.max(options.outWidth, options.outHeight)
                        if (maxOriginal > maxDim) {
                            sampleSize = Math.round(maxOriginal.toFloat() / maxDim.toFloat())
                        }
                        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        }
                        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                        if (bitmap != null) {
                            val baos = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
                            val bytes = baos.toByteArray()
                            bitmap.recycle()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            return JSONObject().apply { put("base64_data", base64) }
                        } else {
                            val bytes = file.readBytes()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            return JSONObject().apply { put("base64_data", base64) }
                        }
                    } catch (e: Exception) {
                        val bytes = file.readBytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        return JSONObject().apply { put("base64_data", base64) }
                    }
                }
                "read_media_storage" -> {
                    val folderFilter = args.optString("folder_filter", "")
                    val projection = arrayOf(
                        android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Images.Media.DATA
                    )
                    val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                    val selection = if (folderFilter.isNotEmpty()) "${android.provider.MediaStore.Images.Media.DATA} LIKE ?" else null
                    val selectionArgs = if (folderFilter.isNotEmpty()) arrayOf("%$folderFilter%") else null
                    
                    val query = context.contentResolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection, selection, selectionArgs, sortOrder
                    )
                    val files = JSONArray()
                    query?.use { cursor ->
                        val dataColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
                        while (cursor.moveToNext() && files.length() < 10) {
                            files.put(cursor.getString(dataColumn))
                        }
                    }
                    return JSONObject().apply { put("recent_images", files) }
                }
                "take_picture" -> {
                    if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        return JSONObject().apply { put("error", "Camera permission denied.") }
                    }
                    val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM)
                    val moagDir = File(picturesDir, "Moag")
                    if (!moagDir.exists()) moagDir.mkdirs()
                    val imageFile = File(moagDir, "agent_capture_${System.currentTimeMillis()}.jpg")
                    try {
                        kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                            val cameraId = manager.cameraIdList.firstOrNull { 
                                manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK 
                            } ?: manager.cameraIdList.firstOrNull()
                            
                            if (cameraId == null) {
                                cont.resumeWith(Result.success(false))
                                return@suspendCancellableCoroutine
                            }
                            
                            val handler = android.os.Handler(android.os.Looper.getMainLooper())
                            try {
                                manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                                        val reader = android.media.ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)
                                        reader.setOnImageAvailableListener({ ir ->
                                            try {
                                                val image = ir.acquireLatestImage()
                                                if (image != null) {
                                                    val buffer = image.planes[0].buffer
                                                    val bytes = ByteArray(buffer.remaining())
                                                    buffer.get(bytes)
                                                    imageFile.writeBytes(bytes)
                                                    android.media.MediaScannerConnection.scanFile(context, arrayOf(imageFile.absolutePath), arrayOf("image/jpeg"), null)
                                                    image.close()
                                                }
                                            } finally {
                                                camera.close()
                                                if (cont.isActive) cont.resumeWith(Result.success(true))
                                            }
                                        }, handler)
                                        
                                        val surface = reader.surface
                                        val captureBuilder = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(surface)
                                            set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }
                                        
                                        val outputConfig = android.hardware.camera2.params.OutputConfiguration(surface)
                                        val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                                            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                                            listOf(outputConfig),
                                            context.mainExecutor,
                                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                                    try {
                                                        session.capture(captureBuilder.build(), null, handler)
                                                    } catch (e: Exception) {
                                                        camera.close()
                                                        if (cont.isActive) cont.resumeWith(Result.success(false))
                                                    }
                                                }
                                                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                                    camera.close()
                                                    if (cont.isActive) cont.resumeWith(Result.success(false))
                                                }
                                            }
                                        )
                                        camera.createCaptureSession(sessionConfig)
                                    }
                                    
                                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                                        camera.close()
                                        if (cont.isActive) cont.resumeWith(Result.success(false))
                                    }
                                    
                                    override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                                        camera.close()
                                        if (cont.isActive) cont.resumeWith(Result.success(false))
                                    }
                                }, handler)
                            } catch (e: Exception) {
                                android.util.Log.e("MOAG", "Error opening camera", e)
                                if (cont.isActive) cont.resumeWith(Result.success(false))
                            }
                        }
                        if (imageFile.exists() && imageFile.length() > 0) {
                            return JSONObject().apply { put("file_path", imageFile.absolutePath) }
                        } else {
                            return JSONObject().apply { put("error", "Failed to take picture (file empty or not created)") }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MOAG", "take_picture exception", e)
                        return JSONObject().apply { put("error", "Failed to take picture: ${e.message}") }
                    }
                }
                "send_intent_to_app" -> {
                    val filePath = args.optString("file_path")
                    val mimeType = args.optString("mime_type", "image/*")
                    val details = "File: $filePath\nMIME type: $mimeType"
                    val result = AppState.requestActionFromUI(tabId, "Share File", details, filePath, intentIdentifier = "Share File (android.intent.action.SEND)")
                    if (result != null && result != "denied") {
                        return JSONObject().apply { put("success", true) }
                    } else {
                        return JSONObject().apply { put("error", "User denied sharing") }
                    }
                }
                "move_file" -> {
                    val sourcePath = args.optString("source_path")
                    val destPath = args.optString("destination_path")
                    val source = File(sourcePath)
                    val dest = File(destPath)
                    if (!source.exists()) return JSONObject().apply { put("error", "Source file not found") }
                    try {
                        if (source.renameTo(dest)) {
                            return JSONObject().apply { put("success", true) }
                        } else {
                            source.copyTo(dest, overwrite = true)
                            source.delete()
                            return JSONObject().apply { put("success", true) }
                        }
                    } catch (e: Exception) {
                        return JSONObject().apply { put("error", "Failed to move file: ${e.message}") }
                    }
                }
                "list_installed_apps" -> {
                    val pm = context.packageManager
                    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                    intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    val activities = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
                    val apps = JSONArray()
                    val seenPackages = mutableSetOf<String>()
                    for (resolveInfo in activities) {
                        val pkgName = resolveInfo.activityInfo.packageName
                        if (!seenPackages.contains(pkgName)) {
                            seenPackages.add(pkgName)
                            apps.put(JSONObject().apply {
                                put("app_name", resolveInfo.loadLabel(pm).toString())
                                put("package_name", pkgName)
                            })
                        }
                    }
                    return JSONObject().apply { put("installed_apps", apps) }
                }
                "set_alarm" -> {
                    val hour = args.optInt("hour", 0)
                    val minute = args.optInt("minute", 0)
                    val message = args.optString("message", "")
                    
                    val details = "Time: $hour:$minute\nMessage: $message"
                    val approved = AppState.waitForPermission(context, tabId, "Set Alarm", details, packageName = "com.google.android.deskclock", intentIdentifier = "com.google.android.deskclock (android.intent.action.SET_ALARM)")
                    if (!approved) {
                        return JSONObject().apply { put("error", "User denied permission") }
                    }
                    
                    try {
                        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                            if (message.isNotEmpty()) {
                                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                            }
                            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        return JSONObject().apply { put("status", "Alarm set for $hour:$minute") }
                    } catch (e: Exception) {
                        return JSONObject().apply { put("error", e.message ?: "Failed to set alarm") }
                    }
                }
                "launch_intent" -> {
                    val packageName = args.optString("package_name")
                    val action = args.optString("action", "")
                    val dataUri = args.optString("data_uri")
                    val mimeType = args.optString("mime_type")
                    val extraStreamUri = args.optString("extra_stream_uri")
                    val extraText = args.optString("extra_text")
                    
                    val resolvedAction = if (action.isNotEmpty()) action else if (extraStreamUri.isNotEmpty() || extraText.isNotEmpty()) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_MAIN
                    val intentIdentifier = if (packageName.isNotEmpty() && resolvedAction.isNotEmpty()) "$packageName ($resolvedAction)" else (packageName.ifEmpty { resolvedAction })
                    
                    val details = "Package: $packageName" + (if (action.isNotEmpty()) "\nAction: $action" else "") + (if (dataUri.isNotEmpty()) "\nData: $dataUri" else "") + (if (extraStreamUri.isNotEmpty()) "\nStream: $extraStreamUri" else "") + (if (extraText.isNotEmpty()) "\nText: $extraText" else "")
                    val approved = AppState.waitForPermission(context, tabId, "Launch App Intent", details, packageName = packageName.ifEmpty { null }, intentIdentifier = intentIdentifier.ifEmpty { null })
                    if (!approved) {
                        return JSONObject().apply { put("error", "User denied permission") }
                    }
                    
                    try {
                        val resolvedAction = if (action.isNotEmpty()) action else if (extraStreamUri.isNotEmpty() || extraText.isNotEmpty()) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_MAIN
                        val intent = if (packageName.isNotEmpty() && resolvedAction == android.content.Intent.ACTION_MAIN && extraStreamUri.isEmpty() && extraText.isEmpty()) {
                            context.packageManager.getLaunchIntentForPackage(packageName)
                        } else {
                            android.content.Intent(resolvedAction).apply {
                                if (packageName.isNotEmpty()) setPackage(packageName)
                                if (dataUri.isNotEmpty()) data = android.net.Uri.parse(dataUri)
                                if (mimeType.isNotEmpty()) type = mimeType
                                if (extraStreamUri.isNotEmpty()) {
                                    val file = java.io.File(extraStreamUri.replace("file://", ""))
                                    val uri = if (file.exists()) {
                                        androidx.core.content.FileProvider.getUriForFile(context, "io.github.nicolasraoul.moag.fileprovider", file)
                                    } else {
                                        android.net.Uri.parse(extraStreamUri)
                                    }
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    clipData = android.content.ClipData.newRawUri("", uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    if (mimeType.isEmpty()) type = "image/*"
                                }
                                if (extraText.isNotEmpty()) {
                                    putExtra(android.content.Intent.EXTRA_TEXT, extraText)
                                }
                            }
                        }
                        
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            return JSONObject().apply { put("success", true) }
                        } else {
                            return JSONObject().apply { put("error", "Could not create or find intent") }
                        }
                    } catch (e: Exception) {
                        return JSONObject().apply { put("error", "Failed to launch intent: ${e.message}") }
                    }
                }
                "schedule_agent_task" -> {
                    val delayMinutes = args.optInt("delay_minutes", 60)
                    val prompt = args.optString("prompt")
                    
                    val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    val intent = android.content.Intent(context, AlarmReceiver::class.java).apply {
                        putExtra("tab_id", tabId)
                        putExtra("prompt", prompt)
                    }
                    
                    val pi = android.app.PendingIntent.getBroadcast(
                        context, 
                        (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), 
                        intent, 
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    val triggerTime = System.currentTimeMillis() + (delayMinutes * 60 * 1000L)
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            if (am.canScheduleExactAlarms()) {
                                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pi)
                            } else {
                                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pi)
                            }
                        } else {
                            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pi)
                        }
                        return JSONObject().apply { put("success", "Task scheduled to run in $delayMinutes minutes") }
                    } catch (e: Exception) {
                        return JSONObject().apply { put("error", "Failed to schedule task: ${e.message}") }
                    }
                }
                "generate_image" -> {
                    val prompt = args.optString("prompt")
                    val filename = args.optString("filename")
                    
                    val details = "Prompt: $prompt\nFilename: $filename"
                    val approved = AppState.waitForPermission(context, tabId, "Generate Image", details)
                    if (!approved) {
                        return JSONObject().apply { put("error", "User denied generation") }
                    }
                    
                    val payload = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply { put("text", prompt) })
                                })
                            })
                        })
                    }
                    
                    val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent?key=$apiKey"
                    val client = OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .header("User-Agent", "Moag 1.0")
                        .url(urlStr)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                        
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseBodyStr = response.body?.string() ?: ""
                        val responseJson = JSONObject(responseBodyStr)
                        val candidates = responseJson.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)
                                    val inlineData = part.optJSONObject("inlineData")
                                    if (inlineData != null) {
                                        val base64Data = inlineData.optString("data")
                                        if (base64Data.isNotEmpty()) {
                                            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                            val file = if (filename.startsWith("/")) File(filename) else {
                                                if (filename.contains("Download", ignoreCase = true)) {
                                                    File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), filename.substringAfterLast("/"))
                                                } else {
                                                    File(context.filesDir, filename)
                                                }
                                            }
                                            file.parentFile?.mkdirs()
                                            file.writeBytes(bytes)
                                            try {
                                                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                                            } catch (e: Exception) {}
                                            return JSONObject().apply { put("file_path", file.absolutePath) }
                                        }
                                    }
                                }
                            }
                        }
                        return JSONObject().apply { put("error", "Failed to parse image from response: $responseJson") }
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        return JSONObject().apply { put("error", "API request failed with code ${response.code}: $errorBody") }
                    }
                }
                else -> {
                    return JSONObject().apply { put("error", "Unknown tool") }
                }
            }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", e.message) }
        }
    }
}
