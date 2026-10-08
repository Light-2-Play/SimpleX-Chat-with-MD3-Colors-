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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.concurrent.thread

object SingBoxService {

  private const val TAG = "SingBoxService"
  private const val LOCAL_PORT = 20808
  private var process: Process? = null

  private val SUBSCRIPTION_URLS = listOf(
    "https://github.com/Au1rxx/free-vpn-subscriptions/raw/main/output/singbox.json",
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
        for (i in 0 until 25) {
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

    val dns = JSONObject().apply {
      val servers = JSONArray().apply {
        put(JSONObject().apply {
          put("tag", "quad9-doh")
          put("address", "https://9.9.9.9/dns-query")
          put("detour", "direct")
        })
        put(JSONObject().apply {
          put("tag", "google-doh")
          put("address", "https://8.8.8.8/dns-query")
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
      val customOutbound = resolveCustomOutbound(getCustomKey(context))
      if (customOutbound != null) {
        cleanOutbounds.put(customOutbound)
        targetTag = customOutbound.optString("tag", "custom-proxy")
      } else {
        throw IllegalArgumentException("Failed to parse VLESS, AWG, or Amnezia configuration")
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
        if (configFile.exists()) return configFile
        throw IllegalStateException("Failed to download subscription")
      }

      val sourceRoot = JSONObject(rawJson)
      val sourceOutbounds = sourceRoot.optJSONArray("outbounds") ?: JSONArray()
      val candidateOutbounds = mutableListOf<JSONObject>()

      for (i in 0 until sourceOutbounds.length()) {
        val ob = sourceOutbounds.getJSONObject(i)
        val type = ob.optString("type")
        if (type == "direct" || type == "block" || type == "dns" || type == "urltest" || type == "selector") continue
        candidateOutbounds.add(ob)
      }

      val limit = getServerLimit(context)
      val selectedOutbounds = if (limit in 1 until candidateOutbounds.size) {
        candidateOutbounds.shuffled().take(limit)
      } else {
        candidateOutbounds
      }

      val proxyTags = JSONArray()
      for (ob in selectedOutbounds) {
        cleanOutbounds.put(ob)
        proxyTags.put(ob.optString("tag"))
      }

      if (proxyTags.length() > 0) {
        val urlTestGroup = JSONObject().apply {
          put("type", "urltest")
          put("tag", "auto")
          put("outbounds", proxyTags)
          put("url", "https://www.gstatic.com/generate_204")
          put("interval", "2m")
          put("tolerance", 50)
        }
        cleanOutbounds.put(urlTestGroup)
        targetTag = "auto"
      }
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

  private fun resolveCustomOutbound(rawInput: String): JSONObject? {
    var input = rawInput.trim()

    try {
      val file = File(input)
      if (file.exists() && file.isFile) {
        input = file.readText().trim()
      }
    } catch (_: Exception) {}

    // 1. Amnezia vpn:// URI
    if (input.startsWith("vpn://", ignoreCase = true)) {
      val extractedConf = decodeAmneziaVpnUri(input)
      if (!extractedConf.isNullOrBlank()) {
        parseAwgConf(extractedConf, "amnezia-free")?.let { return it }
      }
    }

    // 2. HTTP subscription
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

    // 3. VLESS
    val vlessLine = content.lines().firstOrNull { it.trim().startsWith("vless://", ignoreCase = true) }?.trim()
    if (vlessLine != null) {
      parseVlessUri(vlessLine)?.let { return it }
    }

    // 4. AmneziaWG 2.0 / 3.0 / 3.1 (.conf or awg://)
    parseAwg(content)?.let { return it }

    return parseVlessUri(content)
  }

  /**
   * Universal decoder for Amnezia vpn:// URI (v2.0, v3.0, v3.1, Amnezia Free)
   */
  private fun decodeAmneziaVpnUri(vpnUri: String): String? {
    return try {
      var raw = vpnUri.trim()
      if (raw.startsWith("vpn://", ignoreCase = true)) {
        raw = raw.substring(6)
      }
      raw = raw.substringBefore("#").trim()
      if (raw.contains("%")) {
        raw = URLDecoder.decode(raw, "UTF-8")
      }
      raw = raw.replace("\\s".toRegex(), "")

      val mod = raw.length % 4
      if (mod != 0) {
        raw += "=".repeat(4 - mod)
      }

      val rawBytes = try {
        Base64.decode(raw, Base64.DEFAULT)
      } catch (_: Exception) {
        Base64.decode(raw, Base64.URL_SAFE)
      }

      val decompressed = decompressPayload(rawBytes) ?: return null

      if (decompressed.contains("[Interface]", ignoreCase = true) && !decompressed.trim().startsWith("{")) {
        return decompressed
      }

      if (decompressed.trim().startsWith("{") || decompressed.trim().startsWith("[")) {
        val root: Any? = try {
          JSONObject(decompressed)
        } catch (_: Exception) {
          try {
            JSONArray(decompressed)
          } catch (_: Exception) {
            null
          }
        }
        if (root != null) {
          val extracted = findConfigInJson(root)
          if (!extracted.isNullOrBlank()) {
            return extracted
          }
        }
      }

      null
    } catch (e: Exception) {
      Log.e(TAG, "Failed to decode vpn:// URI: ${e.message}")
      null
    }
  }

  private fun decompressPayload(bytes: ByteArray): String? {
    val directText = String(bytes, Charsets.UTF_8).trim()
    if (directText.startsWith("{") || directText.contains("[Interface]")) {
      return directText
    }

    // Qt qCompress (offset 4) and zlib (offset 0)
    for (offset in listOf(4, 0)) {
      if (bytes.size <= offset) continue
      try {
        val inflater = Inflater()
        inflater.setInput(bytes, offset, bytes.size - offset)
        val bos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
          val count = inflater.inflate(buffer)
          if (count <= 0 && inflater.needsInput()) break
          bos.write(buffer, 0, count)
        }
        inflater.end()
        val res = bos.toString("UTF-8").trim()
        if (res.startsWith("{") || res.contains("[Interface]")) {
          return res
        }
      } catch (_: Exception) {}
    }

    // GZIP
    try {
      val gis = GZIPInputStream(bytes.inputStream())
      val res = gis.bufferedReader(Charsets.UTF_8).readText().trim()
      if (res.startsWith("{") || res.contains("[Interface]")) {
        return res
      }
    } catch (_: Exception) {}

    return if (directText.isNotEmpty()) directText else null
  }

  /**
   * Recursive config finder inside arbitrary JSON payloads
   */
  private fun findConfigInJson(node: Any): String? {
    when (node) {
      is String -> {
        val trimmed = node.trim()
        if (trimmed.contains("[Interface]", ignoreCase = true)) {
          return if (trimmed.startsWith("{")) {
            try {
              findConfigInJson(JSONObject(trimmed)) ?: trimmed
            } catch (_: Exception) {
              trimmed
            }
          } else {
            trimmed
          }
        }
        if (trimmed.length > 24 && !trimmed.contains(" ")) {
          try {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.contains("[Interface]", ignoreCase = true)) {
              return decoded
            }
          } catch (_: Exception) {}
        }
      }
      is JSONObject -> {
        val priorityKeys = listOf("last_config", "config", "client_config", "awg", "amnezia-awg", "wireguard", "containers")
        for (k in priorityKeys) {
          if (node.has(k)) {
            val res = findConfigInJson(node.get(k))
            if (!res.isNullOrBlank()) return res
          }
        }
        val keys = node.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          if (k !in priorityKeys) {
            val res = findConfigInJson(node.get(k))
            if (!res.isNullOrBlank()) return res
          }
        }
      }
      is JSONArray -> {
        for (i in 0 until node.length()) {
          val res = findConfigInJson(node.get(i))
          if (!res.isNullOrBlank()) return res
        }
      }
    }
    return null
  }

  private fun parseAwg(raw: String): JSONObject? {
    val trimmed = raw.trim()

    // awg:// links
    if (trimmed.startsWith("awg://", ignoreCase = true)) {
      val tag = if (trimmed.contains("#")) trimmed.substringAfter("#") else "awg-proxy"
      val uriPart = trimmed.removePrefix("awg://").removePrefix("AWG://").substringBefore("#").trim()

      try {
        var b64 = uriPart
        val mod = b64.length % 4
        if (mod != 0) b64 += "=".repeat(4 - mod)
        val decoded = String(Base64.decode(b64, Base64.DEFAULT or Base64.URL_SAFE), Charsets.UTF_8)
        if (decoded.contains("[Interface]", ignoreCase = true) || decoded.contains("PrivateKey", ignoreCase = true)) {
          return parseAwgConf(decoded, tag)
        }
      } catch (_: Exception) {}

      try {
        val uri = Uri.parse(trimmed)
        val privateKey = uri.userInfo.orEmpty()
        val server = uri.host.orEmpty()
        val port = if (uri.port != -1) uri.port else 51820
        val peerPublicKey = uri.getQueryParameter("public_key")
          ?: uri.getQueryParameter("peer_public_key")
          ?: uri.getQueryParameter("pk").orEmpty()

        val addressParam = uri.getQueryParameter("address")
          ?: uri.getQueryParameter("local_address")
          ?: uri.getQueryParameter("ip")
          ?: "10.0.0.2/32"

        val localAddresses = JSONArray().apply {
          addressParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            val cidr = if (!it.contains("/")) if (it.contains(":")) "$it/128" else "$it/32" else it
            put(cidr)
          }
        }

        if (server.isNotEmpty() && privateKey.isNotEmpty() && peerPublicKey.isNotEmpty()) {
          return JSONObject().apply {
            put("type", "wireguard")
            put("tag", tag)
            put("server", server)
            put("server_port", port)
            put("local_address", localAddresses)
            put("private_key", privateKey)
            put("peer_public_key", peerPublicKey)

            (uri.getQueryParameter("preshared_key") ?: uri.getQueryParameter("psk"))?.takeIf { it.isNotBlank() }?.let { put("pre_shared_key", it) }
            uri.getQueryParameter("mtu")?.toIntOrNull()?.let { put("mtu", it) }

            uri.getQueryParameter("reserved")?.let { rStr ->
              val rList = rStr.split(",").mapNotNull { it.trim().toIntOrNull() }
              if (rList.isNotEmpty()) {
                put("reserved", JSONArray().apply { rList.forEach { put(it) } })
              }
            }

            fun parseQueryMagic(param: String?): Long? {
              if (param == null) return null
              val s = param.trim()
              return if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toLongOrNull(16) else s.toLongOrNull()?.let { it and 0xFFFFFFFFL }
            }

            (uri.getQueryParameter("jc") ?: uri.getQueryParameter("junk_packet_count"))?.toIntOrNull()?.let { put("junk_packet_count", it) }
            (uri.getQueryParameter("jmin") ?: uri.getQueryParameter("junk_packet_min_size"))?.toIntOrNull()?.let { put("junk_packet_min_size", it) }
            (uri.getQueryParameter("jmax") ?: uri.getQueryParameter("junk_packet_max_size"))?.toIntOrNull()?.let { put("junk_packet_max_size", it) }
            (uri.getQueryParameter("s1") ?: uri.getQueryParameter("init_packet_junk_size"))?.toIntOrNull()?.let { put("init_packet_junk_size", it) }
            (uri.getQueryParameter("s2") ?: uri.getQueryParameter("response_packet_junk_size"))?.toIntOrNull()?.let { put("response_packet_junk_size", it) }
            parseQueryMagic(uri.getQueryParameter("h1") ?: uri.getQueryParameter("init_packet_magic_header"))?.let { put("init_packet_magic_header", it) }
            parseQueryMagic(uri.getQueryParameter("h2") ?: uri.getQueryParameter("response_packet_magic_header"))?.let { put("response_packet_magic_header", it) }
            parseQueryMagic(uri.getQueryParameter("h3") ?: uri.getQueryParameter("underload_packet_magic_header"))?.let { put("underload_packet_magic_header", it) }
            parseQueryMagic(uri.getQueryParameter("h4") ?: uri.getQueryParameter("transport_packet_magic_header"))?.let { put("transport_packet_magic_header", it) }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse awg:// link: ${e.message}")
      }
    }

    if (trimmed.contains("[Interface]", ignoreCase = true) || trimmed.contains("[Peer]", ignoreCase = true)) {
      return parseAwgConf(trimmed, "awg-proxy")
    }

    return null
  }

  private fun parseAwgConf(confText: String, defaultTag: String): JSONObject? {
    try {
      var currentSection = ""
      var privateKey = ""
      val localAddresses = JSONArray()
      var mtu: Int? = null

      var jc: Int? = null
      var jmin: Int? = null
      var jmax: Int? = null
      var s1: Int? = null
      var s2: Int? = null
      var h1: Long? = null
      var h2: Long? = null
      var h3: Long? = null
      var h4: Long? = null
      var reservedArray: JSONArray? = null

      var peerPublicKey = ""
      var preSharedKey = ""
      var server = ""
      var serverPort = 51820

      fun parseMagic(str: String): Long? {
        val s = str.trim()
        return if (s.startsWith("0x", ignoreCase = true)) {
          s.substring(2).toLongOrNull(16)
        } else {
          s.toLongOrNull()
        }?.let { it and 0xFFFFFFFFL }
      }

      for (rawLine in confText.lines()) {
        val line = rawLine.substringBefore('#').substringBefore(';').trim()
        if (line.isEmpty()) continue

        if (line.startsWith("[") && line.endsWith("]")) {
          currentSection = line.substring(1, line.length - 1).trim().lowercase()
          continue
        }

        val parts = line.split("=", limit = 2)
        if (parts.size != 2) continue
        val key = parts[0].trim().lowercase()
        val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")

        when (currentSection) {
          "interface" -> {
            when (key) {
              "privatekey" -> privateKey = value
              "address" -> {
                value.split(",").forEach { addr ->
                  val trimmedAddr = addr.trim()
                  if (trimmedAddr.isNotEmpty()) {
                    val cidr = if (!trimmedAddr.contains("/")) {
                      if (trimmedAddr.contains(":")) "$trimmedAddr/128" else "$trimmedAddr/32"
                    } else {
                      trimmedAddr
                    }
                    localAddresses.put(cidr)
                  }
                }
              }
              "mtu" -> mtu = value.toIntOrNull()
              "jc" -> jc = value.toIntOrNull()
              "jmin" -> jmin = value.toIntOrNull()
              "jmax" -> jmax = value.toIntOrNull()
              "s1" -> s1 = value.toIntOrNull()
              "s2" -> s2 = value.toIntOrNull()
              "h1" -> h1 = parseMagic(value)
              "h2" -> h2 = parseMagic(value)
              "h3" -> h3 = parseMagic(value)
              "h4" -> h4 = parseMagic(value)
              "reserved" -> {
                val rList = value.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (rList.isNotEmpty()) {
                  reservedArray = JSONArray().apply { rList.forEach { put(it) } }
                }
              }
            }
          }
          "peer" -> {
            when (key) {
              "publickey" -> peerPublicKey = value
              "presharedkey" -> preSharedKey = value
              "endpoint" -> {
                val lastColon = value.lastIndexOf(':')
                if (lastColon != -1) {
                  server = value.substring(0, lastColon).trim().removePrefix("[").removeSuffix("]")
                  serverPort = value.substring(lastColon + 1).trim().toIntOrNull() ?: 51820
                } else {
                  server = value
                }
              }
              "reserved" -> {
                val rList = value.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (rList.isNotEmpty()) {
                  reservedArray = JSONArray().apply { rList.forEach { put(it) } }
                }
              }
            }
          }
        }
      }

      if (server.isBlank() || privateKey.isBlank() || peerPublicKey.isBlank()) {
        Log.w(TAG, "Missing Endpoint, PrivateKey, or PublicKey in AWG configuration")
        return null
      }

      return JSONObject().apply {
        put("type", "wireguard")
        put("tag", defaultTag)
        put("server", server)
        put("server_port", serverPort)
        put("local_address", if (localAddresses.length() > 0) localAddresses else JSONArray().apply { put("10.0.0.2/32") })
        put("private_key", privateKey)
        put("peer_public_key", peerPublicKey)

        if (preSharedKey.isNotBlank()) put("pre_shared_key", preSharedKey)
        if (mtu != null) put("mtu", mtu)
        if (reservedArray != null) put("reserved", reservedArray)

        if (jc != null) put("junk_packet_count", jc)
        if (jmin != null) put("junk_packet_min_size", jmin)
        if (jmax != null) put("junk_packet_max_size", jmax)
        if (s1 != null) put("init_packet_junk_size", s1)
        if (s2 != null) put("response_packet_junk_size", s2)
        if (h1 != null) put("init_packet_magic_header", h1)
        if (h2 != null) put("response_packet_magic_header", h2)
        if (h3 != null) put("underload_packet_magic_header", h3)
        if (h4 != null) put("transport_packet_magic_header", h4)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing AWG conf: ${e.message}")
      return null
    }
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
      val tag = uri.fragment?.takeIf { it.isNotBlank() } ?: "custom-proxy"

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
