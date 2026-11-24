package ink.zanao.campusreply

import android.content.ContentUris
import android.os.Environment
import android.provider.MediaStore
import com.comingx.zanao.utils.JniUtils
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PrivilegeLevel
import java.io.File
import java.net.URLEncoder
import java.util.TreeMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Real campus-market App API client.
 *
 * Session: token/device/alias are imported automatically from the VPN capture
 * output (Downloads/Zanao/zanao-token.json) — no manual pasting.
 * Signing: X-Sc-Ah is produced in-process by the market app's own libAesNew.so
 * (bundled in plugin assets) via JniUtils.makeSign.
 * Responses flagged with x-sc-encrypt or __encrypt__ are decrypted with
 * JniUtils.unhashAppResult.
 */
internal object AppMarketProvider : CampusMarketRequests {
    private val json = Json { ignoreUnknownKeys = true }
    // The App itself speaks plain HTTP on port 80; 443 on this origin does not accept
    // connections from the device (verified: 80 open, 443 times out).
    private const val BASE = "http://api.app.zanao.com"
    private const val TOKEN_KEY = "token"
    private const val DEVICE_KEY = "device"
    private const val ALIAS_KEY = "alias"
    private const val SOURCE_KEY = "session_source"
    private const val CAPTURE_REL = "Download/Zanao/zanao-token.json"
    private const val CAPTURE_ABS = "/sdcard/Download/Zanao/zanao-token.json"
    private const val VPN_PREFS = "/data/data/io.github.whywood.vpnspike/shared_prefs/zanao.xml"

    data class Session(
        val token: String,
        val device: String,
        val alias: String,
        val source: String,
    )

    private fun parseCapture(text: String): Session? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val token = obj["token"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val device = obj["device"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val alias = obj["alias"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifBlank { "sysu" }
            if (token.isBlank()) null else Session(token, device, alias, "capture_file")
        }.getOrNull()
        // Fallback: VPN capture app SharedPreferences XML
        return runCatching {
            fun field(name: String): String =
                Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                    .find(trimmed)?.groupValues?.get(1)?.trim().orEmpty()
            val token = field("token")
            if (token.isBlank()) null else Session(
                token, field("device"), field("alias").ifBlank { "sysu" }, "vpn_prefs",
            )
        }.getOrNull()
    }

    private suspend fun importSession(host: PluginHost): Session? {
        // 1) copy placed in the plugin data dir (operator bridge / previous import)
        runCatching { parseCapture(File(host.dataDir, "zanao-token.json").readText()) }
            .getOrNull()?.let { return it }
        // 2) MediaStore query for the capture output
        runCatching {
            val resolver = host.context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
            val args = arrayOf("zanao-token.json")
            resolver.query(collection, projection, selection, args,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val uri = ContentUris.withAppendedId(collection, id)
                resolver.openInputStream(uri)?.bufferedReader()?.use { reader -> reader.readText() }
            }
        }.getOrNull()?.let { parseCapture(it) }?.let { return it }
        // 3) direct file read (works when storage permission is granted)
        runCatching { parseCapture(File(CAPTURE_ABS).readText()) }.getOrNull()?.let { return it }
        // 4) root shell
        runCatching {
            val result = host.privilege().exec("cat $CAPTURE_ABS", PrivilegeLevel.ROOT, 8_000L)
            if (result.exitCode == 0) parseCapture(result.stdout) else null
        }.getOrNull()?.let { return it }
        // 5) root shell reading the capture app's SharedPreferences
        runCatching {
            val result = host.privilege().exec("cat $VPN_PREFS", PrivilegeLevel.ROOT, 8_000L)
            if (result.exitCode == 0) parseCapture(result.stdout) else null
        }.getOrNull()?.let { return it }
        return null
    }

    suspend fun session(host: PluginHost): Session {
        val cached = host.secrets().get(TOKEN_KEY).orEmpty().trim()
        if (cached.isNotBlank()) {
            return Session(
                cached,
                host.secrets().get(DEVICE_KEY).orEmpty().trim(),
                host.secrets().get(ALIAS_KEY).orEmpty().trim().ifBlank { "sysu" },
                host.secrets().get(SOURCE_KEY).orEmpty().ifBlank { "secrets" },
            )
        }
        val imported = importSession(host)
            ?: error("未找到集市会话：请打开校园集市 App 触发一次请求（VPN 捕捉器运行中），或检查 $CAPTURE_ABS")
        host.secrets().put(TOKEN_KEY, imported.token)
        host.secrets().put(DEVICE_KEY, imported.device)
        host.secrets().put(ALIAS_KEY, imported.alias)
        host.secrets().put(SOURCE_KEY, imported.source)
        return imported
    }

    suspend fun sessionInfo(host: PluginHost): Map<String, Any?> = try {
        val s = session(host)
        mapOf(
            "ok" to true,
            "token_present" to s.token.isNotBlank(),
            "device_present" to s.device.isNotBlank(),
            "alias" to s.alias,
            "session_source" to s.source,
        )
    } catch (e: Exception) {
        mapOf("ok" to false, "error" to (e.message ?: e.toString()))
    }

    override suspend fun request(
        host: PluginHost,
        path: String,
        body: Map<String, Any?>,
        method: String,
    ): JsonElement {
        val s = session(host)
        ZanaoNative.ensure(host)
        val td = (System.currentTimeMillis() / 1000L).toString()
        val nd = buildString(20) { repeat(20) { append(('0'..'9').random()) } }
        val fields = TreeMap<String, Any?>()
        fields["timestamp__"] = td
        fields["randnum__"] = nd
        body.forEach { (key, value) -> if (value != null) fields[key] = value.toString() }
        val ah = JniUtils.makeSign(fields)
            ?: error("makeSign 返回空（native 加载状态：${ZanaoNative.signState()}）")

        val headers = mutableMapOf(
            "X-Sc-Alias" to s.alias,
            "X-Sc-Token" to s.token,
            "X-Sc-Client" to "app",
            "X-Sc-Device" to s.device,
            "X-Sc-Platform" to "Android",
            "X-Sc-Hb-V" to "210",
            "X-Sc-Nt-V" to "310",
            "X-Sc-Version" to "3.1.0",
            "X-Sc-Nd" to nd,
            "X-Sc-Td" to td,
            "X-Sc-Ah" to ah,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json",
            "User-Agent" to "okhttp/4.10.0",
        )

        var url = BASE + path
        var payload: String? = null
        if (method.equals("GET", ignoreCase = true)) {
            if (body.isNotEmpty()) {
                val query = body.filterValues { it != null }.entries.joinToString("&") { (key, value) ->
                    URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value.toString(), "UTF-8")
                }
                url += "?" + query
            }
        } else {
            headers["Content-Type"] = "application/json; charset=utf-8"
            payload = buildJsonObject {
                body.forEach { (key, value) ->
                    when (value) {
                        null -> Unit
                        is Number -> put(key, JsonPrimitive(value))
                        is Boolean -> put(key, JsonPrimitive(value))
                        else -> put(key, JsonPrimitive(value.toString()))
                    }
                }
            }.toString()
        }

        val response = host.network().request(method.uppercase(), url, headers, payload)
        if (response.statusCode !in 200..299) {
            error("集市接口 HTTP ${response.statusCode}：${response.body.take(240)}")
        }
        var text = response.body
        val encryptHeader = response.headers.entries
            .firstOrNull { it.key.equals("x-sc-encrypt", ignoreCase = true) }?.value
        if (encryptHeader == "1") {
            text = JniUtils.unhashAppResult(text)
                ?: error("unhashAppResult 返回空（native 加载状态：${ZanaoNative.signState()}）")
        } else if (text.contains("\"__encrypt__\"")) {
            val outer = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val data = outer?.get("__data__")?.jsonPrimitive?.contentOrNull
            if (data != null) {
                text = JniUtils.unhashAppResult(data)
                    ?: error("unhashAppResult(__data__) 返回空")
            }
        }
        return runCatching { json.parseToJsonElement(text) }.getOrElse {
            error("集市接口返回非 JSON：$path HTTP ${response.statusCode} ${text.take(160)}")
        }
    }
}
