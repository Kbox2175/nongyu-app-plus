package com.nongyu.appplus.webvpn.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账密辅助填入的纯逻辑测试。
 *
 * ## 选择器常量来自 2026-11-19 的真实页面抓取
 *
 * 用浏览器打开 `https://webvpn.sicau.edu.cn/login` 实测得到的 DOM 事实：
 * - 所有 `<input>` 的 `name` / `id` **都是 null** → 只能按 `placeholder` 定位；
 * - 密码登录 tab 的字段是「请输入工号/学号」/「请输入密码」/「请输入验证码」；
 * - 两个 tab 的表单 DOM 同时存在，必须按可见性（offsetParent）过滤；
 * - 页面 `<form>` 数量为 **0** → 提交只能点 `button[type=submit]`。
 *
 * 这些常量一旦被"优化"成 `input[name=username]` 之类，真机就会填不上，
 * 所以这里把实测值钉死。
 */
class WebVpnAutofillTest {

    @Test
    fun `placeholder 常量与实测页面逐字一致`() {
        assertEquals("请输入工号/学号", WebVpnAutofill.PH_ACCOUNT)
        assertEquals("请输入密码", WebVpnAutofill.PH_PASSWORD)
        assertEquals("请输入手机号", WebVpnAutofill.PH_PHONE)
        assertEquals("请输入验证码", WebVpnAutofill.PH_CAPTCHA)
        assertEquals("输入短信验证码", WebVpnAutofill.PH_SMS_CODE)
    }

    @Test
    fun `账号是工号或学号_宽松校验`() {
        assertTrue(WebVpnAutofill.isAccountValid("202500001"))
        assertTrue(WebVpnAutofill.isAccountValid("13800138000"))
        assertTrue(WebVpnAutofill.isPasswordValid("p@ssw0rd"))

        assertFalse(WebVpnAutofill.isAccountValid(""))
        assertFalse(WebVpnAutofill.isAccountValid("   "))
        assertFalse(WebVpnAutofill.isAccountValid(null))
        assertFalse(WebVpnAutofill.isPasswordValid(null))
        assertFalse(WebVpnAutofill.isPasswordValid(""))
        assertFalse(WebVpnAutofill.isAccountValid("abc\ndef"))
        assertFalse(WebVpnAutofill.isAccountValid("a".repeat(WebVpnAutofill.MAX_ACCOUNT + 1)))
        assertFalse(WebVpnAutofill.isPasswordValid("a".repeat(WebVpnAutofill.MAX_PASSWORD + 1)))
    }

    @Test
    fun `canAutofill 需要账号与密码同时合法`() {
        assertTrue(WebVpnAutofill.canAutofill("202500001", "secret"))
        assertFalse(WebVpnAutofill.canAutofill("202500001", ""))
        assertFalse(WebVpnAutofill.canAutofill("", "secret"))
    }

    @Test
    fun `图形验证码宽松校验`() {
        assertTrue(WebVpnAutofill.isCaptchaValid("a1b2"))
        assertTrue(WebVpnAutofill.isCaptchaValid("XY"))
        assertFalse(WebVpnAutofill.isCaptchaValid(""))
        assertFalse(WebVpnAutofill.isCaptchaValid(null))
        assertFalse(WebVpnAutofill.isCaptchaValid("a b"))
    }

    // ---------- 脚本必须按实测 DOM 写，不能退化成 name/id ----------

    @Test
    fun `脚本按 placeholder 定位而不是 name 或 id`() {
        val s = WebVpnAutofill.buildSignInScript("202500001", "secret")
        // 注意：jsStringLiteral 会把中文转成 \uXXXX，所以脚本里**看不到**中文明文，
        // 断言必须打在「用 placeholder 比对」这个行为上，而不是中文字面量。
        assertTrue("必须遍历 input 并比对 placeholder", s.contains("placeholder"))
        assertTrue("必须调用 byPlaceholder 定位", s.contains("byPlaceholder"))
        // 实测 name/id 全是 null，出现「按 name/id 属性选择器」就说明退回了猜测。
        // 注意不能用 contains("input[id=") —— type='password' 里也含 "id="，会误报。
        assertFalse("不得按 name 属性选择", Regex("""input\[name\s*=""").containsMatchIn(s))
        assertFalse("不得按 id 属性选择", Regex("""input\[id\s*=""").containsMatchIn(s))
        assertFalse("不得按 #id 选择", s.contains("document.getElementById"))
    }

    @Test
    fun `实测 placeholder 中文会以 u 转义形态进入脚本`() {
        val s = WebVpnAutofill.buildSignInScript("a", "b")
        // 「工」= U+5DE5，「号」= U+53F7，转义后应为小写十六进制
        val expected = WebVpnAutofill.jsStringLiteral(WebVpnAutofill.PH_ACCOUNT)
        assertTrue("脚本里应出现转义后的账号框 placeholder", s.contains(expected))
        assertTrue("转义形态应含 \\u5de5", expected.contains("\\u5de5"))
    }

    @Test
    fun `脚本按可见性过滤_因为两个 tab 的 DOM 同时存在`() {
        val s = WebVpnAutofill.buildSignInScript("a", "b")
        assertTrue("必须用 offsetParent 判可见", s.contains("offsetParent"))
        assertTrue("必须同时校验尺寸", s.contains("getBoundingClientRect"))
    }

    @Test
    fun `脚本先切到密码登录 tab`() {
        val s = WebVpnAutofill.buildSignInScript("a", "b")
        assertTrue("默认停在短信登录，必须先切 tab", s.contains("密码登录"))
    }

    @Test
    fun `脚本用原生 setter 写值以适配 React`() {
        val s = WebVpnAutofill.buildSignInScript("a", "b")
        assertTrue(
            "React 受控组件只认原型链 setter，直接赋值读不到",
            s.contains("getOwnPropertyDescriptor") && s.contains("HTMLInputElement.prototype"),
        )
        assertTrue(s.contains("new Event('input'"))
        assertTrue(s.contains("new Event('change'"))
    }

    @Test
    fun `没有验证码时只填不提交_因为实测点了不前进`() {
        val s = WebVpnAutofill.buildSignInScript("a", "b", captcha = "")
        // 必须给出「需要验证码」的明确信号，且不盲点按钮
        assertTrue(s.contains("need-captcha"))
        assertTrue("不得兜底 form.submit（本站没有 form）", !s.contains(".form.submit()"))
    }

    @Test
    fun `给了验证码才点提交按钮`() {
        val s = WebVpnAutofill.buildSignInScript("a", "b", captcha = "a1b2")
        assertTrue(s.contains("button[type='submit']"))
        assertTrue(s.contains("via:'button'"))
        // 本站没有 form，不该出现 form.submit()
        assertFalse(s.contains(".form.submit()"))
    }

    // ---------- 转义安全 ----------

    @Test
    fun `危险字符被转义_不能跳出字符串`() {
        val nasty = "a'b\"c\\\\d\n\u2028\u2029</script>"
        val literal = WebVpnAutofill.jsStringLiteral(nasty)
        assertTrue(literal.startsWith("'"))
        assertTrue(literal.endsWith("'"))
        val inner = literal.substring(1, literal.length - 1)
        assertFalse("未转义单引号", Regex("(?<!\\\\)'").containsMatchIn(inner))
        assertFalse("未转义双引号", Regex("(?<!\\\\)\"").containsMatchIn(inner))
        assertFalse(inner.contains('\u2028'))
        assertFalse(inner.contains('\u2029'))
        assertFalse(inner.contains('\n'))
        assertFalse(inner.contains('\r'))
    }

    @Test
    fun `非 ASCII 统一转成 u 转义`() {
        val literal = WebVpnAutofill.jsStringLiteral("密码")
        assertTrue(literal.contains("\\u5bc6"))
        assertTrue(literal.contains("\\u7801"))
        assertFalse(literal.contains("密码"))
    }

    @Test
    fun `脚本里的账密同样被转义`() {
        val script = WebVpnAutofill.buildSignInScript("a'b", "c\nd")
        assertTrue("单引号应被转义", script.contains("\\'"))
        assertFalse("原始换行不得出现", script.contains("c\nd"))
    }

    // ---------- 其他 ----------

    @Test
    fun `清空密码脚本只动 password 且走原生 setter`() {
        val script = WebVpnAutofill.buildClearPasswordScript()
        assertTrue(script.contains("input[type='password']"))
        assertTrue("React 需要原生 setter 才能感知清空", script.contains("HTMLInputElement.prototype"))
        assertFalse(script.contains("input[type='text']"))
    }

    @Test
    fun `探测脚本可报告 tab 与可见字段`() {
        val s = WebVpnAutofill.buildProbeScript()
        assertTrue(s.contains("fields"))
        assertTrue(s.contains("密码登录"))
        assertTrue(s.contains("短信登录"))
    }

    @Test
    fun `buildInjectScript 兼容旧签名且不提交`() {
        val s = WebVpnAutofill.buildInjectScript("a", "b")
        assertTrue(s.contains("need-captcha"))
    }
}
