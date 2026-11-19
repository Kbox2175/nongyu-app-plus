package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.login.model.LoginMethod
import com.nongyu.appplus.webvpn.data.PendingCampusCredential

/**
 * WebVPN 弹窗（= 我们自己的登录页）的**方式与预填判定**（纯逻辑，可纯 JVM 测）。
 *
 * ## 产品定义（维护者原话）
 *
 * > 弹出弹窗，是我们的登录页面，但是底层还是学校的统一身份认证，就是一个桥，
 * > 然后在登录时做一个将密码放到临时的 store 然后弹窗时去取
 * > （手机号登录就取手机号，账密登录就取账密，什么都取不到就是默认登录方式）。
 *
 * 以及「然后给三种方式（和登录页面一样）」。
 *
 * 所以弹窗承载与学校登录页**同样的三种方式**：
 *
 * | 方式 | 需要的输入 | 真实站点对应 |
 * | --- | --- | --- |
 * | 账密 | 学号 + 密码 + 图形验证码 | 「密码登录」tab |
 * | 手机号 | 手机号 + 图形验证码 + 短信验证码 | 「短信登录」tab |
 * | 微信扫码 | 扫页面上的二维码 | 首屏二维码 |
 *
 * 三种方式**共用同一套接口**（`auth.sicau.edu.cn/api/v1/captcha` 取图形码、
 * `cas/login` 换票），因此弹窗能做成纯原生的页面，学校页面完全不必显示。
 */
object WebVpnSignInDefaults {

    /** 弹窗可用的三种方式（顺序即 tab 顺序，与学校登录页一致）。 */
    enum class Mode {
        PASSWORD,
        PHONE,
        WECHAT,
    }

    /** 全部方式，按展示顺序。 */
    val MODES: List<Mode> = listOf(Mode.PASSWORD, Mode.PHONE, Mode.WECHAT)

    /** 取不到任何凭据时的默认方式。 */
    val DEFAULT_MODE: Mode = Mode.PASSWORD

    /**
     * 弹窗初始状态。
     *
     * @param mode 默认选中方式
     * @param account 预填账号（账密=学号；手机号=手机号），没有则空串
     * @param password 预填密码（仅账密），没有则空串
     * @param prefilled 是否真的预填到了内容
     */
    data class Initial(
        val mode: Mode,
        val account: String,
        val password: String,
        val prefilled: Boolean,
    )

    /**
     * 由「上次统一身份认证登录用过的凭据」推出初始状态。
     *
     * 规则：用账密登录过 → 取账密；用手机号登录过 → 取手机号；
     * 什么都取不到 → [DEFAULT_MODE]。
     */
    fun resolve(snapshot: PendingCampusCredential.Snapshot?): Initial {
        if (snapshot == null) {
            return Initial(mode = DEFAULT_MODE, account = "", password = "", prefilled = false)
        }
        return when (snapshot.method) {
            LoginMethod.CAMPUS_PASSWORD -> Initial(
                mode = Mode.PASSWORD,
                account = snapshot.account,
                password = snapshot.password.orEmpty(),
                prefilled = snapshot.account.isNotEmpty() && !snapshot.password.isNullOrEmpty(),
            )

            LoginMethod.CAMPUS_PHONE -> Initial(
                mode = Mode.PHONE,
                account = snapshot.account,
                password = "",
                prefilled = snapshot.account.isNotEmpty(),
            )

            else -> Initial(mode = DEFAULT_MODE, account = "", password = "", prefilled = false)
        }
    }

    /** tab 文案，与学校登录页保持一致。 */
    fun labelOf(mode: Mode): String = when (mode) {
        Mode.PASSWORD -> "密码登录"
        Mode.PHONE -> "短信登录"
        Mode.WECHAT -> "微信扫码"
    }

    /** 该方式需要图形验证码吗（微信扫码不需要）。 */
    fun needsImageCaptcha(mode: Mode): Boolean = mode != Mode.WECHAT

    /** 该方式需要短信验证码吗。 */
    fun needsSmsCode(mode: Mode): Boolean = mode == Mode.PHONE

    /** 账号框的占位文案（与实测站点一致）。 */
    fun accountHintOf(mode: Mode): String = when (mode) {
        Mode.PASSWORD -> "请输入工号/学号"
        Mode.PHONE -> "请输入手机号"
        Mode.WECHAT -> ""
    }
}
