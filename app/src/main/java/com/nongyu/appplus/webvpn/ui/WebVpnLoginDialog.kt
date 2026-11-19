package com.nongyu.appplus.webvpn.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.nongyu.appplus.design.NongyuTheme
import com.nongyu.appplus.login.data.cas.CasCaptcha
import com.nongyu.appplus.webvpn.data.PendingCampusCredential
import com.nongyu.appplus.webvpn.data.WebVpnCredentialStore
import com.nongyu.appplus.webvpn.data.WebVpnDialogScheduler
import com.nongyu.appplus.webvpn.data.WebVpnGatewayLogin
import com.nongyu.appplus.webvpn.data.WebVpnSessionRepository
import com.nongyu.appplus.webvpn.logic.WebVpnAutofill
import com.nongyu.appplus.webvpn.logic.WebVpnSignInDefaults
import com.nongyu.appplus.webvpn.model.WebVpnDialogUiState
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
 * ## 它是什么
 *
 * 这就是**农屿自己的登录页**：原生 Compose，三种方式与学校页面一致
 * （密码登录 / 短信登录 / 微信扫码）。学校那个页面**完全不出现、也不需要**
 * —— 换票走纯 HTTP：
 *
 * ```
 * captcha → sms/send → cas/loginByPhone → webvpn…/login?ticket=<st> → 拿到 wengine_vpn_ticket
 * ```
 *
 * 见 [WebVpnGatewayLogin] 的类注释（含 2026-11-19 实测证据）。
 *
 * ## 凭据记忆（维护者口径）
 *
 * | 方式 | 记住什么 |
 * | --- | --- |
 * | 密码登录 | 账号 + 密码 |
 * | 短信登录 | **只记手机号** |
 * | 微信扫码 | **什么都不记** |
 *
 * 取不到时用默认方式（密码登录）。见 [WebVpnSignInDefaults.resolve]。
 */
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
    val gateway = remember { WebVpnGatewayLogin() }
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp

    var uiState by remember(request.nonce) { mutableStateOf(WebVpnDialogUiState()) }

    var mode by remember(request.nonce) { mutableStateOf(WebVpnSignInDefaults.DEFAULT_MODE) }
    var account by remember(request.nonce) { mutableStateOf("") }
    var password by remember(request.nonce) { mutableStateOf("") }
    var imageCaptcha by remember(request.nonce) { mutableStateOf("") }
    var smsCode by remember(request.nonce) { mutableStateOf("") }
    var rememberCredential by remember(request.nonce) { mutableStateOf(true) }

    // 图形验证码：uuid 必须与用户看到的这张图配对（一次性）
    var captchaUuid by remember(request.nonce) { mutableStateOf<String?>(null) }
    var captchaImage by remember(request.nonce) { mutableStateOf<String?>(null) }
    var captchaLoading by remember(request.nonce) { mutableStateOf(false) }

    /** 取一张新的图形验证码。 */
    fun refreshCaptcha() {
        if (captchaLoading) return
        captchaLoading = true
        scope.launch {
            val c = gateway.fetchCaptcha()
            captchaUuid = c?.uuid
            captchaImage = c?.dataUri
            captchaLoading = false
        }
    }

    // 打开时：决定默认方式 + 预填
    LaunchedEffect(request.nonce) {
        val initial = WebVpnSignInDefaults.resolve(PendingCampusCredential.take())
        mode = initial.mode
        if (initial.account.isNotEmpty()) account = initial.account
        if (initial.password.isNotEmpty()) password = initial.password

        if (!initial.prefilled) {
            val saved = withContext(Dispatchers.IO) { credentialStore.read() }
            if (saved != null) {
                if (account.isEmpty()) account = saved.account
                if (password.isEmpty()) password = saved.password
            }
        }
        // 需要图形验证码的方式，打开即取一张
        if (WebVpnSignInDefaults.needsImageCaptcha(mode)) refreshCaptcha()
    }

    // 切到需要验证码的方式时，若还没有码就取一张
    LaunchedEffect(mode) {
        if (WebVpnSignInDefaults.needsImageCaptcha(mode) && captchaUuid == null) {
            refreshCaptcha()
        }
    }

    /** 按方式记忆：账密记账号密码；短信只记手机号；扫码什么都不记。 */
    fun saveCredentialIfNeeded() {
        if (!rememberCredential) return
        when (mode) {
            WebVpnSignInDefaults.Mode.PASSWORD -> {
                if (WebVpnAutofill.canAutofill(account, password)) {
                    scope.launch(Dispatchers.IO) {
                        credentialStore.save(account.trim(), password)
                    }
                }
            }

            WebVpnSignInDefaults.Mode.PHONE -> {
                // 只记手机号：复用同一存储，但密码位留空
                if (WebVpnAutofill.isAccountValid(account)) {
                    scope.launch(Dispatchers.IO) {
                        credentialStore.savePhoneOnly(account.trim())
                    }
                }
            }

            // 微信扫码：什么都不记
            WebVpnSignInDefaults.Mode.WECHAT -> Unit
        }
    }

    /** 换到票后提交给后端。 */
    fun submitTicket(ticket: String) {
        uiState = uiState.copy(phase = WebVpnLoginPhase.SUBMITTING, errorText = null)
        scope.launch {
            when (val result = repository.submit(ticket)) {
                is WebVpnSubmitResult.Success -> {
                    saveCredentialIfNeeded()
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

    /** 点「登录」：按方式走对应链路。 */
    fun doLogin() {
        uiState = uiState.copy(phase = WebVpnLoginPhase.LOADING, errorText = null)
        scope.launch {
            val result = when (mode) {
                WebVpnSignInDefaults.Mode.PASSWORD -> {
                    val uuid = captchaUuid
                    if (uuid == null) {
                        uiState = uiState.copy(
                            phase = WebVpnLoginPhase.FAILED,
                            errorText = "验证码尚未加载，请稍后重试",
                        )
                        return@launch
                    }
                    gateway.loginByPassword(
                        account = account,
                        password = password,
                        captcha = CasCaptcha(uuid = uuid, image = captchaImage.orEmpty()),
                        captchaCode = imageCaptcha,
                    )
                }

                WebVpnSignInDefaults.Mode.PHONE ->
                    gateway.loginByPhone(phone = account, smsCode = smsCode)

                // 微信扫码：接口链路仓内已具备（getLoginQr + 轮询），本轮先明确提示未开放
                WebVpnSignInDefaults.Mode.WECHAT -> WebVpnGatewayLogin.Result.Failure(
                    "微信扫码登录暂未开放，请使用密码或短信登录",
                )
            }

            when (result) {
                is WebVpnGatewayLogin.Result.Success -> submitTicket(result.ticket)
                is WebVpnGatewayLogin.Result.Failure -> {
                    uiState = uiState.copy(
                        phase = WebVpnLoginPhase.FAILED,
                        errorText = result.message,
                    )
                    // 验证码一次性：失败后必须换一张，否则用户拿废码空跑
                    if (WebVpnSignInDefaults.needsImageCaptcha(mode)) refreshCaptcha()
                }
            }
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
                // 需求：连接网关后校外也能使用校园功能（不只是课表）
                text = subtitleOf(request.trigger),
                color = colors.brand.colorBrand,
                fontSize = (10f * uiScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(s(10.dp)))

            // 主体：三方式登录页（无 WebView）
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
                    captchaImage = captchaImage,
                    captchaLoading = captchaLoading,
                    onModeChange = { mode = it },
                    onAccountChange = { account = it },
                    onPasswordChange = { password = it },
                    onImageCaptchaChange = { imageCaptcha = it },
                    onSmsCodeChange = { smsCode = it },
                    onRememberChange = { rememberCredential = it },
                    onRefreshCaptcha = { refreshCaptcha() },
                    onSendSms = {
                        val uuid = captchaUuid
                        if (uuid == null) {
                            uiState = uiState.copy(
                                phase = WebVpnLoginPhase.FAILED,
                                errorText = "请先填写图形验证码",
                            )
                        } else {
                            scope.launch {
                                when (
                                    val r = gateway.sendSmsCode(
                                        phone = account,
                                        imageCode = imageCaptcha,
                                        uuid = uuid,
                                    )
                                ) {
                                    is WebVpnGatewayLogin.Result.Success ->
                                        uiState = uiState.copy(
                                            phase = WebVpnLoginPhase.IDLE,
                                            errorText = "验证码已发送",
                                        )

                                    is WebVpnGatewayLogin.Result.Failure ->
                                        uiState = uiState.copy(
                                            phase = WebVpnLoginPhase.FAILED,
                                            errorText = r.message,
                                        )
                                }
                            }
                        }
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
            } else if (uiState.phase == WebVpnLoginPhase.LOADING) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = "正在登录学校统一身份认证…",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (9f * uiScale).sp,
                )
            } else if (uiState.phase == WebVpnLoginPhase.SUBMITTING) {
                Spacer(Modifier.height(s(6.dp)))
                Text(
                    text = "正在把网关票据交给服务器保管…",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (9f * uiScale).sp,
                )
            }

            Spacer(Modifier.height(s(10.dp)))

            when (uiState.phase) {
                WebVpnLoginPhase.IDLE, WebVpnLoginPhase.FAILED -> Row(
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
                        onClick = { doLogin() },
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

    WebVpnSignInDefaults.Mode.WECHAT -> false
}

private fun subtitleOf(trigger: WebVpnTrigger): String = when (trigger) {
    WebVpnTrigger.SIGNED_IN -> "连接网关后，校外也能使用校园功能"
    WebVpnTrigger.TICKET_MISSING -> "尚未连接学校 WebVPN · 连接后校外可用校园功能"
    WebVpnTrigger.TICKET_EXPIRED -> "WebVPN 登录已过期 · 重新连接后继续使用"
    WebVpnTrigger.MANUAL -> "重新连接学校 WebVPN 网关"
}

private const val SCRIM_ALPHA = 0.55f
private const val CARD_HEIGHT_DP = 430f
