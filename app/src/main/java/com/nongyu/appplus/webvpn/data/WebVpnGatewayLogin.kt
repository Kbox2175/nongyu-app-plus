package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.login.data.cas.CaptchaImageDecoder
import com.nongyu.appplus.login.data.cas.CasApiFactory
import com.nongyu.appplus.login.data.cas.CasCaptcha
import com.nongyu.appplus.login.data.cas.CasCaptchaResult
import com.nongyu.appplus.login.data.cas.CasLoginUseCase
import com.nongyu.appplus.login.data.cas.CasSignInStResult
import com.nongyu.appplus.login.data.cas.CasSmsSendResult
import com.nongyu.appplus.webvpn.logic.WebVpnGatewayExchange
import com.nongyu.appplus.webvpn.model.WebVpnGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 用**纯 HTTP** 换取 WebVPN 网关票 —— **完全不使用 WebView**。
 *
 * ## 实测链路（2026-11-19 浏览器逐条抓取，非推测）
 *
 * ```
 * ① GET  auth.sicau.edu.cn/api/v1/captcha?appid=<门户UUID>   取图形验证码
 * ② POST auth.sicau.edu.cn/api/v1/sms/send {uuid,code,type:"login",phone}   发短信码
 * ③ POST auth.sicau.edu.cn/api/v1/cas/loginByPhone {code,phone,appid}
 *          解密 data → {st, callbackUrl, users:[{id}]}
 * ④ GET  webvpn.sicau.edu.cn/login?cas_login=true&ticket=<st>&userId=<id>
 *          → Set-Cookie: wengine_vpn_ticketwebvpn_sicau_edu_cn=<token>
 * ```
 *
 * 第 ④ 步就是**网关**换票入口（不是 auth）。实测该 URL 返回
 * 「WebVPN资源访问控制系统登录页面」，路径里带 `vpn-12-o2-auth.sicau.edu.cn`。
 *
 * ## 为什么 WebView 可以彻底去掉
 *
 * 四步全是普通 HTTP 请求，没有任何一步依赖浏览器 JS 执行或页面渲染。
 * 之前用 WebView 是为了「真实跑完登录 SPA」，但既然服务端接口能直接调通，
 * 就没有必要再造一个浏览器环境。
 *
 * ## 复用而不是重写
 *
 * ①②③ 复用仓内 [CasLoginUseCase]（含图形验证码解密、短信发送、st 解析），
 * 只在它之上补 ④。这样避免第二套 AES 解密 / 信封解析逻辑。
 *
 * ## 票值安全
 *
 * - 票只在内存里流动：换出后立即交给 [WebVpnSessionRepository] 提交后端；
 * - 不打印、不落盘、不进错误信息或埋点；
 * - 失败信息只带 HTTP 状态码或用户可读文案。
 */
class WebVpnGatewayLogin(
    /** WebVPN 用的是**门户** appid（不是教务 appid）。 */
    private val portalAppId: String = PORTAL_APPID,
    private val casBaseUrl: String = CasApiFactory.REAL_BASE_URL,
    /** 仅测试注入；默认真实网关。 */
    private val gatewayOrigin: String = WebVpnGateway.ORIGIN,
) {

    /** 换票最终结果。 */
    sealed interface Result {
        data class Success(val ticket: String) : Result
        data class Failure(val message: String) : Result
    }

    /** 一次图形验证码：给 UI 显示，同时带回提交要用的 uuid（一次性）。 */
    data class Captcha(val uuid: String, val dataUri: String)

    /** 只在 ④ 用：换票是一次 GET，不需要 cookie 罐（网关凭 query 里的 st 认人）。 */
    private val gatewayClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun newCasUseCase(): CasLoginUseCase {
        val cas = CasApiFactory.create(casBaseUrl)
        return CasLoginUseCase(cas.api, cas.jar, cas.tgtInterceptor)
    }

    /**
     * ① 取图形验证码。
     *
     * **一次性**：重新取即作废旧的，所以 UI 必须「取码 → 用户填 → 用同一个 uuid 提交」。
     */
    suspend fun fetchCaptcha(): Captcha? = withContext(Dispatchers.IO) {
        try {
            when (val r = newCasUseCase().fetchCaptcha()) {
                is CasCaptchaResult.Success -> Captcha(r.captcha.uuid, r.captcha.image)
                is CasCaptchaResult.Failure -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** ② 发送短信验证码（需先有图形验证码的 uuid，且与用户看到的图配对）。 */
    suspend fun sendSmsCode(
        phone: String,
        imageCode: String,
        uuid: String,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val r = newCasUseCase().sendSmsCode(
                phone = phone,
                captcha = CasCaptcha(uuid = uuid, image = ""),
                captchaCode = imageCode,
            )
            when (r) {
                is CasSmsSendResult.Success -> Result.Success("")
                is CasSmsSendResult.Failure ->
                    Result.Failure(r.message.ifBlank { "验证码发送失败" })
            }
        } catch (e: Exception) {
            Result.Failure("发送短信失败：${e::class.simpleName}")
        }
    }

    /**
     * ③+④ 手机号 + 短信码 → st → 网关票。
     *
     * @return 成功时是 `wengine_vpn_ticket*` 的值
     */
    suspend fun loginByPhone(phone: String, smsCode: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val stResult = newCasUseCase().loginByPhoneForSt(
                    phone = phone,
                    smsCode = smsCode,
                    appId = portalAppId,
                )
                exchange(stResult)
            } catch (e: Exception) {
                Result.Failure("登录异常：${e::class.simpleName}")
            }
        }

    /**
     * ③+④ 账密 + 图形验证码 → st → 网关票。
     */
    suspend fun loginByPassword(
        account: String,
        password: String,
        captcha: CasCaptcha,
        captchaCode: String,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val stResult = newCasUseCase().loginWithPasswordForSt(
                studentNo = account,
                password = password,
                captcha = captcha,
                captchaCode = captchaCode,
                appId = portalAppId,
            )
            exchange(stResult)
        } catch (e: Exception) {
            Result.Failure("登录异常：${e::class.simpleName}")
        }
    }

    /** 把 st 解析结果接到第 ④ 步。 */
    private fun exchange(stResult: CasSignInStResult): Result = when (stResult) {
        is CasSignInStResult.Success -> exchangeWithTicket(stResult.st, stResult.userId)
        is CasSignInStResult.Failure ->
            Result.Failure(stResult.message.ifBlank { "统一身份认证登录失败" })
    }

    /**
     * ④ 用 st + userId 到**网关**换票。
     *
     * 这是整条链路里唯一与「学校网关」直接打交道的请求，也是 WebView 曾经存在的唯一理由。
     */
    fun exchangeWithTicket(st: String, userId: String): Result = try {
        val url = WebVpnGatewayExchange.loginUrl(st, userId)
            .replace(WebVpnGateway.ORIGIN, gatewayOrigin)

        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", WebVpnGateway.ORIGIN + "/")
            .build()

        gatewayClient.newCall(req).execute().use { resp ->
            val setCookies = resp.headers.values("Set-Cookie")
            val ticket = WebVpnGatewayExchange.ticketFromSetCookie(setCookies)
            when {
                ticket != null -> Result.Success(ticket)
                WebVpnGatewayExchange.looksLikeInvalidTicket(
                    runCatching { resp.body?.string() }.getOrNull(),
                ) -> Result.Failure("网关拒绝了这次登录票据，请重新登录")

                else -> Result.Failure("网关未下发登录票据：HTTP ${resp.code}")
            }
        }
    } catch (e: Exception) {
        Result.Failure("连接学校网关失败：${e::class.simpleName}")
    }

    companion object {
        /** WebVPN 用**门户** appid。实测页面 URL 里的 `appid=` 即此值。 */
        const val PORTAL_APPID = "aea49a21-6b26-4e8f-bcfc-80e3de591c93"

        /** 供 UI 把 data URI 解成 Bitmap 显示。 */
        fun decodeCaptcha(dataUri: String): ByteArray? = CaptchaImageDecoder.decode(dataUri)
    }
}
