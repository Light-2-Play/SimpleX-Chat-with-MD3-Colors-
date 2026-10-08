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
    "https://github.com/Au1rxx/free-vpn-subscriptions/raw/main/output/singbox.json",
    "https://cdn.jsdelivr.net/gh/awesome-vpn/awesome-vpn@master/sing-box.json",
    "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/singbox.json"
  )

  private const val PREFS_NAME = "singbox_preferences"
  private const val KEY_SERVER_LIMIT = "server_limit"
  private const val KEY_CUSTOM_MODE = "custom_mode"
  private const val KEY_CUSTOM_KEY = "custom_key"
  const val DEFAULT_SERVER_LIMIT = 25

  // === Настройки пула (25, 50, 100) ===
  fun getServerLimit(context: Context): Int {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getInt(KEY_SERVER_LIMIT, DEFAULT_SERVER_LIMIT)
  }

  fun setServerLimit(context: Context, limit: Int) {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putInt(KEY_SERVER_LIMIT, limit).apply()
  }

  // === Настройки кастомного VLESS / AWG / Подписки ===
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
      showToast(context, "Прокси отключен")
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
          showToast(context, "Ошибка: libsingbox.so не найден")
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
                Log.d(TAG, line)
                lastLog = line
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
          showToast(context, "Прокси подключен ($LOCAL_PORT)")
        } else {
          val errorDetail = if (!proc.isAlive) {
            "вылет (код ${proc.exitValue()}):$lastLog"
          } else {
            "таймаут порта $LOCAL_PORT"
          }
          stop()
          showToast(context, "Ошибка: $errorDetail")
        }
      } catch (e: Exception) {
        showToast(context, "Сбой: ${e.message}")
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

    // 1. Логирование
    root.put("log", JSONObject().apply {
      put("level", "warn")
    })

    // 2. Входящий SOCKS5
    val socksInbound = JSONObject().apply {
      put("type", "socks")
      put("tag", "socks-in")
      put("listen", "127.0.0.1")
      put("listen_port", LOCAL_PORT)
    }
    root.put("inbounds", JSONArray().apply { put(socksInbound) })

    // 3. DNS
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

    // 4. Проверяем режим: Кастомный VLESS/AWG или Автоподбор
    if (isCustomMode(context) && getCustomKey(context).isNotBlank()) {
      val customOutbound = resolveCustomOutbound(getCustomKey(context))
      if (customOutbound != null) {
        cleanOutbounds.put(customOutbound)
        targetTag = customOutbound.optString("tag", "custom-proxy")
      } else {
        throw IllegalArgumentException("Не удалось распознать VLESS / AWG ключ или подписку")
      }
    } else {
      // Режим автоподбора серверов
      var rawJson: String? = null
      for (url in SUBSCRIPTION_URLS) {
        try {
          val downloaded = downloadUrl(url)
          if (downloaded.isNotBlank()) {
            rawJson = downloaded
            break
          }
        } catch (e: Exception) {
          Log.w(TAG, "Ошибка загрузки $url:${e.message}")
        }
      }

      if (rawJson.isNullOrBlank()) {
        if (configFile.exists()) return configFile
        throw IllegalStateException("Не удалось загрузить подписку")
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

    // 5. Маршрутизация
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

  /**
   * Разбирает ключ: vless://, awg://, .conf или скачивает данные по HTTP
   */
  private fun resolveCustomOutbound(rawInput: String): JSONObject? {
    val input = rawInput.trim()

    // 1. Если это URL — пробуем скачать
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
        Log.e(TAG, "Ошибка загрузки кастомной ссылки: ${e.message}")
        input
      }
    } else {
      input
    }

    // 2. Проверяем наличие строки vless://
    val vlessLine = content.lines().firstOrNull { it.trim().startsWith("vless://", ignoreCase = true) }?.trim()
    if (vlessLine != null) {
      parseVlessUri(vlessLine)?.let { return it }
    }

    // 3. Проверяем AmneziaWG (конфиг .conf или ссылка awg://)
    parseAwg(content)?.let { return it }

    // 4. Если ничего не подошло, пробуем распарсить как прямую VLESS ссылку
    return parseVlessUri(content)
  }

  /**
   * Распознавание формата AmneziaWG (awg:// URI или .conf файл)
   */
  private fun parseAwg(raw: String): JSONObject? {
    val trimmed = raw.trim()

    // Формат ссылки awg://
    if (trimmed.startsWith("awg://", ignoreCase = true)) {
      val uriPart = trimmed.substring(6)
      val tag = if (trimmed.contains("#")) trimmed.substringAfter("#") else "awg-proxy"
      val beforeFragment = uriPart.substringBefore("#")

      // Проверяем: не зашифрован ли внутри Base64 конфиг .conf
      try {
        val decoded = String(Base64.decode(beforeFragment, Base64.DEFAULT))
        if (decoded.contains("[Interface]", ignoreCase = true) || decoded.contains("PrivateKey", ignoreCase = true)) {
          return parseAwgConf(decoded, tag)
        }
      } catch (_: Exception) {}

      // Иначе разбираем как стандартный URI с query-параметрами
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
          addressParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
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

            uri.getQueryParameter("preshared_key")?.takeIf { it.isNotBlank() }?.let { put("pre_shared_key", it) }
            uri.getQueryParameter("psk")?.takeIf { it.isNotBlank() }?.let { put("pre_shared_key", it) }
            uri.getQueryParameter("mtu")?.toIntOrNull()?.let { put("mtu", it) }

            // Параметры AmneziaWG
            (uri.getQueryParameter("jc") ?: uri.getQueryParameter("junk_packet_count"))?.toIntOrNull()?.let { put("junk_packet_count", it) }
            (uri.getQueryParameter("jmin") ?: uri.getQueryParameter("junk_packet_min_size"))?.toIntOrNull()?.let { put("junk_packet_min_size", it) }
            (uri.getQueryParameter("jmax") ?: uri.getQueryParameter("junk_packet_max_size"))?.toIntOrNull()?.let { put("junk_packet_max_size", it) }
            (uri.getQueryParameter("s1") ?: uri.getQueryParameter("init_packet_junk_size"))?.toIntOrNull()?.let { put("init_packet_junk_size", it) }
            (uri.getQueryParameter("s2") ?: uri.getQueryParameter("response_packet_junk_size"))?.toIntOrNull()?.let { put("response_packet_junk_size", it) }
            (uri.getQueryParameter("h1") ?: uri.getQueryParameter("init_packet_magic_header"))?.toLongOrNull()?.let { put("init_packet_magic_header", it) }
            (uri.getQueryParameter("h2") ?: uri.getQueryParameter("response_packet_magic_header"))?.toLongOrNull()?.let { put("response_packet_magic_header", it) }
            (uri.getQueryParameter("h3") ?: uri.getQueryParameter("underload_packet_magic_header"))?.toLongOrNull()?.let { put("underload_packet_magic_header", it) }
            (uri.getQueryParameter("h4") ?: uri.getQueryParameter("transport_packet_magic_header"))?.toLongOrNull()?.let { put("transport_packet_magic_header", it) }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Ошибка парсинга awg:// ссылки: ${e.message}")
      }
    }

    // Формат текстового .conf конфига
    if (trimmed.contains("[Interface]", ignoreCase = true) || trimmed.contains("[Peer]", ignoreCase = true)) {
      return parseAwgConf(trimmed, "awg-proxy")
    }

    return null
  }

  /**
   * Разбор классического текстового .conf конфига WireGuard / AmneziaWG
   */
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

      var peerPublicKey = ""
      var preSharedKey = ""
      var server = ""
      var serverPort = 51820

      for (rawLine in confText.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

        if (line.startsWith("[") && line.endsWith("]")) {
          currentSection = line.substring(1, line.length - 1).trim().lowercase()
          continue
        }

        val parts = line.split("=", limit = 2)
        if (parts.size != 2) continue
        val key = parts[0].trim().lowercase()
        val value = parts[1].trim()

        when (currentSection) {
          "interface" -> {
            when (key) {
              "privatekey" -> privateKey = value
              "address" -> {
                value.split(",").forEach { addr ->
                  val trimmedAddr = addr.trim()
                  if (trimmedAddr.isNotEmpty()) localAddresses.put(trimmedAddr)
                }
              }
              "mtu" -> mtu = value.toIntOrNull()
              "jc" -> jc = value.toIntOrNull()
              "jmin" -> jmin = value.toIntOrNull()
              "jmax" -> jmax = value.toIntOrNull()
              "s1" -> s1 = value.toIntOrNull()
              "s2" -> s2 = value.toIntOrNull()
              "h1" -> h1 = value.toLongOrNull()
              "h2" -> h2 = value.toLongOrNull()
              "h3" -> h3 = value.toLongOrNull()
              "h4" -> h4 = value.toLongOrNull()
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
            }
          }
        }
      }

      if (server.isBlank() || privateKey.isBlank() || peerPublicKey.isBlank()) {
        Log.w(TAG, "В AWG конфигурации отсутствуют обязательные поля Endpoint, PrivateKey или PublicKey")
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
      Log.e(TAG, "Ошибка разбора AWG conf: ${e.message}")
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
