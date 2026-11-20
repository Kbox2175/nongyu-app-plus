package com.nongyu.appplus.webvpn.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nongyu.appplus.design.NongyuTheme
import com.nongyu.appplus.login.model.LoginMethod
import com.nongyu.appplus.webvpn.data.PendingCampusCredential
import com.nongyu.appplus.webvpn.data.WebVpnCredentialStore
import com.nongyu.appplus.webvpn.data.WebVpnDialogScheduler
import com.nongyu.appplus.webvpn.data.WebVpnSessionRepository
import com.nongyu.appplus.webvpn.logic.WebVpnAutofill
import com.nongyu.appplus.webvpn.logic.WebVpnTicket
import com.nongyu.appplus.webvpn.model.WebVpnDialogUiState
import com.nongyu.appplus.webvpn.model.WebVpnGateway
import com.nongyu.appplus.webvpn.model.WebVpnLoginPhase
import com.nongyu.appplus.webvpn.model.WebVpnRequest
import com.nongyu.appplus.webvpn.model.WebVpnSubmitResult
import kotlinx.coroutines.launch

/**
 * WebVPN 登录弹窗（根节点最顶层）。
 *
 * ## 直接内嵌学校登录页（2026-11-20 定稿）
 *
 * 放弃原生表单方案：图形验证码无法可靠 v-bind（图会刷新、两个 tab 的图不同），
 * 反复调试证明不可行。现在直接用 WebView 展示学校统一身份认证登录页，
 * 用户自己填账号密码 / 短信码 / 看验证码图并登录 —— 这是最可靠的方式。
 *
 * App 只做两件事：检测票值变化（登录成功）、把票交给后端保管。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebVpnLoginDialog(
    request: WebVpnRequest,
    uiScale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = NongyuTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { WebVpnSessionRepository(context.applicationContext) }
    val credentialStore = remember { WebVpnCredentialStore(context.applicationContext) }
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp

    var uiState by remember(request.nonce) { mutableStateOf(WebVpnDialogUiState()) }
    var reloadKey by remember(request.nonce) { mutableIntStateOf(0) }
    var ticketFound by remember(request.nonce) { mutableStateOf(false) }

    // 凭据来源：主登录页临时记忆优先；否则读弹窗里保存过的加密凭据。
    val snapshot = remember(request.nonce) { PendingCampusCredential.take() }
    val stored = remember(request.nonce) { credentialStore.read() }
    val byPhone = snapshot?.method == LoginMethod.CAMPUS_PHONE
    val prefillAccount = when {
        snapshot != null -> snapshot.account
        stored != null && !byPhone -> stored.account
        else -> ""
    }
    val prefillPassword = when {
        snapshot != null -> snapshot.password.orEmpty()
        stored != null && !byPhone -> stored.password
        else -> ""
    }
    var remember by remember(request.nonce) { mutableStateOf(false) }
    var webViewRef by remember(request.nonce) { mutableStateOf<WebView?>(null) }

    fun readTicket(): String? = try {
        WebVpnTicket.extract(CookieManager.getInstance().getCookie(WebVpnGateway.ORIGIN))
    } catch (e: Exception) {
        null
    }

    fun submitTicket(ticket: String) {
        if (uiState.phase == WebVpnLoginPhase.SUBMITTING ||
            uiState.phase == WebVpnLoginPhase.DONE
        ) {
            return
        }
        uiState = uiState.copy(phase = WebVpnLoginPhase.SUBMITTING, errorText = null)
        scope.launch {
            when (val result = repository.submit(ticket)) {
                is WebVpnSubmitResult.Success -> {
                    uiState = uiState.copy(phase = WebVpnLoginPhase.DONE)
                    WebVpnDialogScheduler.complete()
                }

                is WebVpnSubmitResult.Failure -> {
                    uiState = uiState.copy(
                        phase = WebVpnLoginPhase.FAILED,
                        errorText = result.message,
                    )
                }
            }
        }
    }

    // 登录成功检测：由 WebView.onPageFinished 在页面离开 /login 时调用 tryFinishLogin()。
    // 不用「票值变化」——游客票与有效票形态相同、且登录后票值可能不变，判不出来。
    fun tryFinishLogin() {
        if (ticketFound) return
        val ticket = readTicket() ?: run {
            android.util.Log.w("WebVpnLogin", "离开登录页但没读到票")
            return
        }
        ticketFound = true

        // 勾选了「记住」：登录成功时读页面里用户填的账号/密码/手机号存加密存储。
        if (remember) {
            webViewRef?.evaluateJavascript(WebVpnAutofill.buildReadCredentialsScript()) { raw ->
                val parts = raw?.trim()?.trim('"')?.split('|')
                if (parts != null && parts.size >= 3) {
                    val account = parts[0].trim()
                    val password = parts[1]
                    val phone = parts[2].trim()
                    if (byPhone && phone.isNotEmpty()) {
                        credentialStore.savePhoneOnly(phone)
                    } else if (account.isNotEmpty() && password.isNotEmpty()) {
                        credentialStore.save(account, password)
                    }
                    android.util.Log.w("WebVpnLogin", "已记住凭据 byPhone=$byPhone")
                }
            }
        }

        android.util.Log.w("WebVpnLogin", "离开登录页，读票 len=${ticket.length}，提交")
        submitTicket(ticket)
    }

    BackHandler(enabled = true) { WebVpnDialogScheduler.dismiss() }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { WebVpnDialogScheduler.dismiss() },
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = s(12.dp))
                .height(s(640.dp))
                .clip(RoundedCornerShape(s(20.dp)))
                .background(colors.surface.colorCard)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = s(14.dp), vertical = s(10.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "登录学校 WebVPN",
                        color = colors.surface.colorText,
                        fontSize = (15f * uiScale).sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "登录成功后校外可正常使用校园功能",
                        color = colors.brand.colorBrand,
                        fontSize = (10f * uiScale).sp,
                    )
                }
                CloseCrossButton(uiScale = uiScale) { WebVpnDialogScheduler.dismiss() }
            }

            // 学校登录页：用户自己操作（账密 / 短信 / 验证码图）
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = false

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                android.util.Log.w("WebVpnLogin", "onPageFinished url=${url.take(80)}")

                                // 登录成功检测：页面加载完成且不在登录页 → 网关已认可会话，读票提交。
                                // 覆盖两种情况：① 用户登录成功跳走；② 已有会话直接进门户。
                                // 不要求「先见登录页」——已登录时根本不会出现登录页。
                                val onLoginPage = url.contains("/login") ||
                                    url.contains("/wui/") ||
                                    url.contains("cas_login")
                                if (!onLoginPage && !ticketFound) {
                                    tryFinishLogin()
                                    return
                                }

                                // 登录 SPA 加载后预填账密/手机号 + 切 tab。
                                // 切 tab 后 DOM 异步渲染，延迟 + 重试确保填进新渲染的表单。
                                if (url.contains("/wui/") && prefillAccount.isNotEmpty() &&
                                    view.getTag(TAG_PREFILLED) != true
                                ) {
                                    view.setTag(TAG_PREFILLED, true)
                                    val script = WebVpnAutofill.buildPrefillScript(
                                        prefillAccount, prefillPassword, byPhone,
                                    )
                                    view.postDelayed({
                                        view.evaluateJavascript(script) { r ->
                                            android.util.Log.w("WebVpnPrefill", "首次=$r")
                                        }
                                    }, 600L)
                                    // 切 tab 后表单才渲染，1.6s 再填一次兜底
                                    view.postDelayed({
                                        view.evaluateJavascript(script) { r ->
                                            android.util.Log.w("WebVpnPrefill", "重试=$r")
                                        }
                                    }, 1600L)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame) {
                                    uiState = uiState.copy(errorText = "加载学校登录页失败，请检查网络")
                                }
                            }
                        }
                        loadUrl(WebVpnGateway.LOGIN_URL)
                    }
                },
                update = { webView ->
                    if (webView.getTag(TAG_RELOAD) != reloadKey) {
                        webView.setTag(TAG_RELOAD, reloadKey)
                        if (reloadKey > 0) {
                            ticketFound = false
                            webView.setTag(TAG_PREFILLED, false)
                            webView.loadUrl(WebVpnGateway.LOGIN_URL)
                        }
                    }
                },
            )

            // 记住账号/手机号开关（登录成功时保存用户填的凭据到加密存储）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { remember = !remember }
                    .padding(horizontal = s(14.dp), vertical = s(4.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (remember) "☑" else "☐",
                    color = if (remember) colors.brand.colorBrand else colors.surface.colorTextSecondary,
                    fontSize = (14f * uiScale).sp,
                )
                Spacer(Modifier.size(s(6.dp)))
                Text(
                    text = if (byPhone) "记住手机号" else "记住账号密码",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (11f * uiScale).sp,
                )
            }

            if (uiState.errorText != null) {
                Text(
                    text = uiState.errorText.orEmpty(),
                    color = Color(0xFFB3455E),
                    fontSize = (10f * uiScale).sp,
                    modifier = Modifier.padding(horizontal = s(14.dp), vertical = s(6.dp)),
                )
            } else if (uiState.phase == WebVpnLoginPhase.SUBMITTING) {
                Text(
                    text = "登录成功，正在连接…",
                    color = colors.brand.colorBrand,
                    fontSize = (10f * uiScale).sp,
                    modifier = Modifier.padding(horizontal = s(14.dp), vertical = s(6.dp)),
                )
            }
        }
    }
}

@Composable
private fun CloseCrossButton(uiScale: Float, onClick: () -> Unit) {
    val colors = NongyuTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape((8f * uiScale).dp))
            .clickable(onClick = onClick)
            .padding((7f * uiScale).dp),
        contentAlignment = Alignment.Center,
    ) {
        val len = (11f * uiScale).dp
        val stroke = (1.6f * uiScale).dp
        Canvas(modifier = Modifier.size(len)) {
            val inset = stroke.toPx()
            drawLine(
                color = colors.surface.colorTextSecondary,
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset),
                strokeWidth = inset,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.surface.colorTextSecondary,
                start = Offset(size.width - inset, inset),
                end = Offset(inset, size.height - inset),
                strokeWidth = inset,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val SCRIM_ALPHA = 0.55f
private val TAG_RELOAD = "com.nongyu.appplus.webvpn.RELOAD".hashCode()
private val TAG_PREFILLED = "com.nongyu.appplus.webvpn.PREFILLED".hashCode()
