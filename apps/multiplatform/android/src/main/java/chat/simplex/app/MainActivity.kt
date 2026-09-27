package chat.simplex.app
import chat.simplex.common.ui.theme.MonetPalette
import chat.simplex.common.ui.theme.getMonetPalette
import androidx.compose.ui.graphics.Color
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import chat.simplex.app.model.NtfManager
import chat.simplex.app.model.NtfManager.getUserIdFromIntent
import chat.simplex.common.*
import chat.simplex.common.helpers.*
import chat.simplex.common.model.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.chatlist.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.onboarding.*
import chat.simplex.common.platform.*
import chat.simplex.res.MR
import java.lang.ref.WeakReference
import chat.simplex.app.SingBoxService
import chat.simplex.common.views.chatlist.ByeDpiBridge
// Глобальный обработчик для открытия диалога из Compose UI
var openByeDpiDialog: (() -> Unit)? = null

class MainActivity: FragmentActivity() {
  companion object {
    const val OLD_ANDROID_UI_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
  }

override fun onCreate(savedInstanceState: Bundle?) {
    mainActivity = WeakReference(this)
    super.onCreate(savedInstanceState)

    // 1. Привязываем открытие диалога серверов к кнопкам тулбара:
    openByeDpiDialog = {
      showSingBoxDialog()
    }
    ByeDpiBridge.showDialog = {
      showSingBoxDialog()
    }

    // 2. Динамические цвета Monet (Android 12+):
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      getMonetPalette = { isDark ->
        if (isDark) {
          MonetPalette(
            // Основной акцент (как раз кнопка справа):
            primary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_200)),
            
            // Второстепенный акцент (вкладки Contacts/Groups и поиск — делаем чуть мягче основного, но СВЕТЛЫМ):
            primaryVariant = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_300)),
            
            // Фоны (остаются тёмными):
            background = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_900)),
            surface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_800)),
            
            // Иконка внутри акцентной кнопки (темная на светлой кнопке):
            onPrimary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_900)),
            
            // Основной текст (белый/светло-серый):
            onBackground = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_100)),
            onSurface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_100)),
            
            // Сообщения и плашки (меняем 700/800 на мягкие 200/300):
            sentMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent2_800)),
            sentQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent2_700)),
            receivedMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_800)),
            receivedQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_700)),
            
            // Дополнительный акцент (самый светлый тон для мелких индикаторов вроде 83%):
            primaryVariant2 = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_200))
          )
        } else {
          // ветка для светлой темы остается без изменений
          MonetPalette(
            // Primary (M3 Tone 40) — насыщенный фирменный акцент в светлой теме
            primary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_600)),
            // PrimaryVariant (M3 Primary Container Tone 90)
            primaryVariant = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_100)),

            // Background / Surface (M3 Tone 98 / Tone 95)
            background = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_50)),
            surface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_100)),

            // OnPrimary (Tone 100) — белый текст на насыщенном акценте
            onPrimary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_0)),

            // OnSurface / OnBackground (Tone 10)
            onBackground = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_900)),
            onSurface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_900)),

            // Пузырьки чата в светлой теме
            sentMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_100)),
            sentQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_200)),
            receivedMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_100)),
            receivedQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_200)),

            // PrimaryVariant2 (Tone 10)
            primaryVariant2 = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_900))
          )
        }
      }
    }
   
    // 3. Родная инициализация темы и окружения SimpleX:
    platform.androidSetNightModeIfSupported()
    val c = CurrentColors.value.colors
    platform.androidSetStatusAndNavigationBarAppearance(c.isLight, c.isLight)
    applyAppLocale(ChatModel.controller.appPrefs.appLanguage)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    if (savedInstanceState == null) {
      processNotificationIntent(intent)
      processIntent(intent)
      processExternalIntent(intent)
    }

    if (ChatController.appPrefs.privacyProtectScreen.get()) {
      Log.d(TAG, "onCreate: set FLAG_SECURE")
      window.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
      )
    }

    // 4. Безопасный единственный запуск SingBox с задержкой (не блокирует сплеш-скрин):
    try {
      android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        try {
          SingBoxService.start(this)
        } catch (e: Throwable) {
          android.util.Log.e("SimpleXMod", "Ошибка запуска SingBox", e)
        }
      }, 1000)
    } catch (e: Throwable) {
      android.util.Log.e("SimpleXMod", "Сбой вызова Handler", e)
    }

    enableEdgeToEdge()

    setContent {
      AppScreen()
    }

    SimplexApp.context.schedulePeriodicServiceRestartWorker()
    SimplexApp.context.schedulePeriodicWakeUp()
  }// <--- ВОТ ЗДЕСЬ законно закрывается метод onCreate

 // Вспомогательный метод: мягкие углы 28dp и динамический цвет подложки Material You
  private fun createMD3DialogBackground(): android.graphics.drawable.Drawable {
    val density = resources.displayMetrics.density
    val surfaceColor = com.google.android.material.color.MaterialColors.getColor(
      this,
      com.google.android.material.R.attr.colorSurfaceContainerHigh,
      com.google.android.material.color.MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorSurface,
        android.graphics.Color.DKGRAY
      )
    )

    val shape = android.graphics.drawable.GradientDrawable().apply {
      this.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
      cornerRadius = 28f * density
      setColor(surfaceColor)
    }

    val margin = (16 * density).toInt()
    return android.graphics.drawable.InsetDrawable(shape, margin, margin, margin, margin)
  }

  // Диалог настроек SingBox
  private fun showSingBoxDialog() {
    val options = arrayOf(
      "25 серверов (рекомендуется)",
      "50 серверов",
      "100 серверов",
      "Все доступные",
      "Свой VLESS / подписку"
    )
    val limits = intArrayOf(25, 50, 100, 0)

    val isCustom = SingBoxService.isCustomMode(this)
    val currentLimit = SingBoxService.getServerLimit(this)
    
    // Если включен свой ключ — выбираем 4-й пункт, иначе ищем позицию в limits
    var selectedIndex = if (isCustom) {
      4
    } else {
      limits.indexOf(currentLimit).let { if (it == -1) 0 else it }
    }

    val modeLabel = if (isCustom) "Свой VLESS" else "Автоподбор"
    val statusText = if (SingBoxService.isRunning) "● VLESS активен ($modeLabel, порт 20808)" else "○ VLESS выключен"

    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("Настройки VLESS Proxy\n$statusText")
      .setSingleChoiceItems(options, selectedIndex) { _, which ->
        selectedIndex = which
      }
      .setPositiveButton(if (SingBoxService.isRunning) "Применить" else "Включить") { _, _ ->
        if (selectedIndex == 4) {
          // Открываем ввод своего ключа
          showCustomVlessInputDialog()
        } else {
          // Режим автоподбора: отключаем customMode и сохраняем лимит
          SingBoxService.setCustomMode(this, false)
          SingBoxService.setServerLimit(this, limits[selectedIndex])

          if (SingBoxService.isRunning) {
            SingBoxService.restart(this)
          } else {
            SingBoxService.start(this)
          }
        }
      }
      .setNegativeButton("Отключить") { _, _ ->
        SingBoxService.stop()
      }
      .setNeutralButton("Отмена", null)
      .create()

    dialog.window?.setBackgroundDrawable(createMD3DialogBackground())
    dialog.show()
  }

  // Окно для ввода VLESS ключа или HTTP/HTTPS ссылки
  private fun showCustomVlessInputDialog() {
    val currentKey = SingBoxService.getCustomKey(this)
    val density = resources.displayMetrics.density

    // Контейнер с отступами
    val container = android.widget.FrameLayout(this).apply {
      setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
    }

    // Системные динамические цвета для поля ввода
    val inputBgColor = com.google.android.material.color.MaterialColors.getColor(
      this,
      com.google.android.material.R.attr.colorSurfaceContainerHighest,
      android.graphics.Color.DKGRAY
    )
    val textColor = com.google.android.material.color.MaterialColors.getColor(
      this,
      com.google.android.material.R.attr.colorOnSurface,
      android.graphics.Color.WHITE
    )
    val hintColor = com.google.android.material.color.MaterialColors.getColor(
      this,
      com.google.android.material.R.attr.colorOnSurfaceVariant,
      android.graphics.Color.GRAY
    )

    val input = android.widget.EditText(this).apply {
      hint = "vless://... или https://..."
      setText(currentKey)
      setTextColor(textColor)
      setHintTextColor(hintColor)
      textSize = 14f
      setSingleLine(false)
      maxLines = 5
      setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
      background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = 16f * density
        setColor(inputBgColor)
      }
    }
    container.addView(input)

    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("Свой VLESS или ссылка")
      .setMessage("Вставьте прямую ссылку vless:// или URL-ссылку на подписку:")
      .setView(container)
      .setPositiveButton("Подключить") { _, _ ->
        val key = input.text.toString().trim()
        if (key.isNotEmpty()) {
          SingBoxService.setCustomMode(this, true)
          SingBoxService.setCustomKey(this, key)

          if (SingBoxService.isRunning) {
            SingBoxService.restart(this)
          } else {
            SingBoxService.start(this)
          }
        }
      }
      .setNegativeButton("Назад") { _, _ ->
        showSingBoxDialog()
      }
      .create()

    dialog.window?.setBackgroundDrawable(createMD3DialogBackground())
    dialog.show()
  }
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    processIntent(intent)
    processExternalIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    AppLock.recheckAuthState()
  }

  override fun onPause() {
    super.onPause()
    /**
     * When new activity is created after a click on notification, the old one receives onPause before
     * recreation but receives onStop after recreation. So using both (onPause and onStop) to prevent
     * unwanted multiple auth dialogs from [runAuthenticate]
     * */
    AppLock.appWasHidden()
  }

  override fun onStop() {
    super.onStop()
    VideoPlayerHolder.stopAll()
    AppLock.appWasHidden()
  }

  override fun onDestroy() {
    super.onDestroy()
    SingBoxService.stop()
  }

  override fun onBackPressed() {
    val canFinishActivity = (
        onBackPressedDispatcher.hasEnabledCallbacks() // Has something to do in a backstack
            || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R // Android 11 or above
            || isTaskRoot // there are still other tasks after we reach the main (home) activity
        ) && SimplexApp.context.chatModel.sharedContent.value !is SharedContent.Forward
    if (canFinishActivity) {
      // https://medium.com/mobile-app-development-publication/the-risk-of-android-strandhogg-security-issue-and-how-it-can-be-mitigated-80d2ddb4af06
      super.onBackPressed()
    }

    if (!onBackPressedDispatcher.hasEnabledCallbacks() && ChatController.appPrefs.performLA.get()) {
      // When pressed Back and there is no one wants to process the back event, clear auth state to force re-auth on launch
      AppLock.clearAuthState()
      AppLock.laFailed.value = true
    }
    if (!onBackPressedDispatcher.hasEnabledCallbacks()) {
      val sharedContent = chatModel.sharedContent.value
      // Drop shared content
      chatModel.sharedContent.value = null
      if (sharedContent is SharedContent.Forward) {
        chatModel.chatId.value = sharedContent.fromChatInfo.id
      }
      if (canFinishActivity) {
        finish()
      }
    }
  }
}

fun processNotificationIntent(intent: Intent?) {
  val userId = getUserIdFromIntent(intent)
  when (intent?.action) {
    NtfManager.OpenChatAction -> {
      val chatId = intent.getStringExtra("chatId")
      Log.d(TAG, "processNotificationIntent: OpenChatAction $chatId")
      if (chatId != null) {
        ntfManager.openChatAction(userId, chatId)
      }
    }
    NtfManager.ShowChatsAction -> {
      Log.d(TAG, "processNotificationIntent: ShowChatsAction")
      ntfManager.showChatsAction(userId)
    }
    NtfManager.AcceptCallAction -> {
      val chatId = intent.getStringExtra("chatId")
      if (chatId == null || chatId == "") return
      Log.d(TAG, "processNotificationIntent: AcceptCallAction $chatId")
      ntfManager.acceptCallAction(chatId)
    }
  }
}

fun processIntent(intent: Intent?) {
  when (intent?.action) {
    "android.intent.action.VIEW" -> {
      val uri = intent.data
      if (uri != null) {
        chatModel.appOpenUrl.value = null to uri.toString()
      } else {
        AlertManager.shared.showAlertMsg(generalGetString(MR.strings.error_parsing_uri_title), generalGetString(MR.strings.error_parsing_uri_desc))
      }
    }
  }
}

fun processExternalIntent(intent: Intent?) {
  when (intent?.action) {
    Intent.ACTION_SEND -> {
      // Close active chat and show a list of chats
      chatModel.chatId.value = null
      chatModel.clearOverlays.value = true
      when {
        intent.type == "text/plain" -> {
          val text = intent.getStringExtra(Intent.EXTRA_TEXT)
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            // Shared file that contains plain text, like `*.log` file
            chatModel.sharedContent.value = SharedContent.File(text ?: "", uri.toURI())
          } else if (text != null) {
            // Shared just a text
            chatModel.sharedContent.value = SharedContent.Text(text)
          }
        }
        isMediaIntent(intent) -> {
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.Media(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", listOf(uri.toURI()))
          } // All other mime types
        }
        else -> {
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.File(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", uri.toURI())
          }
        }
      }
    }
    Intent.ACTION_SEND_MULTIPLE -> {
      // Close active chat and show a list of chats
      chatModel.chatId.value = null
      chatModel.clearOverlays.value = true
      Log.e(TAG, "ACTION_SEND_MULTIPLE ${intent.type}")
      when {
        isMediaIntent(intent) -> {
          val uris = intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM) as? List<Uri>
          if (uris != null) {
            if (uris.any { it.scheme != "content" }) return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.Media(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", uris.map { it.toURI() })
          } // All other mime types
        }
        else -> {}
      }
    }
  }
}

fun isMediaIntent(intent: Intent): Boolean =
  intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true

//fun testJson() {
//  val str: String = """
//  """.trimIndent()
//
//  println(json.decodeFromString<APIResult>(str))
//}
