package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.login.model.LoginMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 临时凭据 store：登录成功后写入、弹窗打开时读取。
 *
 * 只在内存里，杀进程即丢；写入口径是「登录成功那一刻」。
 */
class PendingCampusCredentialTest {

    @After
    fun tearDown() = PendingCampusCredential.clear()

    @Test
    fun `初始为空`() {
        assertNull(PendingCampusCredential.take())
    }

    @Test
    fun `账密登录后能取到账号与密码`() {
        PendingCampusCredential.remember(
            method = LoginMethod.CAMPUS_PASSWORD,
            account = " 202500001 ",
            password = "secret",
        )
        val s = PendingCampusCredential.take()!!
        assertEquals(LoginMethod.CAMPUS_PASSWORD, s.method)
        assertEquals("202500001", s.account)
        assertEquals("secret", s.password)
        assertEquals(true, s.hasPassword)
    }

    @Test
    fun `手机号登录后只取手机号`() {
        PendingCampusCredential.remember(
            method = LoginMethod.CAMPUS_PHONE,
            account = "13800138000",
        )
        val s = PendingCampusCredential.take()!!
        assertEquals(LoginMethod.CAMPUS_PHONE, s.method)
        assertEquals("13800138000", s.account)
        assertNull(s.password)
    }

    @Test
    fun `微信扫码什么都不记`() {
        // 先存入账密，再用微信扫码登录：应当把之前的一并清空
        PendingCampusCredential.remember(LoginMethod.CAMPUS_PASSWORD, "202500001", "pwd")
        PendingCampusCredential.remember(LoginMethod.CAMPUS_WECHAT, "whatever")
        assertNull("扫码不记任何账号信息", PendingCampusCredential.take())
    }

    @Test
    fun `短信登录只记手机号_不记验证码`() {
        PendingCampusCredential.remember(
            method = LoginMethod.CAMPUS_PHONE,
            account = "13800138000",
            // 即使误传了密码位，短信方式也必须把它丢掉
            password = "123456",
        )
        val s = PendingCampusCredential.take()!!
        assertEquals("13800138000", s.account)
        assertNull("短信方式不得记住任何验证码/密码", s.password)
    }

    @Test
    fun `空账号不写入`() {
        PendingCampusCredential.remember(LoginMethod.CAMPUS_PASSWORD, "", "p")
        assertNull(PendingCampusCredential.take())
    }

    @Test
    fun `后一次登录覆盖前一次`() {
        PendingCampusCredential.remember(LoginMethod.CAMPUS_PASSWORD, "a", "p")
        PendingCampusCredential.remember(LoginMethod.CAMPUS_PHONE, "b")
        val s = PendingCampusCredential.take()!!
        assertEquals(LoginMethod.CAMPUS_PHONE, s.method)
        assertEquals("b", s.account)
    }
}
