package com.nongyu.appplus.webvpn.logic

/**
 * 「辅助填入学校登录页」的纯逻辑（**不碰 Android / WebView**，可纯 JVM 测）。
 *
 * ## 选择器来自**真机抓取**，不是猜的
 *
 * 2026-11-19 直接在浏览器打开
 * `https://webvpn.sicau.edu.cn/login`（302 到 auth 域的 `/wui/` SPA）实测：
 *
 * | 实测事实 | 值 |
 * | --- | --- |
 * | input 的 `name` / `id` | **全部为 null**（`<input>` 上没有任何 name/id） |
 * | 密码登录 tab 的输入框 | `placeholder` = 「请输入工号/学号」/「请输入密码」/「请输入验证码」 |
 * | 短信登录 tab 的输入框 | 「请输入手机号」/「请输入验证码」/「输入短信验证码」 |
 * | 两组表单是否同时存在 | **是**：两个 tab 的 DOM 都在，靠 `offsetParent` 区分可见性 |
 * | `<form>` 元素个数 | **0**（纯 SPA + 按钮点击提交） |
 * | 提交按钮 | `button[type=submit]`，文案「登 录」（中间有空格！） |
 * | 图形验证码 | 页面内 `<img>`，**必须人工看**，无法程序化获取 |
 *
 * 因此定位**只能**用 `placeholder` + 可见性，`name`/`id` 一律不可用；
 * 提交**只能**点按钮，`form.submit()` 这条兜底在本站根本不存在。
 *
 * ## ⚠️ 图形验证码是硬阻塞
 *
 * 实测点「登 录」但验证码为空时，页面不前进（只多出「忘记密码」链接）。
 * 也就是说：**密码登录这条路无法全自动** —— 除非把页面显示出来让人填验证码，
 * 或者走短信登录（用户手机收码）。这正是本弹窗必须保留可见交互的原因，
 * 也是「完全删掉 WebView」不可行的根本原因。
 *
 * ## 安全纪律
 *
 * 1. **脚本用 JSON 转义插值**：绝不能把用户输入直接拼进 JS 字符串字面量；
 * 2. **写值必须走原生 setter**：React 受控组件只认 `HTMLInputElement.prototype`
 *    上的 `value` setter，直接 `el.value = x` 在 React 里读不到（实测本站是 React SPA）；
 * 3. **不读取、不回传任何填进去的值**。
 */
object WebVpnAutofill {

    /**
     * 「密码登录」tab 的账号框（实测 placeholder）。
     *
     * 注意是「工号/学号」而不是「学号」—— 实测值，不要想当然。
     */
    const val PH_ACCOUNT = "请输入工号/学号"

    /** 「密码登录」tab 的密码框。 */
    const val PH_PASSWORD = "请输入密码"

    /** 「短信登录」tab 的手机号框。 */
    const val PH_PHONE = "请输入手机号"

    /** 两个 tab 共用的图形验证码框。 */
    const val PH_CAPTCHA = "请输入验证码"

    /** 「短信登录」tab 的短信验证码框。 */
    const val PH_SMS_CODE = "输入短信验证码"

    /**
     * 账号校验：宽松（非空 + 长度合理 + 无控制字符）。
     *
     * 实测该框同时收工号与学号，本地不限定形态。
     */
    fun isAccountValid(raw: String?): Boolean {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || v.length > MAX_ACCOUNT) return false
        return v.none { it.code < 0x20 || it.code == 0x7f }
    }

    /** 密码只校验非空与长度上限；形态由学校决定，本地不猜。 */
    fun isPasswordValid(raw: String?): Boolean {
        val v = raw.orEmpty()
        if (v.isEmpty() || v.length > MAX_PASSWORD) return false
        return v.none { it.code < 0x20 || it.code == 0x7f }
    }

    /** 账号与密码是否足以尝试填充。 */
    fun canAutofill(account: String?, password: String?): Boolean =
        isAccountValid(account) && isPasswordValid(password)

    /** 图形验证码：4–6 位字母数字（平台通用形态，本地只做宽松长度校验）。 */
    fun isCaptchaValid(raw: String?): Boolean {
        val v = raw?.trim().orEmpty()
        return v.length in 1..8 && v.all { it.isLetterOrDigit() }
    }

    /**
     * 生成「切到密码登录 + 填入 + 可选填验证码 + 提交」脚本。
     *
     * 流程与真人操作一致（实测路径）：
     * 1. 点「密码登录」tab（默认停在「短信登录」，不切 tab 就填不到密码框）；
     * 2. 用 placeholder 找**可见**的账号框 / 密码框（两 tab DOM 同时存在，必须验可见）；
     * 3. 用原生 setter 写值 + 派发 input/change（React 受控组件才认）；
     * 4. 有验证码就一并填；没有就不动它（留给用户手填）；
     * 5. 点可见的 `button[type=submit]` 提交。
     *
     * @param captcha 图形验证码；为空串时不填该框。
     * @return 交给 `WebView.evaluateJavascript` 的脚本；返回 JSON `{ok,via,why}`。
     */
    /**
     * 从 WebView 页面读出当前已填的账号/密码/手机号（用于「记住」）。
     *
     * 纯 WebView 方案下用户在学校页面里输入，原生看不到；要记住必须在登录前把值读出来。
     * 只回 JSON（账号/密码/手机号），不回验证码。
     */
    fun buildReadCredentialsScript(): String =
        """
        (function(){
          function val(ph){
            var list = document.querySelectorAll('input');
            for (var i=0;i<list.length;i++){
              if ((list[i].placeholder||'').trim() === ph) return list[i].value || '';
            }
            return '';
          }
          function valPwd(){
            var list = document.querySelectorAll("input[type='password']");
            for (var i=0;i<list.length;i++){ if (list[i].value) return list[i].value; }
            return '';
          }
          // 用 | 分隔，原生 split 解析（字段值不含 |）
          return val(${jsStringLiteral(PH_ACCOUNT)}) + '|' + valPwd() + '|' + val(${jsStringLiteral(PH_PHONE)});
        })();
        """.trimIndent()

    /**
     * 预填脚本（纯 WebView 方案用）：切到指定 tab、填入账号/密码或手机号，**不提交、不碰验证码**。
     *
     * 用户仍需在页面上自己看验证码图、填图形码并点登录 —— App 只帮他把已知的账密/手机号
     * 预填好，减少输入。验证码图是页面自己的，所见即所填，不存在两套图不匹配的问题。
     *
     * @param byPhone true=短信登录（填手机号），false=密码登录（填学号+密码）
     * @return JS 脚本，返回 JSON `{tab, filled}` 便于诊断。
     */
    fun buildPrefillScript(account: String, password: String, byPhone: Boolean): String {
        val a = jsStringLiteral(account.trim())
        val p = jsStringLiteral(password)
        val accountPh = jsStringLiteral(if (byPhone) PH_PHONE else PH_ACCOUNT)
        val tabLabel = jsStringLiteral(if (byPhone) "短信登录" else "密码登录")
        return """
            (function(){
              function visible(el){
                if (!el) return false;
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              }
              function setValue(el, val){
                try {
                  var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
                  d.set.call(el, val);
                } catch(e) { el.value = val; }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              }
              function fillByPh(ph, val){
                var list = document.querySelectorAll('input');
                for (var i=0;i<list.length;i++){
                  if (!visible(list[i])) continue;
                  if ((list[i].placeholder||'').trim() === ph) { setValue(list[i], val); return true; }
                }
                return false;
              }
              // 切到目标 tab（DOM 异步渲染，返回后由原生延迟再填一次）
              var tab = 'current';
              var links = document.querySelectorAll('a');
              for (var j=0;j<links.length;j++){
                var t = (links[j].innerText||'').replace(/\s/g,'');
                if (t === $tabLabel && visible(links[j])) { links[j].click(); tab = $tabLabel; break; }
              }
              var okAcc = fillByPh($accountPh, $a);
              var okPwd = false;
              if (!${byPhone}) {
                var pwds = document.querySelectorAll("input[type='password']");
                for (var k=0;k<pwds.length;k++){
                  if (visible(pwds[k])) { setValue(pwds[k], $p); okPwd = true; break; }
                }
              }
              return JSON.stringify({tab:tab, acc:okAcc, pwd:okPwd});
            })();
        """.trimIndent()
    }

    fun buildSignInScript(account: String, password: String, captcha: String = ""): String {
        val a = jsStringLiteral(account.trim())
        val p = jsStringLiteral(password)
        val c = jsStringLiteral(captcha.trim())
        return """
            (function(){
              var PH_ACCOUNT = ${jsStringLiteral(PH_ACCOUNT)};
              var PH_PASSWORD = ${jsStringLiteral(PH_PASSWORD)};
              var PH_CAPTCHA = ${jsStringLiteral(PH_CAPTCHA)};

              function visible(el){
                if (!el || el.disabled || el.readOnly) return false;
                if (!el.offsetParent) return false;
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              }
              function byPlaceholder(ph){
                var list = document.querySelectorAll('input');
                for (var i=0;i<list.length;i++){
                  var el = list[i];
                  if (!visible(el)) continue;
                  if ((el.placeholder || '').trim() === ph) return el;
                }
                return null;
              }
              function visiblePwd(){
                var list = document.querySelectorAll("input[type='password']");
                for (var i=0;i<list.length;i++){ if (visible(list[i])) return list[i]; }
                return null;
              }
              // React 受控组件：必须走原型链上的原生 setter，直接赋值不生效
              function setValue(el, val){
                try {
                  var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                  d.set.call(el, val);
                } catch(e) { el.value = val; }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              }
              function fillByPh(ph, val){
                var el = byPlaceholder(ph);
                if (!el) return false;
                try { el.focus(); } catch(e) {}
                setValue(el, val);
                return true;
              }
              // ① 切到「密码登录」tab（已在该 tab 时点它也无害）
              try {
                var links = document.querySelectorAll('a, [role=tab], .nav-link');
                for (var i=0;i<links.length;i++){
                  var t = (links[i].innerText || '').replace(/\s/g,'');
                  if (t === '密码登录' && visible(links[i])) { links[i].click(); break; }
                }
              } catch(e) {}

              // ② 填账号 / 密码
              var okAcc = fillByPh(PH_ACCOUNT, $a);
              var pwdEl = visiblePwd();
              var okPwd = false;
              if (pwdEl) { try { pwdEl.focus(); } catch(e) {} setValue(pwdEl, $p); okPwd = true; }
              if (!okPwd) okPwd = fillByPh(PH_PASSWORD, $p);
              if (!okAcc || !okPwd) {
                return JSON.stringify({ok:false, why:'field-not-found', acc:okAcc, pwd:okPwd});
              }

              // ③ 图形验证码（用户给了才填）
              var cap = $c;
              var okCap = false;
              if (cap) { okCap = fillByPh(PH_CAPTCHA, cap); }

              // ④ 没有图形验证码就不要提交 —— 实测本站验证码为空时按钮点了不前进，
              //    盲点只会让用户以为「点了没反应」。
              if (!okCap) {
                return JSON.stringify({ok:true, via:'filled-only', why:'need-captcha'});
              }

              // ⑤ 提交：本站**没有 form**，只能点可见的 submit 按钮
              var btns = document.querySelectorAll("button[type='submit']");
              for (var k=0;k<btns.length;k++){
                if (visible(btns[k])) { btns[k].click(); return JSON.stringify({ok:true, via:'button'}); }
              }
              return JSON.stringify({ok:true, via:'filled-only', why:'no-submit-button'});
            })();
        """.trimIndent()
    }

    /**
     * 探测脚本：报告当前页面处于哪个 tab、有哪些可见输入框。
     *
     * 用于真机排查「为什么没填上」——比让用户描述页面可靠得多。
     */
    fun buildProbeScript(): String = """
        (function(){
          function visible(el){
            if (!el || !el.offsetParent) return false;
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
          }
          var fields = [];
          var list = document.querySelectorAll('input');
          for (var i=0;i<list.length;i++){
            if (!visible(list[i])) continue;
            fields.push({type:list[i].type, ph:list[i].placeholder || ''});
          }
          var tab = '';
          var links = document.querySelectorAll('a');
          for (var j=0;j<links.length;j++){
            var t = (links[j].innerText||'').replace(/\s/g,'');
            if ((t==='密码登录'||t==='短信登录') && visible(links[j])) {
              tab = t + (((links[j].className||'')+'').indexOf('active')>=0 ? '(active)' : '');
            }
          }
          return JSON.stringify({tab:tab, fields:fields});
        })();
    """.trimIndent()

    /**
     * 兼容旧调用：仅填入、不提交（保留给「先填后让用户确认」的场景）。
     */
    fun buildInjectScript(account: String, password: String): String =
        buildSignInScript(account, password, captcha = "")

    /**
     * 清空密码框（界面上「不同意 / 关闭」后避免密码留在页面里）。
     *
     * 只清密码，不动账号。走原生 setter，React 才能感知。
     */
    fun buildClearPasswordScript(): String = """
        (function(){
          var list = document.querySelectorAll("input[type='password']");
          for (var i=0;i<list.length;i++){
            var el = list[i];
            if (!el) continue;
            try {
              var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
              d.set.call(el, '');
            } catch(e) { el.value = ''; }
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

    const val MAX_ACCOUNT: Int = 64
    const val MAX_PASSWORD: Int = 256
}
