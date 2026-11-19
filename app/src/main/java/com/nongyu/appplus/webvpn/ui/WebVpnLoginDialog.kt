package com.nongyu.appplus.webvpn.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nongyu.appplus.design.NongyuTheme
import com.nongyu.appplus.webvpn.data.PendingCampusCredential
import com.nongyu.appplus.webvpn.data.WebVpnCredentialStore
import com.nongyu.appplus.webvpn.data.WebVpnDialogScheduler
import com.nongyu.appplus.webvpn.data.WebVpnSessionRepository
import com.nongyu.appplus.webvpn.logic.WebVpnAutofill
import com.nongyu.appplus.webvpn.logic.WebVpnSignInDefaults
import com.nongyu.appplus.webvpn.logic.WebVpnTicket
import com.nongyu.appplus.webvpn.model.WebVpnDialogUiState
import com.nongyu.appplus.webvpn.model.WebVpnGateway
import com.nongyu.appplus.webvpn.model.WebVpnLoginPhase
import com.nongyu.appplus.webvpn.model.WebVpnRequest
import com.nongyu.appplus.webvpn.model.WebVpnSubmitResult
import com.nongyu.appplus.webvpn.model.WebVpnTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学校 WebVPN 登录子窗体（**根节点最顶层浮层**）。
 *
 * ## 它是什么（维护者的定义）
 *
 * > 弹出弹窗，是我们的登录页面，但是底层还是学校的统一身份认证，就是一个桥。
 *
 * 所以这个弹窗**就是农屿自己的登录页**：原生 Compose 界面，三种方式（密码登录 /
 * 短信登录 / 微信扫码），字段与学校页面一一对应；学校那个网页**不显示给用户**，
 * 只作为不可见的桥负责真正跑完登录、把 `wengine_vpn_ticket` 发下来。
 *
 * ## 凭据从哪来
 *
 * 弹窗打开时按维护者规则决定默认方式与预填：
 * 上次用账密登录过 → 取账密；上次用手机号 → 取手机号；什么都取不到 → 默认（账密）。
 * 实现见 [WebVpnSignInDefaults.resolve] 与 [PendingCampusCredential]。
 *
 * ## 层级
 *
 * 由 [WebVpnDialogHost] 挂在 `NongyuApp` 根 `Box` 的最后一个子节点，
 * 盖住主界面、登录中心、设置面板与星云球。
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
    val probe = remember(request.nonce) { WebVpnLoginProbe() }
    val lastFinishedUrl = remember(request.nonce) { mutableStateOf<String?>(null) }
    var webViewReloadKey by remember(request.nonce) { mutableIntStateOf(0) }

    // ---- 表单状态（三种方式共用一组变量）----
    var mode by remember(request.nonce) {
        mutableStateOf(WebVpnSignInDefaults.DEFAULT_MODE)
    }
    var account by remember(request.nonce) { mutableStateOf("") }
    var password by remember(request.nonce) { mutableStateOf("") }
    var imageCaptcha by remember(request.nonce) { mutableStateOf("") }
    var smsCode by remember(request.nonce) { mutableStateOf("") }
    var rememberCredential by remember(request.nonce) { mutableStateOf(true) }
    var pendingSubmit by remember(request.nonce) { mutableStateOf(false) }

    // ---- 打开时取凭据：临时 store 优先，其次已保存的加密凭据 ----
    LaunchedEffect(request.nonce) {
        val pending = PendingCampusCredential.take()
        val initial = WebVpnSignInDefaults.resolve(pending)
        mode = initial.mode
        if (initial.account.isNotEmpty()) account = initial.account
        if (initial.password.isNotEmpty()) password = initial.password

        // 临时 store 取不到时才回落到持久化凭据
        if (!initial.prefilled) {
            val saved = withContext(Dispatchers.IO) { credentialStore.read() }
            if (saved != null) {
                if (account.isEmpty()) account = saved.account
                if (password.isEmpty()) password = saved.password
            }
        }
    }

    /** 读网关票：CookieManager 拿整条 Cookie 头，解析交纯逻辑层。 */
    fun readTicket(): String? = try {
        WebVpnTicket.extract(CookieManager.getInstance().getCookie(WebVpnGateway.ORIGIN))
    } catch (e: Exception) {
        null
    }

    /** 提交票给后端保管。票值只在调用链里流动，绝不写日志。 */
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
                    if (rememberCredential && WebVpnAutofill.canAutofill(account, password)) {
                        withContext(Dispatchers.IO) {
                            credentialStore.save(account.trim(), password)
                        }
                    }
                    uiState = uiState.copy(phase = WebVpnLoginPhase.DONE)
                    WebVpnDialogScheduler.complete()
                }

                is WebVpnSubmitResult.Failure -> {
                    probe.submitted = false
                    pendingSubmit = false
                    uiState = uiState.copy(
                        phase = WebVpnLoginPhase.FAILED,
                        errorText = result.message,
                    )
                }
            }
        }
    }

    // 页面加载完成：若等待提交，就注入表单并点登录
    LaunchedEffect(lastFinishedUrl.value, pendingSubmit) {
        if (!pendingSubmit) return@LaunchedEffect
        val url = lastFinishedUrl.value ?: return@LaunchedEffect
        if (WebVpnTicket.isStillOnLoginPage(url) && probe.webView != null) {
            probe.webView?.evaluateJavascript(
                WebVpnAutofill.buildSignInScript(
                    account = account,
                    password = password,
                    captcha = imageCaptcha,
                ),
                null,
            )
            pendingSubmit = false
        }
    }

    // 离开登录页且票值变化 → 提交给后端
    LaunchedEffect(lastFinishedUrl.value, uiState.phase) {
        val url = lastFinishedUrl.value ?: return@LaunchedEffect
        if (uiState.phase != WebVpnLoginPhase.LOADING || probe.submitted) return@LaunchedEffect
        val left = !WebVpnTicket.isStillOnLoginPage(url)
        val ticket = readTicket()
        uiState = uiState.copy(leftLoginPage = left)
        if (left && ticket != null && ticket != probe.guestTicket) {
            probe.submitted = true
            submitTicket(ticket)
        }
    }

    BackHandler(enabled = true) { WebVpnDialogScheduler.dismiss() }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        // 模态遮板
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
                .padding(horizontal = s(20.dp))
                .height(s(CARD_HEIGHT_DP.dp))
                .clip(RoundedCornerShape(s(20.dp)))
                .background(colors.surface.colorCard)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(s(16.dp)),
        ) {
            // 标题行：X 关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "登录学校 WebVPN",
                    color = colors.surface.colorText,
                    fontSize = (15f * uiScale).sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                CloseCrossButton(uiScale = uiScale) { WebVpnDialogScheduler.dismiss() }
            }

            Spacer(Modifier.height(s(4.dp)))
            Text(
                text = subtitleOf(request.trigger),
                color = colors.brand.colorBrand,
                fontSize = (10f * uiScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(s(10.dp)))

            // 主体：我们自己的三方式登录页
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SignInPanel(
                    uiScale = uiScale,
                    mode = mode,
                    account = account,
                    password = password,
                    imageCaptcha = imageCaptcha,
                    smsCode = smsCode,
                    remember = rememberCredential,
                    phase = uiState.phase,
                    onModeChange = { mode = it },
                    onAccountChange = { account = it },
                    onPasswordChange = { password = it },
                    onImageCaptchaChange = { imageCaptcha = it },
                    onSmsCodeChange = { smsCode = it },
                    onRememberChange = { rememberCredential = it },
                )
            }

            // 不可见的桥：真正跑完学校登录、把网关票发下来
            if (uiState.phase == WebVpnLoginPhase.LOADING ||
                uiState.phase == WebVpnLoginPhase.SUBMITTING
            ) {
                GatewayBridgeWebView(
                    reloadKey = webViewReloadKey,
                    probe = probe,
                    lastFinishedUrl = lastFinishedUrl,
                    onError = { msg ->
                        pendingSubmit = false
                        uiState = uiState.copy(
                            phase = WebVpnLoginPhase.FAILED,
                            errorText = msg,
                        )
                    },
                )
            }

            // 状态/错误行
            if (uiState.errorText != null) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = uiState.errorText.orEmpty(),
                    color = Color(0xFFB3455E),
                    fontSize = (9f * uiScale).sp,
                    lineHeight = (12f * uiScale).sp,
                )
            } else if (uiState.phase == WebVpnLoginPhase.SUBMITTING) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = "正在把网关票据交给服务器保管…",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (9f * uiScale).sp,
                )
            } else if (uiState.phase == WebVpnLoginPhase.LOADING) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = "正在通过学校统一身份认证登录…",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (9f * uiScale).sp,
                )
            }

            Spacer(Modifier.height(s(10.dp)))

            // 底部按钮
            when (uiState.phase) {
                WebVpnLoginPhase.IDLE -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(s(8.dp)),
                ) {
                    SecondaryButton(
                        label = "不同意",
                        uiScale = uiScale,
                        modifier = Modifier.weight(1f),
                        onClick = { WebVpnDialogScheduler.decline() },
                    )
                    SignInButton(
                        label = "登 录",
                        uiScale = uiScale,
                        enabled = canSubmit(mode, account, password, imageCaptcha, smsCode),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            probe.guestTicket = readTicket()
                            pendingSubmit = true
                            uiState = uiState.copy(
                                phase = WebVpnLoginPhase.LOADING,
                                errorText = null,
                            )
                        },
                    )
                }

                WebVpnLoginPhase.LOADING, WebVpnLoginPhase.SUBMITTING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(s(8.dp)),
                ) {
                    SecondaryButton(
                        label = "取消",
                        uiScale = uiScale,
                        modifier = Modifier.weight(1f),
                        onClick = { WebVpnDialogScheduler.dismiss() },
                    )
                    SignInButton(
                        label = "处理中…",
                        uiScale = uiScale,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    )
                }

                WebVpnLoginPhase.FAILED -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(s(8.dp)),
                ) {
                    SecondaryButton(
                        label = "取消",
                        uiScale = uiScale,
                        modifier = Modifier.weight(1f),
                        onClick = { WebVpnDialogScheduler.dismiss() },
                    )
                    SignInButton(
                        label = "重试",
                        uiScale = uiScale,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            webViewReloadKey++
                            pendingSubmit = true
                            uiState = uiState.copy(
                                phase = WebVpnLoginPhase.LOADING,
                                errorText = null,
                            )
                        },
                    )
                }

                WebVpnLoginPhase.DONE -> Unit
            }
        }
    }
}

/** 该方式下是否填够了必要字段。 */
private fun canSubmit(
    mode: WebVpnSignInDefaults.Mode,
    account: String,
    password: String,
    imageCaptcha: String,
    smsCode: String,
): Boolean = when (mode) {
    WebVpnSignInDefaults.Mode.PASSWORD ->
        WebVpnAutofill.canAutofill(account, password) &&
            WebVpnAutofill.isCaptchaValid(imageCaptcha)

    WebVpnSignInDefaults.Mode.PHONE ->
        WebVpnAutofill.isAccountValid(account) &&
            WebVpnAutofill.isCaptchaValid(imageCaptcha) &&
            smsCode.trim().length >= 4

    // 微信扫码：不需要表单输入，由桥接页面渲染二维码
    WebVpnSignInDefaults.Mode.WECHAT -> true
}

/**
 * 不可见的桥接 WebView。
 *
 * 用户看不到它：它是 `0.dp` 尺寸。它的唯一职责是真实加载学校登录页、
 * 让网关把 `wengine_vpn_ticket` 下发到 CookieManager，供 [readTicket] 取用。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GatewayBridgeWebView(
    reloadKey: Int,
    probe: WebVpnLoginProbe,
    lastFinishedUrl: androidx.compose.runtime.MutableState<String?>,
    onError: (String) -> Unit,
) {
    AndroidView(
        modifier = Modifier.size(0.dp),
        factory = { ctx ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                probe.webView = this
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
                        if (!probe.started) {
                            probe.started = true
                            probe.guestTicket = try {
                                WebVpnTicket.extract(
                                    CookieManager.getInstance().getCookie(WebVpnGateway.ORIGIN),
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        lastFinishedUrl.value = url
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            onError("无法连接学校网关，请检查网络后重试")
                        }
                    }
                }
                loadUrl(WebVpnGateway.LOGIN_URL)
            }
        },
        update = { webView ->
            if (webView.getTag(RELOAD_TAG_KEY) != reloadKey) {
                webView.setTag(RELOAD_TAG_KEY, reloadKey)
                if (reloadKey > 0) webView.loadUrl(WebVpnGateway.LOGIN_URL)
            }
        },
    )
}

/** WebView 检测过程的宿主对象。 */
private class WebVpnLoginProbe {
    var started: Boolean = false
    var guestTicket: String? = null
    var submitted: Boolean = false
    var webView: WebView? = null
}

private fun subtitleOf(trigger: WebVpnTrigger): String = when (trigger) {
    WebVpnTrigger.SIGNED_IN -> "登录成功 · 连接网关后校外也能刷新课表"
    WebVpnTrigger.TICKET_MISSING -> "尚未连接学校 WebVPN · 请登录后继续"
    WebVpnTrigger.TICKET_EXPIRED -> "WebVPN 登录已过期 · 请重新登录"
    WebVpnTrigger.MANUAL -> "重新登录学校 WebVPN 网关"
}

private const val SCRIM_ALPHA = 0.55f
private const val CARD_HEIGHT_DP = 430f
private val RELOAD_TAG_KEY = "com.nongyu.appplus.webvpn.RELOAD_KEY".hashCode()
