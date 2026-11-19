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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nongyu.appplus.design.NongyuTheme
import com.nongyu.appplus.login.ui.FormField
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
import com.nongyu.appplus.webvpn.model.WebVpnTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学校 WebVPN 登录子窗体（**根节点最顶层浮层**）。
 *
 * ## 层级
 *
 * 本 Composable 由 [WebVpnDialogHost] 挂在 `NongyuApp` 根 `Box` 的**最后一个子节点**，
 * 因此盖住主界面、登录中心（含圆形揭示转场）、设置面板、星云球与时间条遮罩。
 * 它不是 `androidx.compose.ui.window.Dialog()`：那样会开独立 window 但挂在调用的
 * Composition 局部，无法保证「高于一切页面内浮层」，也无法在登录转场期间保持稳定。
 *
 * ## 风格
 *
 * 与 `JumpToDateDialog` / `FormButton` 同族：`NongyuTheme.colors` 取色、`uiScale`
 * 自适应、白卡片 + 圆角、品牌绿主按钮、模态遮板。不写死任何十六进制色值。
 *
 * ## 登录流程
 *
 * 1. [WebVpnLoginPhase.IDLE]：说明 + 「同意登录」/「不同意」双按钮（知情同意）；
 * 2. 同意后内嵌 [WebView] 加载 [WebVpnGateway.LOGIN_URL]，用户**在学校页面里**输入账号；
 *    App 不读取账号密码，只在页面跳转后用 [CookieManager] 读网关票；
 * 3. 检测到票 → [WebVpnSessionRepository.submit] 交后端 `POST /school/webvpn-session`；
 * 4. 成功 → [WebVpnDialogScheduler.complete] 关闭；失败保留 WebView 供重试。
 *
 * ## 「不同意」永远可达
 *
 * 卡片右上角常驻「不同意」按钮，任何阶段都能点 —— 点了会写会话级拒绝偏好，
 * 抑制后续自动调度（手动入口除外）。返回键 / 点遮板则是「暂不处理」，不改变同意态。
 *
 * ## ⚠️ WebView 行为的待实测边界
 *
 * 学校 CAS 登录页 `/wui/` 的 SPA 在 Android WebView 内的行为仓内无一手实测
 * （见 ADR-0010）。因此除了「离开登录页 + 票值相对首帧基线变化」的自动判定，
 * 还常驻一个「我已完成登录」按钮作为兜底：用户点它即读当前 Cookie 交票。
 * 匿名游客票与已认证票同形态，最终有效性由后端代取结果裁决。
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
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp

    var uiState by remember(request.nonce) { mutableStateOf(WebVpnDialogUiState()) }
    // WebView 检测用的可变宿主：普通对象 + remember，避免 WebViewClient 捕获过期值。
    val probe = remember(request.nonce) { WebVpnLoginProbe() }
    val lastFinishedUrl = remember(request.nonce) { mutableStateOf<String?>(null) }
    var webViewReloadKey by remember(request.nonce) { mutableIntStateOf(0) }

    // ---- 账号密码辅助填入（用户 2026-11-18 要求；持久保存走 Keystore+AES/GCM）----
    val credentialStore = remember { WebVpnCredentialStore(context.applicationContext) }
    var account by remember(request.nonce) { mutableStateOf("") }
    var password by remember(request.nonce) { mutableStateOf("") }
    var rememberCredential by remember(request.nonce) { mutableStateOf(true) }
    var savedCredentialLoaded by remember(request.nonce) { mutableStateOf(false) }
    // 已保存的账号密码：页面加载完后自动注入
    var pendingAutofill by remember(request.nonce) { mutableStateOf(false) }

    // 是否显示页面本身（需要用户看图形验证码时才显示）。
    //
    // 2026-11-19 实测：学校登录页是 React SPA、无 <form>，密码登录**必须**填页面内
    // 那张 <img> 图形验证码；空着点「登 录」页面原地不动。
    // 所以「完全不显示网页」在这里撞到了硬约束：验证码无法程序化获取。
    var showCaptchaPanel by remember(request.nonce) { mutableStateOf(false) }

    // 首次打开：把已保存的账号密码读进内存（解密在 IO 线程，避免卡首帧）
    LaunchedEffect(request.nonce) {
        val saved = withContext(Dispatchers.IO) { credentialStore.read() }
        if (saved != null && !savedCredentialLoaded) {
            account = saved.account
            password = saved.password
            pendingAutofill = true
        }
        savedCredentialLoaded = true
    }

    /** 读网关票：CookieManager 拿的是整条 Cookie 头，形态校验交纯逻辑层。 */
    fun readTicket(): String? = try {
        WebVpnTicket.extract(
            CookieManager.getInstance().getCookie(WebVpnGateway.ORIGIN),
        )
    } catch (e: Exception) {
        null
    }

    /** 提交票：票值只在这个调用链里流动，绝不写日志 / 不进错误信息。 */
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
                    // 登录成功才落盘账号密码：只有这时才证明这组凭据真的有效。
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
                    uiState = uiState.copy(
                        phase = WebVpnLoginPhase.FAILED,
                        errorText = result.message,
                    )
                }
            }
        }
    }

    // 页面跳转后的自动判定：离开登录页 且 票值相对首帧游客票发生变化 → 认为已认证。
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

    /** 把账号密码注入后台 WebView 并**点击登录**（脚本按实测 DOM 定位）。 */
    fun autofillAndSubmit(webView: WebView?) {
        if (webView == null) return
        if (!WebVpnAutofill.canAutofill(account, password)) return
        webView.evaluateJavascript(
            WebVpnAutofill.buildSignInScript(account.trim(), password, captcha = ""),
            null,
        )
    }

    // 登录页每次加载完成，若有待提交的账号密码就填+提交一次
    LaunchedEffect(lastFinishedUrl.value, pendingAutofill) {
        if (!pendingAutofill) return@LaunchedEffect
        val url = lastFinishedUrl.value ?: return@LaunchedEffect
        if (WebVpnTicket.isStillOnLoginPage(url)) {
            autofillAndSubmit(probe.webView)
            pendingAutofill = false
        }
    }

    // 「不同意」优先于返回键：任何阶段都有明确出口。
    BackHandler(enabled = true) { WebVpnDialogScheduler.dismiss() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ---- 模态遮板：点空白 = 暂不处理（不改同意态）----
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

        // ---- 卡片 ----
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = s(22.dp))
                .height(s(CARD_HEIGHT_DP.dp))
                .clip(RoundedCornerShape(s(20.dp)))
                .background(colors.surface.colorCard)
                // 卡片内部消费点击，避免穿透到遮板
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(s(16.dp)),
        ) {
            // 标题行：右上角 X 关闭
            //
            // 维护者 2026-11-18 要求把「不同意」换成 X：X 是**中性的关闭**——
            // 只关窗、不表达拒绝，所以自动调度下次仍会再来（等同 dismiss）。
            // 「明确不同意登录」的语义移到卡片底部的「不同意」按钮上，两者不混用。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "登录学校 WebVPN",
                    color = colors.surface.colorText,
                    fontSize = (15f * uiScale).sp,
                    lineHeight = (19f * uiScale).sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                CloseCrossButton(uiScale = uiScale) { WebVpnDialogScheduler.dismiss() }
            }

            Spacer(Modifier.height(s(6.dp)))
            Text(
                text = subtitleOf(request.trigger),
                color = colors.brand.colorBrand,
                fontSize = (9f * uiScale).sp,
                lineHeight = (12f * uiScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(s(4.dp)))
            Text(
                text = PRIVACY_NOTE,
                color = colors.surface.colorTextSecondary.copy(alpha = 0.85f),
                fontSize = (8f * uiScale).sp,
                lineHeight = (11f * uiScale).sp,
            )
            Spacer(Modifier.height(s(10.dp)))

            // ---- 主体：原生表单 / 图形验证码区 ----
            //
            // 2026-11-19 实测学校登录页后修正：**图形验证码是硬阻塞**。
            // 页面是 React SPA（无 form），密码登录必须填那张页面内 `<img>` 的验证码，
            // 空着点「登 录」页面原地不动。所以一旦需要验证码，
            // 必须把 WebView **显示出来**让用户看图填字 —— 这是不可绕过的。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(s(10.dp)))
                    .background(colors.surface.colorBackground),
            ) {
                if (uiState.phase != WebVpnLoginPhase.DONE) {
                    // 需要验证码时显示页面，否则显示原生表单
                    if (showCaptchaPanel) {
                        GatewayWebView(
                            reloadKey = webViewReloadKey,
                            probe = probe,
                            lastFinishedUrl = lastFinishedUrl,
                            onError = { msg ->
                                uiState = uiState.copy(
                                    phase = WebVpnLoginPhase.FAILED,
                                    errorText = msg,
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        SignInForm(
                            uiScale = uiScale,
                            account = account,
                            password = password,
                            remember = rememberCredential,
                            phase = uiState.phase,
                            onAccountChange = { account = it },
                            onPasswordChange = { password = it },
                            onRememberChange = { rememberCredential = it },
                        )
                    }
                }
            }

            // ---- 后台 WebView（不可见，仅用于产生网关票与读票）----
            //
            // 需要验证码时上面已经把它显示出来了（同一个实例不能声明两次），
            // 所以这里只在「不显示」的阶段挂一个 0 尺寸载体。
            if (!showCaptchaPanel &&
                (uiState.phase == WebVpnLoginPhase.LOADING ||
                    uiState.phase == WebVpnLoginPhase.SUBMITTING)
            ) {
                GatewayWebView(
                    reloadKey = webViewReloadKey,
                    probe = probe,
                    lastFinishedUrl = lastFinishedUrl,
                    onError = { msg ->
                        uiState = uiState.copy(
                            phase = WebVpnLoginPhase.FAILED,
                            errorText = msg,
                        )
                    },
                    modifier = Modifier.size(0.dp),
                )
            }

            // ---- 状态行 ----
            if (uiState.errorText != null) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = uiState.errorText.orEmpty(),
                    color = Color(0xFFB3455E),
                    fontSize = (8f * uiScale).sp,
                    lineHeight = (11f * uiScale).sp,
                )
            } else if (uiState.phase == WebVpnLoginPhase.SUBMITTING) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = "正在把网关票据交给服务器保管…",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (8f * uiScale).sp,
                )
            } else if (uiState.phase == WebVpnLoginPhase.LOADING && !uiState.leftLoginPage) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = "正在连接学校网关…",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (8f * uiScale).sp,
                )
            }

            Spacer(Modifier.height(s(10.dp)))
            ActionRow(
                uiScale = uiScale,
                phase = uiState.phase,
                canSubmit = WebVpnAutofill.canAutofill(account, password),
                onSubmit = {
                    // 实测：密码登录必须填页面内的图形验证码 → 必须让用户看到页面。
                    // 先把账号密码记下，显示页面，用户填完验证码后再点「提交登录」。
                    probe.guestTicket = readTicket()
                    showCaptchaPanel = true
                    uiState = uiState.copy(phase = WebVpnLoginPhase.LOADING, errorText = null)
                    pendingAutofill = true
                },
                onDecline = { WebVpnDialogScheduler.decline() },
                onCancel = { WebVpnDialogScheduler.dismiss() },
                onResubmit = {
                    pendingAutofill = true
                    uiState = uiState.copy(errorText = null)
                },
                onRetry = {
                    webViewReloadKey++
                    pendingAutofill = true
                    uiState = uiState.copy(
                        phase = WebVpnLoginPhase.LOADING,
                        errorText = null,
                    )
                },
            )
        }
    }
}

/**
 * 弹窗主体：**模仿学校统一身份认证登录页**的表单。
 *
 * 维护者 2026-11-18：「能不能做成和登录页一样的？」——所以这里直接复用登录中心
 * 的 [FormField] 组件，不另画一套，主题/尺寸调整时天然同步。
 *
 * ## 为什么不出图形验证码
 *
 * 学校统一身份认证的图形验证码必须是**网关会话里那一张**（一次性、绑 uuid），
 * 由后台 WebView 里的页面自己取用；原生侧再画一张不仅无意义，还会误导用户去填。
 */
@Composable
private fun SignInForm(
    uiScale: Float,
    account: String,
    password: String,
    remember: Boolean,
    phase: WebVpnLoginPhase,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRememberChange: (Boolean) -> Unit,
) {
    val colors = NongyuTheme.colors
    val editable = phase == WebVpnLoginPhase.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "统一身份认证",
            color = colors.surface.colorText,
            fontSize = (15f * uiScale).sp,
            fontWeight = FontWeight.Bold,
        )

        FormField(
            placeholder = "请输入学号",
            colors = colors,
            value = account,
            onValueChange = { if (editable) onAccountChange(it) },
        )
        FormField(
            placeholder = "请输入密码",
            colors = colors,
            isPassword = true,
            value = password,
            onValueChange = { if (editable) onPasswordChange(it) },
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = editable) { onRememberChange(!remember) }
                .padding(vertical = 2.dp, horizontal = 2.dp),
        ) {
            Text(
                text = if (remember) "☑ 记住账号密码" else "☐ 记住账号密码",
                color = if (remember) colors.brand.colorBrand else colors.surface.colorTextSecondary,
                fontSize = (12f * uiScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = "登录后农屿会自动连接学校网关，用于校外读取课表。密码仅加密保存在本机。",
            color = colors.surface.colorTextSecondary.copy(alpha = 0.75f),
            fontSize = (10f * uiScale).sp,
            lineHeight = (14f * uiScale).sp,
        )
    }
}

/** 右上角 X 关闭（与设置面板右上角 CloseCross 同一套观感：两条 45° 短线）。 */
@Composable
private fun CloseCrossButton(uiScale: Float, onClick: () -> Unit) {
    val colors = NongyuTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape((8f * uiScale).dp))
            .clickable(onClick = onClick)
            // 命中区放大到 ~28dp，视觉叉仍是小尺寸
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
                end = Offset(this.size.width - inset, this.size.height - inset),
                strokeWidth = inset,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.surface.colorTextSecondary,
                start = Offset(this.size.width - inset, inset),
                end = Offset(inset, this.size.height - inset),
                strokeWidth = inset,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 承载学校网关登录页的 WebView。
 *
 * - JS / DOM Storage 必须开（CAS 登录页是 `/wui/` SPA）；
 * - 允许第三方 Cookie（CAS 跨域回投），否则拿不到 `wengine_vpn_ticket*`；
 * - `factory` 只执行一次，`lastFinishedUrl` 是 [androidx.compose.runtime.MutableState]
 *   对象引用，WebViewClient 写它的 `.value` 能被 Compose 侧观察到，不存在过期捕获。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GatewayWebView(
    reloadKey: Int,
    probe: WebVpnLoginProbe,
    lastFinishedUrl: androidx.compose.runtime.MutableState<String?>,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier.size(0.dp),
) {
    AndroidView(
        // 默认 **0 尺寸**：用户看不到页面。需要用户看图填验证码时，
        // 调用方传 `Modifier.fillMaxSize()` 把它显示出来（实测验证码无法程序化获取）。
        modifier = modifier,
        factory = { ctx ->
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }
            WebView(ctx).apply {
                probe.webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    // 用 API 24+ 的现代重载（旧 String 版已废弃）：让 WebView 自己
                    // 跟随 302，CAS 回投才完整，不要在这里拦截。
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = false

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        if (!probe.started) {
                            probe.started = true
                            // 首帧基线：匿名访问时网关也会下发游客票，记下来用于比对
                            probe.guestTicket = try {
                                WebVpnTicket.extract(
                                    CookieManager.getInstance()
                                        .getCookie(WebVpnGateway.ORIGIN),
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        lastFinishedUrl.value = url
                    }

                    // 只报「主文档加载失败」：子资源（图片/脚本）失败不该弹错误提示，
                    // 否则 CAS 页面一点点资源 404 就会误报「网关打不开」。
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            onError("网关页面加载失败，请检查网络后重试")
                        }
                    }
                }
                loadUrl(WebVpnGateway.LOGIN_URL)
            }
        },
        update = { webView ->
            // reloadKey 变化时（用户点「重试」）重新加载登录页；常规重组不重载。
            if (webView.getTag(RELOAD_TAG_KEY) != reloadKey) {
                webView.setTag(RELOAD_TAG_KEY, reloadKey)
                if (reloadKey > 0) webView.loadUrl(WebVpnGateway.LOGIN_URL)
            }
        },
    )
}

/**
 * 底部动作行：与真实登录页一样以「登录」为主按钮，随状态切换。
 *
 * - `IDLE`：不同意 / **登录**（表单可编辑时）；
 * - `LOADING`：取消 / 正在登录…（后台 WebView 正在自动提交）；
 * - `SUBMITTING`：取消 / 提交票据中…；
 * - `FAILED`：取消 / 重新登录。
 */
@Composable
private fun ActionRow(
    uiScale: Float,
    phase: WebVpnLoginPhase,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onResubmit: () -> Unit,
    onRetry: () -> Unit,
) {
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp
    when (phase) {
        WebVpnLoginPhase.IDLE -> {
            Row(horizontalArrangement = Arrangement.spacedBy(s(8.dp))) {
                CardButton("不同意", uiScale, Modifier.weight(1f), false, onDecline)
                CardButton(
                    "登录",
                    uiScale,
                    Modifier.weight(1f),
                    true,
                    onSubmit,
                    enabled = canSubmit,
                )
            }
        }

        WebVpnLoginPhase.LOADING -> {
            Row(horizontalArrangement = Arrangement.spacedBy(s(8.dp))) {
                CardButton("取消", uiScale, Modifier.weight(1f), false, onCancel)
                CardButton("正在登录…", uiScale, Modifier.weight(1f), true, {}, enabled = false)
            }
        }

        WebVpnLoginPhase.SUBMITTING -> {
            Row(horizontalArrangement = Arrangement.spacedBy(s(8.dp))) {
                CardButton("取消", uiScale, Modifier.weight(1f), false, onCancel)
                CardButton("提交中…", uiScale, Modifier.weight(1f), true, {}, enabled = false)
            }
        }

        WebVpnLoginPhase.FAILED -> {
            Row(horizontalArrangement = Arrangement.spacedBy(s(8.dp))) {
                CardButton("取消", uiScale, Modifier.weight(1f), false, onCancel)
                CardButton("重新登录", uiScale, Modifier.weight(1f), true, onResubmit)
            }
        }

        WebVpnLoginPhase.DONE -> Unit
    }
}

@Composable
private fun CardButton(
    label: String,
    uiScale: Float,
    modifier: Modifier,
    primary: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = NongyuTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape((9f * uiScale).dp))
            .background(
                if (primary) colors.brand.colorBrand else colors.surface.colorBackground,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = (8f * uiScale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) colors.brand.colorOnBrand else colors.surface.colorTextSecondary,
            fontSize = (9.5f * uiScale).sp,
            lineHeight = (12f * uiScale).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** WebView 检测过程的宿主对象（普通类，字段在回调里改，Compose 侧读同一个实例）。 */
private class WebVpnLoginProbe {
    var started: Boolean = false
    var guestTicket: String? = null
    var submitted: Boolean = false
    /** 当前 WebView 实例引用：供自动填入调用 `evaluateJavascript`。 */
    var webView: WebView? = null
}

private fun subtitleOf(trigger: WebVpnTrigger): String = when (trigger) {
    WebVpnTrigger.SIGNED_IN -> "登录成功 · 连接网关后校外也能刷新课表"
    WebVpnTrigger.TICKET_MISSING -> "尚未连接学校 WebVPN · 请登录后继续"
    WebVpnTrigger.TICKET_EXPIRED -> "WebVPN 登录已过期 · 请重新登录网关"
    WebVpnTrigger.MANUAL -> "重新登录学校 WebVPN 网关"
}

private const val PRIVACY_NOTE =
    "账号密码仅在你手机上的学校页面输入，农屿不读取；网关票据加密交由服务器保管，仅用于代取课表。"

private val EXPLAINER_LINES = listOf(
    "· 农屿需要通过学校 WebVPN 网关读取课表等校内数据。",
    "· 登录后会在本页打开学校统一身份认证页面，由你本人输入账号密码。",
    "· 登录产生的网关票据会交给农屿服务器加密保管，用于校外代取页面；服务端不会回传票据。",
    "· 你可以选择「不同意」：本次使用不再自动提示，课表仍可查看已缓存的内容。",
)

private const val SCRIM_ALPHA = 0.55f
private const val CARD_HEIGHT_DP = 430f
private val RELOAD_TAG_KEY = "com.nongyu.appplus.webvpn.RELOAD_KEY".hashCode()
