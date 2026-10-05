package io.github.nicolasraoul.moag
import android.util.Log
object NetworkTest {
    fun test(urlStr: String, payload: String) {
        Thread {
            try {
                Log.d("MOAG_NET", "Testing: $urlStr")
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                Log.d("MOAG_NET", "Code for $urlStr: $code")
                val response = conn.errorStream?.bufferedReader()?.readText() ?: conn.inputStream.bufferedReader().readText()
                Log.d("MOAG_NET", "Response: $response")
            } catch (e: Throwable) {
                Log.e("MOAG_NET", "Error for $urlStr", e)
            }
        }.start()
    }
}
