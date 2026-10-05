package io.github.nicolasraoul.moag

import android.content.Context
import android.os.Environment
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Skill(
    val id: String, 
    val condition: String, 
    val advice: String,
    val sourcePath: String? = null
)

object SkillManager {

    fun getSkillsDir(): File {
        val ext = Environment.getExternalStorageDirectory()
        val sdcardSkills = File(ext, "skills")
        if (sdcardSkills.exists()) return sdcardSkills
        val directPath = File("/sdcard/skills")
        if (directPath.exists()) return directPath
        return sdcardSkills
    }

    private fun parseSkillFile(file: File): Skill? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            val content = file.readText()
            val lines = content.lines()
            
            var name = file.parentFile?.name?.takeIf { it != "skills" } ?: file.nameWithoutExtension
            var description = ""
            var body = content
            
            if (lines.isNotEmpty() && lines[0].trim() == "---") {
                val secondSeparatorIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (secondSeparatorIndex != -1) {
                    val frontmatterLines = lines.subList(1, secondSeparatorIndex + 1)
                    body = lines.drop(secondSeparatorIndex + 2).joinToString("\n").trim()
                    
                    for (line in frontmatterLines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("name:", ignoreCase = true)) {
                            name = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                        } else if (trimmed.startsWith("description:", ignoreCase = true)) {
                            description = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                        }
                    }
                }
            }
            
            if (description.isBlank()) {
                val nonHeaderLine = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") && it != "---" }
                description = nonHeaderLine?.trim() ?: name
            }
            
            val advice = if (body.isNotBlank()) body else description
            Skill(id = name, condition = description, advice = advice, sourcePath = file.absolutePath)
        } catch (e: Exception) {
            Log.e("MOAG", "Error parsing skill from ${file.absolutePath}", e)
            null
        }
    }

    fun ensureDefaultSkills() {
        try {
            val dir = getSkillsDir()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val myPicDir = File(dir, "my_pic")
            val myPicSkill = File(myPicDir, "SKILL.md")
            if (!myPicSkill.exists()) {
                myPicDir.mkdirs()
                myPicSkill.writeText(
                    "---\n" +
                    "name: my_pic\n" +
                    "description: The user talks about 'my pic' or 'my photo' or 'a picture I took'\n" +
                    "---\n" +
                    "When the user says 'my pic' or 'my photo', it means a picture they took with their camera. You MUST use the read_media_storage tool with folder_filter='DCIM/Camera'. Do NOT use generated images or downloaded files.\n"
                )
            }

            val recurringDir = File(dir, "recurring_tasks")
            val recurringSkill = File(recurringDir, "SKILL.md")
            if (!recurringSkill.exists()) {
                recurringDir.mkdirs()
                recurringSkill.writeText(
                    "---\n" +
                    "name: recurring_tasks\n" +
                    "description: The user asks to do something repeatedly, every X minutes, or periodically\n" +
                    "---\n" +
                    "To do something repeatedly or periodically, you MUST call the `schedule_agent_task` tool to schedule the next iteration of the task. Do NOT just say you scheduled it in text. You must explicitly execute the `schedule_agent_task` tool with `delay_minutes` and the `prompt` for the next iteration. Make sure you also do the task for the current iteration. To keep track of state (like how many pictures have been taken), include the current iteration number in the `prompt` for the next iteration (e.g. 'Iteration 2 of 10'). Do NOT use the `write_text_file` tool to store state between iterations.\n"
                )
            }
        } catch (e: Exception) {
            Log.e("MOAG", "Could not create default skills in sdcard", e)
        }
    }

    fun getSdcardSkillCount(): Int {
        ensureDefaultSkills()
        return getSkillsFromSdcard().size
    }

    fun getSkillsFromSdcard(): List<Skill> {
        val dir = getSkillsDir()
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val skills = mutableListOf<Skill>()
        val files = dir.listFiles() ?: return emptyList()

        for (item in files) {
            if (item.isDirectory) {
                val skillMd = item.listFiles()?.firstOrNull { 
                    it.isFile && it.name.equals("SKILL.md", ignoreCase = true) 
                }
                if (skillMd != null) {
                    parseSkillFile(skillMd)?.let { skills.add(it) }
                }
            } else if (item.isFile && item.extension.equals("md", ignoreCase = true)) {
                parseSkillFile(item)?.let { skills.add(it) }
            }
        }
        return skills
    }

    fun getSkills(context: Context): List<Skill> {
        ensureDefaultSkills()
        val sdcardSkills = getSkillsFromSdcard()
        if (sdcardSkills.isNotEmpty()) {
            return sdcardSkills
        }

        // Fallback to internal app storage skills if /sdcard/skills is inaccessible
        val internalDir = File(context.filesDir, "skills")
        if (internalDir.exists() && internalDir.isDirectory) {
            val internalSkills = mutableListOf<Skill>()
            val files = internalDir.listFiles() ?: emptyArray()
            for (item in files) {
                if (item.isDirectory) {
                    val skillMd = item.listFiles()?.firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                    if (skillMd != null) parseSkillFile(skillMd)?.let { internalSkills.add(it) }
                } else if (item.isFile && item.extension.equals("md", ignoreCase = true)) {
                    parseSkillFile(item)?.let { internalSkills.add(it) }
                }
            }
            if (internalSkills.isNotEmpty()) return internalSkills
        }

        return listOf(
            Skill(
                "my_pic", 
                "The user talks about 'my pic' or 'my photo' or 'a picture I took'", 
                "When the user says 'my pic' or 'my photo', it means a picture they took with their camera. You MUST use the read_media_storage tool with folder_filter='DCIM/Camera'. Do NOT use generated images or downloaded files."
            ),
            Skill(
                "recurring_tasks",
                "The user asks to do something repeatedly, every X minutes, or periodically",
                "To do something repeatedly or periodically, you MUST call the `schedule_agent_task` tool to schedule the next iteration of the task. Do NOT just say you scheduled it in text. You must explicitly execute the `schedule_agent_task` tool with `delay_minutes` and the `prompt` for the next iteration. Make sure you also do the task for the current iteration. To keep track of state (like how many pictures have been taken), include the current iteration number in the `prompt` for the next iteration (e.g. 'Iteration 2 of 10'). Do NOT use the `write_text_file` tool to store state between iterations."
            )
        )
    }

    suspend fun analyzeSkillsForPrompt(apiKey: String, prompt: String, skills: List<Skill>): List<Skill> {
        if (skills.isEmpty()) return emptyList()
        
        val skillsJson = JSONArray()
        for (skill in skills) {
            val obj = JSONObject()
            obj.put("id", skill.id)
            obj.put("condition", skill.condition)
            skillsJson.put(obj)
        }
        
        val systemInstruction = "You are an intelligent router. Given a user prompt and a list of skills with conditions, determine which skills should be activated. Return ONLY a JSON array of skill IDs. Do not return any other text or markdown."
        val userPrompt = "User Prompt: $prompt\n\nAvailable Skills: $skillsJson"
        
        val requestBodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("role", "system")
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemInstruction) }) })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", userPrompt) }) })
                })
            })
        }
        
        try {
            val client = OkHttpClient.Builder().build()
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
                
            val response = client.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""
            Log.d("MOAG", "SkillManager HTTP ${response.code}: $responseBodyStr")
            if (response.isSuccessful) {
                val responseJson = JSONObject(responseBodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val message = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = message?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text").trim().replace("```json", "").replace("```", "").trim()
                        val selectedIds = JSONArray(text)
                        val selectedList = mutableListOf<String>()
                        for (i in 0 until selectedIds.length()) {
                            selectedList.add(selectedIds.getString(i))
                        }
                        return skills.filter { it.id in selectedList }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MOAG", "Skill analysis failed", e)
        }
        return emptyList()
    }
}
