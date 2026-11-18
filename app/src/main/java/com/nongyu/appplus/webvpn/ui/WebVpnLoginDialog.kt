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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nongyu.appplus.design.NongyuTheme
import com.nongyu.appplus.webvpn.data.WebVpnDialogScheduler
import com.nongyu.appplus.webvpn.data.WebVpnSessionRepository
import com.nongyu.appplus.webvpn.logic.WebVpnTicket
import com.nongyu.appplus.webvpn.model.WebVpnDialogUiState
import com.nongyu.appplus.webvpn.model.WebVpnGateway
import com.nongyu.appplus.webvpn.model.WebVpnLoginPhase
import com.nongyu.appplus.webvpn.model.WebVpnRequest
import com.nongyu.appplus.webvpn.model.WebVpnSubmitResult
import com.nongyu.appplus.webvpn.model.WebVpnTrigger
import kotlinx.coroutines.launch

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
            // 标题行：右上角「不同意」常驻
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
                Text(
                    text = "不同意",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (9f * uiScale).sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(s(8.dp)))
                        .clickable { WebVpnDialogScheduler.decline() }
                        .padding(horizontal = s(8.dp), vertical = s(5.dp)),
                )
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

            // ---- 主体：说明 / WebView ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(s(10.dp)))
                    .background(colors.surface.colorBackground),
            ) {
                when (uiState.phase) {
                    WebVpnLoginPhase.IDLE -> IdleExplainer(uiScale = uiScale)
                    WebVpnLoginPhase.DONE -> Unit
                    else -> GatewayWebView(
                        reloadKey = webViewReloadKey,
                        probe = probe,
                        lastFinishedUrl = lastFinishedUrl,
                        onError = { msg ->
                            uiState = uiState.copy(
                                phase = WebVpnLoginPhase.FAILED,
                                errorText = msg,
                            )
                        },
                    )
                }
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
                    text = "请在页面里完成学校统一身份认证登录",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (8f * uiScale).sp,
                )
            }

            Spacer(Modifier.height(s(10.dp)))
            ActionRow(
                uiScale = uiScale,
                phase = uiState.phase,
                onAgree = {
                    probe.guestTicket = readTicket()
                    uiState = uiState.copy(phase = WebVpnLoginPhase.LOADING, errorText = null)
                },
                onDecline = { WebVpnDialogScheduler.decline() },
                onCancel = { WebVpnDialogScheduler.dismiss() },
                onConfirmLogin = {
                    val ticket = readTicket()
                    if (ticket == null) {
                        uiState = uiState.copy(
                            phase = WebVpnLoginPhase.FAILED,
                            errorText = "尚未检测到网关票据，请在页面里完成登录后重试",
                        )
                    } else {
                        probe.submitted = true
                        submitTicket(ticket)
                    }
                },
                onRetry = {
                    webViewReloadKey++
                    uiState = uiState.copy(
                        phase = WebVpnLoginPhase.LOADING,
                        errorText = null,
                    )
                },
            )
        }
    }
}

/** 未同意前的说明区（纯文字，不加载任何页面 —— 不碰网络直到用户明确同意）。 */
@Composable
private fun IdleExplainer(uiScale: Float) {
    val colors = NongyuTheme.colors
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(s(12.dp)),
        verticalArrangement = Arrangement.spacedBy(s(7.dp)),
    ) {
        for (line in EXPLAINER_LINES) {
            Text(
                text = line,
                color = colors.surface.colorTextSecondary.copy(alpha = 0.9f),
                fontSize = (8.5f * uiScale).sp,
                lineHeight = (12f * uiScale).sp,
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
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }
            WebView(ctx).apply {
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

/** 底部动作行：按阶段切换，但「不同意 / 取消」始终有出口。 */
@Composable
private fun ActionRow(
    uiScale: Float,
    phase: WebVpnLoginPhase,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onConfirmLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp
    when (phase) {
        WebVpnLoginPhase.IDLE -> {
            Row(horizontalArrangement = Arrangement.spacedBy(s(8.dp))) {
                // 全用位置参数：Kotlin 不允许在命名参数之后再出现位置参数。
                CardButton("不同意", uiScale, Modifier.weight(1f), false, onDecline)
                CardButton("同意登录", uiScale, Modifier.weight(1f), true, onAgree)
            }
        }

        WebVpnLoginPhase.LOADING -> {
            Row(horizontalArrangement = Arrangement.spacedBy(s(8.dp))) {
                CardButton("取消", uiScale, Modifier.weight(1f), false, onCancel)
                CardButton("我已完成登录", uiScale, Modifier.weight(1f), true, onConfirmLogin)
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
                CardButton("重试", uiScale, Modifier.weight(1f), true, onRetry)
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
