package com.nongyu.appplus.webvpn.logic

/**
 * 「辅助填入账号密码」的纯逻辑（**不碰 Android / WebView**，可纯 JVM 测）。
 *
 * ## ⚠️ 为什么这件事必须谨慎
 *
 * 学校登录页是 `/wui/` 的跨域 SPA，App 想往里填字**只能靠 JS 注入**。
 * 而「自动填账密」等价于**把教务密码持久化在手机上**——这是本仓此前刻意避免的事
 * （`docs/specs/网络层.md`、`docs/后端已就绪-前端对接须知.md` 都把学校密码当敏感物）。
 * 维护者 2026-11-18 明确要求做持久保存，因此本类只负责**拼装注入脚本与校验输入**，
 * 真正的加密落盘由 `data/WebVpnCredentialStore`（Keystore + AES/GCM）承担。
 *
 * ## 安全纪律（三条，任何一条失守都会让功能变成漏洞）
 *
 * 1. **脚本用 JSON 转义插值**：绝不能把用户输入直接拼进 JS 字符串字面量，
 *    否则一个带 `"` 或 `\` 的密码就能跳出字符串执行任意代码。
 *    这里用 [jsStringLiteral] 统一做转义。
 * 2. **只往登录页注入**：调用方（UI）必须先确认当前 URL 仍在学校登录域内。
 * 3. **不读取、不回传任何填进去的值**：脚本只做 `el.value = ...` 与 `dispatchEvent`，
 *    不把页面上的任何内容返回给原生层。
 *
 * ## 为什么用 `dispatchEvent`
 *
 * 现代前端框架（Vue / React）的受控输入框不监听 `el.value =` 的赋值，
 * 只认用户输入产生的 `input` / `change` 事件。不补这一步的话，
 * 页面看起来填上了，但点登录时框架状态还是空的 —— 这是自动填最典型的假成功。
 */
object WebVpnAutofill {

    /** 账号输入框候选（学校 wui 登录页常见 name/id，按优先级）。 */
    private val USER_SELECTORS = listOf(
        "input[name='account']",
        "input[name='username']",
        "input[name='user']",
        "input[name='loginName']",
        "input[id='account']",
        "input[id='username']",
        "input[type='text']",
        "input[type='tel']",
        "input:not([type])",
    )

    /** 密码输入框候选。 */
    private val PASSWORD_SELECTORS = listOf(
        "input[type='password']",
        "input[name='password']",
        "input[id='password']",
    )

    /**
     * 账号可以是学号或手机号，这里只做「非空 + 长度合理」的宽松校验。
     *
     * 刻意不限定必须是 9 位学号：学校登录页同时接受学号与手机号，
     * 在客户端硬编码某一形态会让另一条合法路径被误拒。
     */
    fun isAccountValid(raw: String?): Boolean {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || v.length > MAX_ACCOUNT) return false
        // 不允许控制字符（含换行），避免注入到脚本里改变结构
        return v.none { it.code < 0x20 || it.code == 0x7f }
    }

    /** 密码只校验非空与长度上限；形态由学校决定，本地不猜。 */
    fun isPasswordValid(raw: String?): Boolean {
        val v = raw.orEmpty()
        if (v.isEmpty() || v.length > MAX_PASSWORD) return false
        return v.none { it.code < 0x20 || it.code == 0x7f }
    }

    /** 账号与密码是否都还不足以尝试填充。 */
    fun canAutofill(account: String?, password: String?): Boolean =
        isAccountValid(account) && isPasswordValid(password)

    /**
     * 生成注入脚本：按候选选择器找第一个可见输入框，赋值并派发事件。
     *
     * @return 可直接交给 `WebView.evaluateJavascript` 的脚本字符串。
     */
    fun buildInjectScript(account: String, password: String): String {
        val a = jsStringLiteral(account)
        val p = jsStringLiteral(password)
        return """
            (function(){
              var userSel = ${jsSelectorArray(USER_SELECTORS)};
              var passSel = ${jsSelectorArray(PASSWORD_SELECTORS)};
              function pick(sels, exclude){
                for (var i=0;i<sels.length;i++){
                  var list = document.querySelectorAll(sels[i]);
                  for (var j=0;j<list.length;j++){
                    var el = list[j];
                    if (!el || el.disabled || el.readOnly) continue;
                    if (exclude && el === exclude) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) continue;
                    return el;
                  }
                }
                return null;
              }
              function fill(el, val){
                if (!el) return false;
                el.focus();
                el.value = val;
                try {
                  el.dispatchEvent(new Event('input', {bubbles:true}));
                  el.dispatchEvent(new Event('change', {bubbles:true}));
                } catch(e) {}
                return true;
              }
              var pwd = pick(passSel, null);
              var usr = pick(userSel, pwd);
              var okU = fill(usr, $a);
              var okP = fill(pwd, $p);
              return okU && okP;
            })();
        """.trimIndent()
    }

    /**
     * 生成「填入并**自动提交**」脚本（无界面 WebView 专用）。
     *
     * ## 为什么需要「自动提交」
     *
     * 维护者 2026-11-18 要求「不用那个网页展示」：界面只保留原生三态表单，
     * WebView 改为**不显示**的后台载体，仅用来真实跑完网关登录 SPA（否则拿不到网关票）。
     * 既然用户看不到页面，就没人替我们点「登录」——必须由脚本填完直接提交。
     *
     * ## 提交策略（按可靠性排序，逐个尝试）
     *
     * 1. 表单内 / 页面上的 `submit` 按钮（`type=submit`、`[type=button]`、
     *    文案含「登录 / 登 录 / 立即登录」的元素）→ `.click()`；
     * 2. 若填到的密码框有 `form`，直接 `form.submit()`；
     * 3. 兜底：在密码框上派发 `Enter` 键盘事件（多数登录页监听回车提交）。
     *
     * 三条都试是因为学校 `/wui/` 登录页的真实 DOM 仓内无一手记录，
     * 多一条兜底就多一分自动化成功率；失败也不会报错，只是留在当前页。
     *
     * @return `(ok, 说明)` 的 JSON 字符串；`ok` 表示至少填到了两个输入框。
     */
    fun buildFillAndSubmitScript(account: String, password: String): String {
        val a = jsStringLiteral(account)
        val p = jsStringLiteral(password)
        return """
            (function(){
              var userSel = ${jsSelectorArray(USER_SELECTORS)};
              var passSel = ${jsSelectorArray(PASSWORD_SELECTORS)};
              function pick(sels, exclude){
                for (var i=0;i<sels.length;i++){
                  var list = document.querySelectorAll(sels[i]);
                  for (var j=0;j<list.length;j++){
                    var el = list[j];
                    if (!el || el.disabled || el.readOnly) continue;
                    if (exclude && el === exclude) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) continue;
                    return el;
                  }
                }
                return null;
              }
              function fill(el, val){
                if (!el) return false;
                try { el.focus(); } catch(e) {}
                el.value = val;
                try {
                  el.dispatchEvent(new Event('input', {bubbles:true}));
                  el.dispatchEvent(new Event('change', {bubbles:true}));
                } catch(e) {}
                return true;
              }
              var pwd = pick(passSel, null);
              var usr = pick(userSel, pwd);
              var okU = fill(usr, $a);
              var okP = fill(pwd, $p);
              if (!okP) return JSON.stringify({ok:false,why:'no-password-field'});

              // ① 找提交按钮
              var btn = document.querySelector("button[type='submit'], input[type='submit']");
              if (!btn) {
                var cands = document.querySelectorAll("button, [role='button'], .btn, .login-btn");
                for (var k=0;k<cands.length;k++){
                  var t = (cands[k].innerText || cands[k].value || '').replace(/\s/g,'');
                  if (t.indexOf('登录') >= 0 || t.toLowerCase().indexOf('login') >= 0) { btn = cands[k]; break; }
                }
              }
              if (btn) { try { btn.click(); return JSON.stringify({ok:true,via:'button'}); } catch(e) {} }

              // ② 直接提交表单
              if (pwd && pwd.form) {
                try { pwd.form.submit(); return JSON.stringify({ok:true,via:'form'}); } catch(e) {}
              }

              // ③ 回车兜底
              try {
                var ke = new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true});
                pwd.dispatchEvent(ke);
                var ku = new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true});
                pwd.dispatchEvent(ku);
                return JSON.stringify({ok:true,via:'enter'});
              } catch(e) {
                return JSON.stringify({ok:true,via:'none'});
              }
            })();
        """.trimIndent()
    }

    /**
     * 过滤掉页面上残留的密码输入（例如用户点「不同意」或关闭后）。
     *
     * 只清空**密码**，不动账号：账号不是秘密，清掉反而让用户重输。
     */
    fun buildClearPasswordScript(): String = """
        (function(){
          var list = document.querySelectorAll("input[type='password']");
          for (var i=0;i<list.length;i++){
            var el = list[i];
            if (!el) continue;
            el.value = '';
            try {
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
            } catch(e) {}
          }
          return true;
        })();
    """.trimIndent()

    // ---------------- 转义（安全边界，务必走这里） ----------------

    /**
     * 把一个任意字符串变成**安全的 JS 字符串字面量**（含两侧引号）。
     *
     * 逐字符转义而不是简单 `replace("\"", "\\\"")`：
     * 后者漏掉反斜杠、换行、U+2028/U+2029（JS 里是行终止符，能直接截断字符串字面量）。
     * 非 ASCII 统一走 `\uXXXX`，彻底避开编码歧义。
     */
    internal fun jsStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append('\'')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0000' -> sb.append("\\0")
                // JS 的行终止符，未转义会让字面量提前结束
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> {
                    if (ch.code < 0x20 || ch.code > 0x7e) {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('\'')
        return sb.toString()
    }

    /** 把选择器列表转成 JS 数组字面量（选择器是我们自己写的常量，无需转义用户输入）。 */
    private fun jsSelectorArray(selectors: List<String>): String =
        selectors.joinToString(prefix = "[", postfix = "]") { jsStringLiteral(it) }

    const val MAX_ACCOUNT: Int = 64
    const val MAX_PASSWORD: Int = 256
}
