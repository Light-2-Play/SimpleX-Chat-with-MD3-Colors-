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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

// Глобальное состояние для управления диалогом SingBox из Compose
var showSingBoxDialogState = mutableStateOf(false)

var openByeDpiDialog: (() -> Unit)? = null

class MainActivity: FragmentActivity() {
  companion object {
    const val OLD_ANDROID_UI_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    mainActivity = WeakReference(this)
    super.onCreate(savedInstanceState)

    openByeDpiDialog = {
      showSingBoxDialogState.value = true
    }
    ByeDpiBridge.showDialog = {
      showSingBoxDialogState.value = true
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      getMonetPalette = { isDark ->
        if (isDark) {
          MonetPalette(
            primary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_200)),
            primaryVariant = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_300)),
            background = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_900)),
            surface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_800)),
            onPrimary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_900)),
            onBackground = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_100)),
            onSurface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_100)),
            sentMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent2_800)),
            sentQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent2_700)),
            receivedMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_800)),
            receivedQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_700)),
            primaryVariant2 = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_200))
          )
        } else {
          MonetPalette(
            primary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_600)),
            primaryVariant = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_100)),
            background = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_50)),
            surface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_100)),
            onPrimary = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_0)),
            onBackground = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_900)),
            onSurface = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral1_900)),
            sentMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_100)),
            sentQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_200)),
            receivedMessage = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_100)),
            receivedQuote = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_neutral2_200)),
            primaryVariant2 = androidx.compose.ui.graphics.Color(getColor(android.R.color.system_accent1_900))
          )
        }
      }
    }
   
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

    try {
      android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        try {
          SingBoxService.start(this@MainActivity)
        } catch (e: Throwable) {
          android.util.Log.e("SimpleXMod", "SingBox Startup Error", e)
        }
      }, 1000)
    } catch (e: Throwable) {
      android.util.Log.e("SimpleXMod", "Handler Call failure", e)
    }

    enableEdgeToEdge()

    setContent {
      // Подключаем наш диалог поверх всего приложения
      SingBoxComposeDialogs(this@MainActivity)
      
      AppScreen()
    }

    SimplexApp.context.schedulePeriodicServiceRestartWorker()
    SimplexApp.context.schedulePeriodicWakeUp()
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
        onBackPressedDispatcher.hasEnabledCallbacks()
            || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            || isTaskRoot 
        ) && SimplexApp.context.chatModel.sharedContent.value !is SharedContent.Forward
    if (canFinishActivity) {
      super.onBackPressed()
    }

    if (!onBackPressedDispatcher.hasEnabledCallbacks() && ChatController.appPrefs.performLA.get()) {
      AppLock.clearAuthState()
      AppLock.laFailed.value = true
    }
    if (!onBackPressedDispatcher.hasEnabledCallbacks()) {
      val sharedContent = chatModel.sharedContent.value
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

// =====================================================================
// КОМПОНЕНТЫ ДИАЛОГОВ SINGBOX НА JETPACK COMPOSE (МАТЕРИАЛ 2 + MONET)
// =====================================================================
@Composable
fun SingBoxComposeDialogs(activity: MainActivity) {
    if (!showSingBoxDialogState.value) return

    val colors = CurrentColors.value.colors
    var showCustomInputDialog by remember { mutableStateOf(false) }

    // Основной диалог
    if (!showCustomInputDialog) {
        Dialog(onDismissRequest = { showSingBoxDialogState.value = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    val modeLabel = if (SingBoxService.isCustomMode(activity)) "Custom VLESS" else "Auto Selection"
                    val isRunning = SingBoxService.isRunning
                    val statusText = if (isRunning) "● VLESS active ($modeLabel)" else "○ VLESS disabled"

                    Text(
                        text = "VLESS Proxy Settings",
                        color = colors.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        color = if (isRunning) colors.primary else colors.onSurface.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val limits = listOf(25, 50, 100, 0, -1) // -1 это Custom
                    val options = listOf(
                        "25 servers (Lite)",
                        "50 servers (Mid)",
                        "100 servers (High)",
                        "All available (Ultra)",
                        "Custom VLESS / Subscription"
                    )

                    var selectedIndex by remember {
                        val isCustom = SingBoxService.isCustomMode(activity)
                        val curLim = SingBoxService.getServerLimit(activity)
                        mutableStateOf(if (isCustom) 4 else {
                            val idx = limits.indexOf(curLim)
                            if (idx == -1) 0 else idx
                        })
                    }

                    options.forEachIndexed { index, text ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedIndex = index }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Кастомная радио-кнопка в цветах Monet
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        color = if (selectedIndex == index) colors.primary else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = if (selectedIndex == index) colors.primary else colors.onSurface.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedIndex == index) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(colors.onPrimary, CircleShape)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = text,
                                color = colors.onSurface,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { SingBoxService.stop(); showSingBoxDialogState.value = false },
                            shape = CircleShape 
                        ) {
                            Text("Disable", color = colors.primary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = { showSingBoxDialogState.value = false },
                            shape = CircleShape 
                        ) {
                            Text("Cancel", color = colors.primary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (selectedIndex == 4) {
                                    showCustomInputDialog = true
                                } else {
                                    SingBoxService.setCustomMode(activity, false)
                                    SingBoxService.setServerLimit(activity, limits[selectedIndex])
                                    if (SingBoxService.isRunning) SingBoxService.restart(activity)
                                    else SingBoxService.start(activity)
                                    showSingBoxDialogState.value = false
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = colors.primary,
                                contentColor = colors.onPrimary
                            )
                        ) {
                            Text(if (isRunning) "Apply" else "Turn On")
                        }
                    }
                }
            }
        }
    } else {
        // Диалог ввода кастомного ключа
        Dialog(onDismissRequest = { showCustomInputDialog = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Custom VLESS or link",
                        color = colors.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    var customKey by remember { mutableStateOf(SingBoxService.getCustomKey(activity)) }

                    TextField(
                        value = customKey,
                        onValueChange = { customKey = it },
                        placeholder = { Text("vless://... or https://...") },
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = colors.onSurface,
                            placeholderColor = colors.onSurface.copy(alpha = 0.5f),
                            backgroundColor = colors.background,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = colors.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        maxLines = 5
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showCustomInputDialog = false },
                            shape = CircleShape
                        ) {
                            Text("Back", color = colors.primary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val key = customKey.trim()
                                if (key.isNotEmpty()) {
                                    SingBoxService.setCustomMode(activity, true)
                                    SingBoxService.setCustomKey(activity, key)
                                    if (SingBoxService.isRunning) SingBoxService.restart(activity)
                                    else SingBoxService.start(activity)
                                    showCustomInputDialog = false
                                    showSingBoxDialogState.value = false
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = colors.primary,
                                contentColor = colors.onPrimary
                            )
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// ИНТЕНТЫ СТАНДАРТНОГО APP
// =====================================================================
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
      chatModel.chatId.value = null
      chatModel.clearOverlays.value = true
      when {
        intent.type == "text/plain" -> {
          val text = intent.getStringExtra(Intent.EXTRA_TEXT)
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.File(text ?: "", uri.toURI())
          } else if (text != null) {
            chatModel.sharedContent.value = SharedContent.Text(text)
          }
        }
        isMediaIntent(intent) -> {
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.Media(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", listOf(uri.toURI()))
          }
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
      chatModel.chatId.value = null
      chatModel.clearOverlays.value = true
      when {
        isMediaIntent(intent) -> {
          val uris = intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM) as? List<Uri>
          if (uris != null) {
            if (uris.any { it.scheme != "content" }) return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.Media(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", uris.map { it.toURI() })
          }
        }
        else -> {}
      }
    }
  }
}

fun isMediaIntent(intent: Intent): Boolean =
  intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true
