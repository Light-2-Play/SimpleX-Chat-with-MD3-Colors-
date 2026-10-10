package chat.simplex.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

object SingBoxService {

  private const val TAG = "SingBoxService"
  private const val LOCAL_PORT = 20808
  private var process: Process? = null

  private val SUBSCRIPTION_URLS = listOf(
    "https://cdn.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/singbox.json",
    "https://cdn.jsdelivr.net/gh/awesome-vpn/awesome-vpn@master/sing-box.json",
    "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/singbox.json"
  )

  private const val PREFS_NAME = "singbox_preferences"
  private const val KEY_SERVER_LIMIT = "server_limit"
  private const val KEY_CUSTOM_MODE = "custom_mode"
  private const val KEY_CUSTOM_KEY = "custom_key"
  const val DEFAULT_SERVER_LIMIT = 25

  fun getServerLimit(context: Context): Int {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getInt(KEY_SERVER_LIMIT, DEFAULT_SERVER_LIMIT)
  }

  fun setServerLimit(context: Context, limit: Int) {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putInt(KEY_SERVER_LIMIT, limit).apply()
  }

  fun isCustomMode(context: Context): Boolean {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getBoolean(KEY_CUSTOM_MODE, false)
  }

  fun setCustomMode(context: Context, enabled: Boolean) {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putBoolean(KEY_CUSTOM_MODE, enabled).apply()
  }

  fun getCustomKey(context: Context): String {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getString(KEY_CUSTOM_KEY, "") ?: ""
  }

  fun setCustomKey(context: Context, key: String) {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putString(KEY_CUSTOM_KEY, key.trim()).apply()
  }

  @Volatile
  var isRunning = false
    private set

  fun toggle(context: Context) {
    if (isRunning) {
      stop()
      showToast(context, "Proxy disconnected")
    } else {
      start(context)
    }
  }

  fun restart(context: Context) {
    stop()
    thread(name = "SingBoxRestarter") {
      Thread.sleep(600)
      start(context)
    }
  }

  fun start(context: Context) {
    if (isRunning) return

    thread(name = "SingBoxStarter") {
      try {
        val configFile = prepareConfig(context)
        val binaryFile = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")

        if (!binaryFile.exists()) {
          showToast(context, "Error: libsingbox.so not found")
          return@thread
        }

        try {
          binaryFile.setExecutable(true)
        } catch (_: Exception) {}

        val pb = ProcessBuilder(
          binaryFile.absolutePath,
          "run",
          "-c",
          configFile.absolutePath
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        process = proc

        var lastLog = ""
        thread(name = "SingBoxLogReader") {
          try {
            proc.inputStream.bufferedReader().useLines { lines ->
              lines.forEach { line ->
                val clean = line.replace(Regex("\u001B\\[[;\\d]*m"), "").trim()
                Log.d(TAG, clean)
                if (clean.contains("FATAL", ignoreCase = true) ||
                  clean.contains("ERROR", ignoreCase = true) ||
                  clean.contains("panic", ignoreCase = true)
                ) {
                  lastLog = clean
                } else if (lastLog.isEmpty()) {
                  lastLog = clean
                }
              }
            }
          } catch (_: Exception) {}
        }

        var portOpen = false
        for (i in 0 until 30) {
          Thread.sleep(300)
          try {
            Socket().use { s ->
              s.connect(InetSocketAddress("127.0.0.1", LOCAL_PORT), 300)
              portOpen = true
            }
            break
          } catch (_: Exception) {
            if (!proc.isAlive) break
          }
        }

        if (portOpen) {
          isRunning = true
          showToast(context, "Proxy connected ($LOCAL_PORT)")
        } else {
          val errorDetail = if (!proc.isAlive) {
            val exitCode = proc.exitValue()
            val msg = if (lastLog.isNotBlank()) lastLog else "code $exitCode"
            "crashed (exit code $exitCode): $msg"
          } else {
            "port $LOCAL_PORT timeout"
          }
          stop()
          showToast(context, "Error: $errorDetail")
        }
      } catch (e: Exception) {
        showToast(context, "Failure: ${e.message}")
      }
    }
  }

  fun stop() {
    try {
      process?.destroy()
      process = null
    } catch (_: Exception) {}
    isRunning = false
  }

  private fun prepareConfig(context: Context): File {
    val configFile = File(context.filesDir, "singbox_active.json")
    val root = JSONObject()

    root.put("log", JSONObject().apply {
      put("level", "warn")
    })

    val socksInbound = JSONObject().apply {
      put("type", "socks")
      put("tag", "socks-in")
      put("listen", "127.0.0.1")
      put("listen_port", LOCAL_PORT)
    }
    root.put("inbounds", JSONArray().apply { put(socksInbound) })

    // Системный DNS устройства - не блокируется оператором
    val dns = JSONObject().apply {
      val servers = JSONArray().apply {
        put(JSONObject().apply {
          put("tag", "local-dns")
          put("address", "local")
          put("detour", "direct")
        })
      }
      put("servers", servers)
      put("strategy", "prefer_ipv4")
    }
    root.put("dns", dns)

    val cleanOutbounds = JSONArray()
    var targetTag = "direct"

    if (isCustomMode(context) && getCustomKey(context).isNotBlank()) {
      try {
        val customOutbounds = resolveCustomOutbounds(getCustomKey(context))
        if (customOutbounds.isNotEmpty()) {
          targetTag = registerOutboundsWithUrlTest(cleanOutbounds, customOutbounds, context)
        } else {
          throw IllegalArgumentException("No valid VLESS servers found in custom configuration")
        }
      } catch (e: Exception) {
        throw IllegalArgumentException("Failed: ${e.message ?: "Invalid configuration"}")
      }
    } else {
      var rawJson: String? = null
      for (url in SUBSCRIPTION_URLS) {
        try {
          val downloaded = downloadUrl(url)
          if (downloaded.isNotBlank()) {
            rawJson = downloaded
            break
          }
        } catch (e: Exception) {
          Log.w(TAG, "Failed to download subscription from $url: ${e.message}")
        }
      }

      if (rawJson.isNullOrBlank()) {
        if (configFile.exists() && configFile.length() > 50) return configFile
        throw IllegalStateException("Failed to download subscription")
      }

      val sourceRoot = JSONObject(rawJson)
      val sourceOutbounds = sourceRoot.optJSONArray("outbounds") ?: JSONArray()
      val vlessOutbounds = mutableListOf<JSONObject>()
      val otherOutbounds = mutableListOf<JSONObject>()

      for (i in 0 until sourceOutbounds.length()) {
        val ob = sourceOutbounds.getJSONObject(i)
        val type = ob.optString("type")
        if (type == "direct" || type == "block" || type == "dns" || type == "urltest" || type == "selector") continue
        if (type.equals("vless", ignoreCase = true)) {
          vlessOutbounds.add(ob)
        } else {
          otherOutbounds.add(ob)
        }
      }

      // Приоритет отдаем VLESS-серверам
      val candidateOutbounds = if (vlessOutbounds.isNotEmpty()) vlessOutbounds else otherOutbounds
      targetTag = registerOutboundsWithUrlTest(cleanOutbounds, candidateOutbounds, context)
    }

    cleanOutbounds.put(JSONObject().apply {
      put("type", "direct")
      put("tag", "direct")
    })

    root.put("outbounds", cleanOutbounds)

    val route = JSONObject().apply {
      val rules = JSONArray().apply {
        put(JSONObject().apply {
          put("inbound", JSONArray().apply { put("socks-in") })
          put("outbound", targetTag)
        })
      }
      put("rules", rules)
      put("final", "direct")
    }
    root.put("route", route)

    configFile.writeText(root.toString(2))
    return configFile
  }

  // Сборка группы urltest с гарантированно уникальными тегами для исключения падений
  private fun registerOutboundsWithUrlTest(
    cleanOutbounds: JSONArray,
    rawOutbounds: List<JSONObject>,
    context: Context
  ): String {
    val limit = getServerLimit(context)
    val selected = if (limit in 1 until rawOutbounds.size) {
      rawOutbounds.shuffled().take(limit)
    } else {
      rawOutbounds
    }

    val usedTags = mutableSetOf<String>()
    val proxyTags = JSONArray()

    for ((index, ob) in selected.withIndex()) {
      var tag = ob.optString("tag").ifBlank { "proxy" }
      if (usedTags.contains(tag) || tag == "direct" || tag == "auto") {
        tag = "${tag}_$index"
      }
      usedTags.add(tag)
      ob.put("tag", tag)

      cleanOutbounds.put(ob)
      proxyTags.put(tag)
    }

    return if (proxyTags.length() > 1) {
      val urlTestGroup = JSONObject().apply {
        put("type", "urltest")
        put("tag", "auto")
        put("outbounds", proxyTags)
        put("url", "https://www.gstatic.com/generate_204")
        put("interval", "1m")
        put("tolerance", 50)
      }
      cleanOutbounds.put(urlTestGroup)
      "auto"
    } else if (proxyTags.length() == 1) {
      proxyTags.getString(0)
    } else {
      "direct"
    }
  }

  private fun resolveCustomOutbounds(rawInput: String): List<JSONObject> {
    var input = rawInput.trim().removeSurrounding("\"").removeSurrounding("'")

    try {
      val file = File(input)
      if (file.exists() && file.isFile) {
        input = file.readText().trim()
      }
    } catch (_: Exception) {}

    val content = if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
      try {
        val downloaded = downloadUrl(input)
        val decoded = try {
          String(Base64.decode(downloaded.trim(), Base64.DEFAULT))
        } catch (_: Exception) {
          downloaded
        }
        decoded.trim()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to download custom link: ${e.message}")
        input
      }
    } else {
      input
    }

    val results = mutableListOf<JSONObject>()

    // Если передан JSON со списком outbounds
    if (content.startsWith("{") && content.contains("\"outbounds\"")) {
      try {
        val json = JSONObject(content)
        val outbounds = json.optJSONArray("outbounds") ?: JSONArray()
        for (i in 0 until outbounds.length()) {
          val ob = outbounds.getJSONObject(i)
          val type = ob.optString("type")
          if (type == "direct" || type == "block" || type == "dns" || type == "urltest" || type == "selector") continue
          results.add(ob)
        }
        if (results.isNotEmpty()) return results
      } catch (_: Exception) {}
    }

    // Парсим все строки vless://
    for (line in content.lines()) {
      val trimmed = line.trim()
      if (trimmed.startsWith("vless://", ignoreCase = true)) {
        parseVlessUri(trimmed)?.let { results.add(it) }
      }
    }

    return results
  }

  private fun parseVlessUri(vlessUri: String): JSONObject? {
    return try {
      val uri = Uri.parse(vlessUri.trim())
      if (uri.scheme != "vless") return null

      val uuid = uri.userInfo ?: return null
      val server = uri.host ?: return null
      val port = if (uri.port != -1) uri.port else 443

      val security = uri.getQueryParameter("security") ?: "none"
      val flow = uri.getQueryParameter("flow")
      val sni = uri.getQueryParameter("sni") ?: server
      val pbk = uri.getQueryParameter("pbk").orEmpty()
      val sid = uri.getQueryParameter("sid").orEmpty()
      val fp = uri.getQueryParameter("fp") ?: "chrome"
      val tag = uri.fragment?.takeIf { it.isNotBlank() } ?: "vless-proxy"

      JSONObject().apply {
        put("type", "vless")
        put("tag", tag)
        put("server", server)
        put("server_port", port)
        put("uuid", uuid)
        if (!flow.isNullOrBlank()) put("flow", flow)

        if (security.equals("reality", ignoreCase = true)) {
          put("tls", JSONObject().apply {
            put("enabled", true)
            put("server_name", sni)
            put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            put("reality", JSONObject().apply {
              put("enabled", true)
              if (pbk.isNotBlank()) put("public_key", pbk)
              if (sid.isNotBlank()) put("short_id", sid)
            })
          })
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing VLESS: ${e.message}")
      null
    }
  }

  private fun downloadUrl(urlString: String): String {
    var curUrl = urlString
    for (redirect in 0 until 5) {
      val conn = (URL(curUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "v2rayNG/1.8.5")
      }
      val code = conn.responseCode
      if (code == HttpURLConnection.HTTP_MOVED_PERM ||
        code == HttpURLConnection.HTTP_MOVED_TEMP ||
        code == 307 || code == 308
      ) {
        val loc = conn.getHeaderField("Location") ?: break
        curUrl = loc
        continue
      }
      if (code in 200..299) {
        return conn.inputStream.bufferedReader().use { it.readText() }
      }
      break
    }
    throw IllegalStateException("Network response error: $urlString")
  }

  private fun showToast(context: Context, msg: String) {
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
  }
}
