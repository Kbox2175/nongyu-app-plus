package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.login.model.LoginMethod
import com.nongyu.appplus.webvpn.logic.WebVpnSignInDefaults.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「弹窗打开时用哪种方式、预填什么」的判定（维护者口径）：
 *
 * > 手机号登录就取手机号，账密登录就取账密，什么都取不到就是默认登录方式。
 */
class WebVpnSignInDefaultsTest {

    @Test
    fun `三种方式与顺序和学校登录页一致`() {
        assertEquals(
            listOf(Mode.PASSWORD, Mode.PHONE, Mode.WECHAT),
            WebVpnSignInDefaults.MODES,
        )
        assertEquals("密码登录", WebVpnSignInDefaults.labelOf(Mode.PASSWORD))
        assertEquals("短信登录", WebVpnSignInDefaults.labelOf(Mode.PHONE))
        assertEquals("微信扫码", WebVpnSignInDefaults.labelOf(Mode.WECHAT))
    }

    @Test
    fun `什么都取不到时用默认方式（账密）且不预填`() {
        val i = WebVpnSignInDefaults.resolve(null)
        assertEquals(WebVpnSignInDefaults.DEFAULT_MODE, i.mode)
        assertEquals(Mode.PASSWORD, i.mode)
        assertEquals("", i.account)
        assertEquals("", i.password)
        assertFalse(i.prefilled)
    }

    @Test
    fun `上次用账密登录_取账密`() {
        val i = WebVpnSignInDefaults.resolve(
            com.nongyu.appplus.webvpn.data.PendingCampusCredential.Snapshot(
                method = LoginMethod.CAMPUS_PASSWORD,
                account = "202500001",
                password = "secret",
            ),
        )
        assertEquals(Mode.PASSWORD, i.mode)
        assertEquals("202500001", i.account)
        assertEquals("secret", i.password)
        assertTrue(i.prefilled)
    }

    @Test
    fun `上次用手机号登录_取手机号且不带密码`() {
        val i = WebVpnSignInDefaults.resolve(
            com.nongyu.appplus.webvpn.data.PendingCampusCredential.Snapshot(
                method = LoginMethod.CAMPUS_PHONE,
                account = "13800138000",
                password = null,
            ),
        )
        assertEquals(Mode.PHONE, i.mode)
        assertEquals("13800138000", i.account)
        assertEquals("", i.password)
        assertTrue(i.prefilled)
    }

    @Test
    fun `只有账号没有密码也算不完整`() {
        val i = WebVpnSignInDefaults.resolve(
            com.nongyu.appplus.webvpn.data.PendingCampusCredential.Snapshot(
                method = LoginMethod.CAMPUS_PASSWORD,
                account = "202500001",
                password = null,
            ),
        )
        assertEquals(Mode.PASSWORD, i.mode)
        assertEquals("202500001", i.account)
        assertFalse("密码缺失时不算完整预填", i.prefilled)
    }

    @Test
    fun `图形验证码：微信扫码不需要`() {
        assertTrue(WebVpnSignInDefaults.needsImageCaptcha(Mode.PASSWORD))
        assertTrue(WebVpnSignInDefaults.needsImageCaptcha(Mode.PHONE))
        assertFalse(WebVpnSignInDefaults.needsImageCaptcha(Mode.WECHAT))

        assertFalse(WebVpnSignInDefaults.needsSmsCode(Mode.PASSWORD))
        assertTrue(WebVpnSignInDefaults.needsSmsCode(Mode.PHONE))
        assertFalse(WebVpnSignInDefaults.needsSmsCode(Mode.WECHAT))
    }

    @Test
    fun `账号框占位与实测站点一致`() {
        assertEquals("请输入工号/学号", WebVpnSignInDefaults.accountHintOf(Mode.PASSWORD))
        assertEquals("请输入手机号", WebVpnSignInDefaults.accountHintOf(Mode.PHONE))
        assertEquals("", WebVpnSignInDefaults.accountHintOf(Mode.WECHAT))
    }
}
