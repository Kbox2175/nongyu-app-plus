package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.login.model.LoginMethod

/**
 * 「用户最近一次用统一身份认证登录时填过什么」的**临时中转站**。
 *
 * ## 为什么需要它（维护者的原话）
 *
 * > 弹出弹窗，是我们的登录页面，但是底层还是学校的统一身份认证，就是一个桥，
 * > 然后在登录时做一个将密码放到临时的 store 然后弹窗时去取
 * > （手机号登录就取手机号，账密登录就取账密，什么都取不到就是默认登录方式）。
 *
 * 所以这是一个**单向的、进程内的**中转：登录中心登录成功时把用户填的凭据放进来，
 * WebVPN 弹窗打开时取出来预填。
 *
 * ## 为什么是"临时"而不是持久
 *
 * 持久化账密是另一件事，已有 [WebVpnCredentialStore]（Keystore + AES/GCM）负责。
 * 本类刻意**只活在内存里**：它表达的是「本次会话里用户刚刚输入过什么」，
 * 杀进程即丢。这样有两个好处：
 *
 * 1. 用户在主登录页输入但不勾选记住时，不会因为这里被顺带持久化；
 * 2. 弹窗能在**用户还没决定是否保存**的情况下就预填好，体验连贯。
 *
 * 写入口径：**只在登录成功那一刻写入**。登录失败不写 —— 失败可能是账号或密码错，
 * 存下来只会让弹窗预填一个错误的值。
 */
object PendingCampusCredential {

    /**
     * 用户最近一次统一身份认证登录用的方式与输入。
     *
     * @param method 当时用的是账密还是手机号
     * @param account 账密方式下是学号；手机号方式下是手机号
     * @param password 仅账密方式有值
     */
    data class Snapshot(
        val method: LoginMethod,
        val account: String,
        val password: String? = null,
    ) {
        val hasPassword: Boolean get() = !password.isNullOrEmpty()
    }

    @Volatile
    private var snapshot: Snapshot? = null

    /** 登录成功后调用：记下这次用的方式与输入。 */
    fun remember(method: LoginMethod, account: String, password: String? = null) {
        val a = account.trim()
        if (a.isEmpty()) return
        snapshot = when (method) {
            LoginMethod.CAMPUS_PASSWORD ->
                Snapshot(method = method, account = a, password = password.orEmpty().ifEmpty { null })

            LoginMethod.CAMPUS_PHONE ->
                Snapshot(method = method, account = a, password = null)

            // 其他方式（微信扫码 / 平台登录）不产生可复用的学校凭据
            else -> return
        }
    }

    /** 弹窗打开时取：没有就返回 null（调用方据此用默认登录方式）。 */
    fun take(): Snapshot? = snapshot

    /** 仅供测试与登出清理。 */
    fun clear() {
        snapshot = null
    }
}
