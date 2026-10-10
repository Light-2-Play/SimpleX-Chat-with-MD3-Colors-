package chat.simplex.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import chat.simplex.common.views.chatlist.ByeDpiBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

object SingBoxService {

  private const val TAG = "SingBoxService"
  private const val LOCAL_PORT = 20808
  private var process: Process? = null

  private val SUBSCRIPTION_URLS = listOf(
    "https://cdn.jsdelivr.net/gh/awesome-vpn/awesome-vpn@master/sing-box.json",
    "https://cdn.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/output/singbox.json",
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
          Handler(Looper.getMainLooper()).post {
            ByeDpiBridge.isRunning.value = true
          }

          var checkSuccess = false
          var checkError = ""
          try {
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LOCAL_PORT))
            val testConn = (URL("http://connectivitycheck.gstatic.com/generate_204").openConnection(socksProxy) as HttpURLConnection).apply {
              connectTimeout = 4000
              readTimeout = 4000
              instanceFollowRedirects = true
            }
            val code = testConn.responseCode
            if (code == 204 || code == 200) {
              checkSuccess = true
            } else {
              checkError = "HTTP $code"
            }
          } catch (e: Exception) {
            checkError = e.message ?: "Connection error"
          }

          if (checkSuccess) {
            showToast(context, "Proxy connected & verified ($LOCAL_PORT)")
          } else {
            val logInfo = if (lastLog.isNotBlank()) " | Log: $lastLog" else ""
            showToast(context, "Proxy open, but test failed: $checkError$logInfo")
          }
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
        stop()
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
    Handler(Looper.getMainLooper()).post {
      ByeDpiBridge.isRunning.value = false
    }
  }

  private fun prepareConfig(context: Context): File {
    val configFile = File(context.filesDir, "singbox_active.json")
    val root = JSONObject()

    root.put("log", JSONObject().apply {
      put("level", "info")
      put("timestamp", true)
    })

    val socksInbound = JSONObject().apply {
      put("type", "socks")
      put("tag", "socks-in")
      put("listen", "127.0.0.1")
      put("listen_port", LOCAL_PORT)
    }
    root.put("inbounds", JSONArray().apply { put(socksInbound) })

    val dns = JSONObject().apply {
      val servers = JSONArray().apply {
        put(JSONObject().apply {
          put("tag", "local-dns")
          put("address", "local")
          put("detour", "direct")
        })
        put(JSONObject().apply {
          put("tag", "remote-dns")
          put("address", "77.88.8.8")
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
          throw IllegalArgumentException("Unsupported configuration format. Supported: VLESS links or Subscription URLs.")
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
      val cleanTag = "proxy_" + tag.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.takeIf { it.isNotBlank() }?.take(15) ?: index.toString()
      val uniqueTag = if (usedTags.contains(cleanTag) || cleanTag == "direct" || cleanTag == "auto") {
        "${cleanTag}_$index"
      } else {
        cleanTag
      }
      usedTags.add(uniqueTag)
      ob.put("tag", uniqueTag)

      cleanOutbounds.put(ob)
      proxyTags.put(uniqueTag)
    }

    return if (proxyTags.length() > 1) {
      val urlTestGroup = JSONObject().apply {
        put("type", "urltest")
        put("tag", "auto")
        put("outbounds", proxyTags)
        put("url", "http://connectivitycheck.gstatic.com/generate_204")
        put("interval", "2m")
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
          String(Base64.decode(downloaded.trim(), Base64.DEFAULT), StandardCharsets.UTF_8)
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

    var workingContent = content
    if (!workingContent.startsWith("vless://", ignoreCase = true) && !workingContent.startsWith("{")) {
      try {
        val b64Decoded = String(Base64.decode(workingContent, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
        if (b64Decoded.contains("vless://", ignoreCase = true) || b64Decoded.startsWith("{")) {
          workingContent = b64Decoded
        }
      } catch (_: Exception) {}
    }

    val results = mutableListOf<JSONObject>()

    if (workingContent.startsWith("{") && workingContent.contains("\"outbounds\"")) {
      try {
        val json = JSONObject(workingContent)
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

    for (line in workingContent.lines()) {
      val trimmed = line.trim()
      if (trimmed.startsWith("vless://", ignoreCase = true)) {
        parseVlessUri(trimmed)?.let { results.add(it) }
      }
    }

    return results
  }

  private fun parseVlessUri(vlessUri: String): JSONObject? {
    return try {
      val cleanUri = vlessUri.trim()
      val uri = Uri.parse(cleanUri)
      if (uri.scheme?.lowercase() != "vless") return null

      val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
      val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
      val port = if (uri.port != -1) uri.port else 443

      val security = (uri.getQueryParameter("security") ?: "none").lowercase()
      val flow = uri.getQueryParameter("flow")?.takeIf { it.isNotBlank() }
      val sni = uri.getQueryParameter("sni")
        ?: uri.getQueryParameter("serverName")
        ?: uri.getQueryParameter("peer")
        ?: server
      val pbk = uri.getQueryParameter("pbk")
        ?: uri.getQueryParameter("publicKey")
        .orEmpty()
      val sid = uri.getQueryParameter("sid")
        ?: uri.getQueryParameter("shortId")
        .orEmpty()
      val fp = uri.getQueryParameter("fp") ?: "chrome"
      val type = (uri.getQueryParameter("type") ?: "tcp").lowercase()
      val path = uri.getQueryParameter("path") ?: "/"
      val host = uri.getQueryParameter("host") ?: sni
      val serviceName = uri.getQueryParameter("serviceName") ?: ""

      val rawFragment = uri.fragment
      val tag = if (!rawFragment.isNullOrBlank()) {
        try { java.net.URLDecoder.decode(rawFragment, "UTF-8") } catch (_: Exception) { rawFragment }
      } else "custom-proxy"

      JSONObject().apply {
        put("type", "vless")
        put("tag", tag)
        put("server", server)
        put("server_port", port)
        put("uuid", uuid)
        if (!flow.isNullOrBlank()) {
          put("flow", flow)
        }

        if (security == "reality") {
          put("tls", JSONObject().apply {
            put("enabled", true)
            put("server_name", sni)
            put("utls", JSONObject().apply {
              put("enabled", true)
              put("fingerprint", fp)
            })
            put("reality", JSONObject().apply {
              put("enabled", true)
              if (pbk.isNotBlank()) put("public_key", pbk)
              if (sid.isNotBlank()) put("short_id", sid)
            })
          })
        } else if (security == "tls") {
          put("tls", JSONObject().apply {
            put("enabled", true)
            put("server_name", sni)
            put("utls", JSONObject().apply {
              put("enabled", true)
              put("fingerprint", fp)
            })
          })
        }

        if (type == "ws") {
          put("transport", JSONObject().apply {
            put("type", "ws")
            put("path", path)
            put("headers", JSONObject().apply {
              put("Host", host)
            })
          })
        } else if (type == "grpc") {
          put("transport", JSONObject().apply {
            put("type", "grpc")
            if (serviceName.isNotBlank()) {
              put("service_name", serviceName)
            }
          })
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing VLESS URI: ${e.message}")
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
