package com.armoney.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class BankHttp(private val configuration: Configuration, private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    suspend fun send(path: String, method: String = "GET", body: String? = null, token: String? = null, key: String? = null, form: Boolean = false): String = withContext(Dispatchers.IO) {
        configuration.usable(); require(path.startsWith("/") && !path.startsWith("//"))
        val connection = open(URL(configuration.origin + path))
        val assistant = path == "/v1/assistant/messages"
        val deadline = if (assistant) java.util.concurrent.Executors.newSingleThreadScheduledExecutor() else null
        val timeout = deadline?.schedule({ connection.disconnect() }, 40, java.util.concurrent.TimeUnit.SECONDS)
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = if (assistant) 40_000 else 15_000
            connection.requestMethod = method; connection.setRequestProperty("Accept", "application/json")
            token?.let { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))); connection.setRequestProperty("Authorization", "Bearer $it") }
            key?.let { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))); connection.setRequestProperty("Idempotency-Key", it) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8); require(bytes.size <= if (assistant) 4096 else 12_288)
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use {
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer); if (count < 0) break
                    require(out.size() + count <= if (assistant) 32_768 else 1_048_576) { "Response too large" }; out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: byteArrayOf()
            require(bytes.size <= 1_048_576) { "Response too large" }
            val result = if (assistant) Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString() else bytes.toString(Charsets.UTF_8)
            if (status !in 200..299) {
                // Only an exact endpoint error envelope can prove pre-acceptance refusal.
                // Duplicate/extra fields, HTML proxy responses and ambiguous JSON remain uncertain.
                val code = strictErrorCode(connection.contentType, result, bytes.size)
                throw HttpFailure(status, code)
            }
            if (assistant) require(status == 200 && connection.contentType?.substringBefore(';')?.trim()?.lowercase() == "application/json") { "Unexpected assistant response" }
            result
        } finally { timeout?.cancel(false); deadline?.shutdownNow(); connection.disconnect() }
    }
}

internal fun strictErrorCode(contentType: String?, body: String, byteCount: Int): String? {
    if (contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" || byteCount > 4096) return null
    return Regex("\\s*\\{\\s*\"error\"\\s*:\\s*\"([a-z_]+)\"\\s*}\\s*").matchEntire(body)?.groupValues?.get(1)
}

