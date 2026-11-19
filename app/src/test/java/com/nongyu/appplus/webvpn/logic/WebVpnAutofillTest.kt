package com.nongyu.appplus.webvpn.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账密辅助填入的纯逻辑测试。
 *
 * 重点在**注入脚本的转义安全**：密码里出现引号 / 反斜杠 / U+2028 等字符时，
 * 必须仍是合法的 JS 字符串字面量，否则用户输入就能跳出字符串执行任意代码。
 */
class WebVpnAutofillTest {

    @Test
    fun `账号与密码的宽松校验`() {
        assertTrue(WebVpnAutofill.isAccountValid("202500001"))
        assertTrue(WebVpnAutofill.isAccountValid("13800138000"))
        assertTrue(WebVpnAutofill.isPasswordValid("p@ssw0rd"))

        assertFalse(WebVpnAutofill.isAccountValid(""))
        assertFalse(WebVpnAutofill.isAccountValid("   "))
        assertFalse(WebVpnAutofill.isAccountValid(null))
        assertFalse(WebVpnAutofill.isPasswordValid(null))
        assertFalse(WebVpnAutofill.isPasswordValid(""))
        // 控制字符拒绝
        assertFalse(WebVpnAutofill.isAccountValid("abc\ndef"))
        assertFalse(WebVpnAutofill.isPasswordValid("abc\rd"))
        // 超长拒绝
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
    fun `脚本包含账号与密码且默认转义为普通字面量`() {
        val script = WebVpnAutofill.buildInjectScript("202500001", "secret")
        assertTrue(script.contains("202500001"))
        assertTrue(script.contains("secret"))
        // 用单引号包裹的字面量
        assertTrue(script.contains("'202500001'"))
    }

    @Test
    fun `危险字符被转义_不能跳出字符串`() {
        val nasty = "a'b\"c\\\\d\n\u2028\u2029</script>"
        val literal = WebVpnAutofill.jsStringLiteral(nasty)
        // 首尾必须是单引号，内部原始引号不得裸露
        assertTrue(literal.startsWith("'"))
        assertTrue(literal.endsWith("'"))
        val inner = literal.substring(1, literal.length - 1)
        // 除转义序列中的反斜杠外，不允许出现未转义的单引号 / 双引号
        assertFalse("未转义单引号", Regex("(?<!\\\\)'").containsMatchIn(inner))
        assertFalse("未转义双引号", Regex("(?<!\\\\)\"").containsMatchIn(inner))
        // 行终止符必须被转义，不能截断字符串字面量
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
    fun `清空密码脚本只动 password 输入框`() {
        val script = WebVpnAutofill.buildClearPasswordScript()
        assertTrue(script.contains("input[type='password']"))
        // 不应把账号一起清掉
        assertFalse(script.contains("input[type='text']"))
    }

    // ---------- 无界面 WebView：填完必须自动提交 ----------

    @Test
    fun `填入并提交脚本包含三级提交策略`() {
        val script = WebVpnAutofill.buildFillAndSubmitScript("202500001", "secret")
        // ① 提交按钮
        assertTrue("应尝试点提交按钮", script.contains("type='submit'"))
        // ② 直接提交表单
        assertTrue("应兜底 form.submit()", script.contains(".form.submit()"))
        // ③ 回车兜底
        assertTrue("应兜底派发 Enter", script.contains("keyCode:13"))
        // 返回 JSON 结果便于排查
        assertTrue(script.contains("JSON.stringify"))
    }

    @Test
    fun `填入并提交脚本里的账密同样被转义`() {
        val nasty = "a'b\"c\\\\d\n</script>"
        val script = WebVpnAutofill.buildFillAndSubmitScript(nasty, nasty)
        // 原始换行不得出现（会被转成 \n 两字符）
        val rawNewlineInLiteral = script.contains("c\\\\d\n")
        assertFalse("原始换行不得出现在脚本里", rawNewlineInLiteral)
        // 单引号必须转义，否则字面量被截断
        assertTrue("单引号应被转义", script.contains("\\'"))
        // 行终止符不出现
        assertFalse(script.contains("\u2028"))
        assertFalse(script.contains("\u2029"))
    }

    @Test
    fun `没有密码框时脚本返回失败标记`() {
        val script = WebVpnAutofill.buildFillAndSubmitScript("a", "b")
        assertTrue(script.contains("no-password-field"))
    }
}
