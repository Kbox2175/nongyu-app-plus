package com.nongyu.appplus.webvpn.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nongyu.appplus.design.NongyuTheme
import com.nongyu.appplus.login.ui.FormField
import com.nongyu.appplus.webvpn.logic.WebVpnSignInDefaults
import com.nongyu.appplus.webvpn.model.WebVpnLoginPhase

/**
 * 弹窗主体：**我们自己的登录页**，结构与学校统一身份认证页一一对应。
 *
 * ## 三种方式（维护者要求「和登录页面一样」）
 *
 * 实测学校页面顶部就是两个 tab（短信登录 / 密码登录）+ 首屏微信扫码，
 * 所以这里给出三种：[Mode.PASSWORD] / [Mode.PHONE] / [Mode.WECHAT]。
 *
 * | 方式 | 字段 |
 * | --- | --- |
 * | 密码登录 | 工号/学号 + 密码 + 图形验证码 |
 * | 短信登录 | 手机号 + 图形验证码 + 短信验证码 |
 * | 微信扫码 | 二维码 + 提示文案 |
 *
 * 字段全部复用登录页的 [FormField]，与主登录页同源，保证观感一致。
 */
@Composable
fun SignInPanel(
    uiScale: Float,
    mode: WebVpnSignInDefaults.Mode,
    account: String,
    password: String,
    imageCaptcha: String,
    smsCode: String,
    remember: Boolean,
    phase: WebVpnLoginPhase,
    onModeChange: (WebVpnSignInDefaults.Mode) -> Unit,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onImageCaptchaChange: (String) -> Unit,
    onSmsCodeChange: (String) -> Unit,
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
        // ---- 方式切换（对应学校页面的两个 tab + 微信扫码）----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WebVpnSignInDefaults.MODES.forEach { m ->
                ModeTab(
                    label = WebVpnSignInDefaults.labelOf(m),
                    uiScale = uiScale,
                    active = m == mode,
                    enabled = editable,
                    onClick = { onModeChange(m) },
                )
            }
        }

        Text(
            text = "欢迎使用川农e认证",
            color = colors.surface.colorText,
            fontSize = (15f * uiScale).sp,
            fontWeight = FontWeight.Bold,
        )

        when (mode) {
            WebVpnSignInDefaults.Mode.PASSWORD -> {
                FormField(
                    placeholder = WebVpnSignInDefaults.accountHintOf(mode),
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
                CaptchaField(
                    uiScale = uiScale,
                    value = imageCaptcha,
                    editable = editable,
                    label = "请输入验证码",
                    onValueChange = onImageCaptchaChange,
                )
            }

            WebVpnSignInDefaults.Mode.PHONE -> {
                FormField(
                    placeholder = WebVpnSignInDefaults.accountHintOf(mode),
                    colors = colors,
                    value = account,
                    onValueChange = { if (editable) onAccountChange(it) },
                )
                CaptchaField(
                    uiScale = uiScale,
                    value = imageCaptcha,
                    editable = editable,
                    label = "请输入验证码",
                    onValueChange = onImageCaptchaChange,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        FormField(
                            placeholder = "输入短信验证码",
                            colors = colors,
                            value = smsCode,
                            onValueChange = { if (editable) onSmsCodeChange(it) },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = editable) { }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "获取验证码",
                            color = colors.brand.colorBrand,
                            fontSize = (12f * uiScale).sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            WebVpnSignInDefaults.Mode.WECHAT -> {
                Text(
                    text = "打开微信APP 扫一扫",
                    color = colors.surface.colorTextSecondary,
                    fontSize = (12f * uiScale).sp,
                )
                // 二维码占位：真实二维码由后台桥接页面渲染，这里只给视觉位
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.brand.colorBrandMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "二维码",
                        color = colors.surface.colorTextSecondary,
                        fontSize = (11f * uiScale).sp,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = editable) { onRememberChange(!remember) }
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = if (remember) "☑ 记住账号密码" else "☐ 记住账号密码",
                color = if (remember) colors.brand.colorBrand else colors.surface.colorTextSecondary,
                fontSize = (12f * uiScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 方式 tab：与学校页面顶部 tab 同一观感（选中态下划线与品牌色）。 */
@Composable
private fun ModeTab(
    label: String,
    uiScale: Float,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = NongyuTheme.colors
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = if (active) colors.brand.colorBrand else colors.surface.colorTextSecondary,
            fontSize = (13f * uiScale).sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width((26f * uiScale).dp)
                .height((2f * uiScale).dp)
                .background(
                    if (active) colors.brand.colorBrand else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )
    }
}

/**
 * 图形验证码输入 + 图片位。
 *
 * 实测学校页面这个位置就是一张 `<img>`；原生侧从
 * `GET auth.sicau.edu.cn/api/v1/captcha` 拿到加密 data 后解密得到
 * `data:image/png;base64,...`（[com.nongyu.appplus.login.data.cas.CaptchaImageDecoder] 已能解码）。
 */
@Composable
private fun CaptchaField(
    uiScale: Float,
    value: String,
    editable: Boolean,
    label: String,
    onValueChange: (String) -> Unit,
) {
    val colors = NongyuTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            FormField(
                placeholder = label,
                colors = colors,
                value = value,
                onValueChange = { if (editable) onValueChange(it) },
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(width = 100.dp, height = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.brand.colorBrandMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "验证码",
                color = colors.surface.colorTextSecondary,
                fontSize = (12f * uiScale).sp,
            )
        }
    }
}

/** 右上角 X 关闭（与设置面板右上角 CloseCross 同一套观感：两条 45° 短线）。 */
@Composable
fun CloseCrossButton(uiScale: Float, onClick: () -> Unit) {
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

/** 卡片主按钮（品牌绿），与登录页 FormButton 同族。 */
@Composable
fun SignInButton(
    label: String,
    uiScale: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = NongyuTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape((10f * uiScale).dp))
            .background(
                if (enabled) colors.brand.colorBrand
                else colors.surface.colorText.copy(alpha = 0.12f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = (11f * uiScale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) colors.brand.colorOnBrand else colors.surface.colorTextSecondary,
            fontSize = (15f * uiScale).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** 次要按钮（描边/浅底），用于「不同意」。 */
@Composable
fun SecondaryButton(
    label: String,
    uiScale: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = NongyuTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape((10f * uiScale).dp))
            .background(colors.surface.colorBackground)
            .clickable(onClick = onClick)
            .padding(vertical = (11f * uiScale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.surface.colorTextSecondary,
            fontSize = (14f * uiScale).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
