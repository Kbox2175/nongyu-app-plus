# ADR-0010 新增 `webvpn` 顶层能力域

- 状态：已采纳（2026-11-17）
- 相关：ADR-0008、`docs/specs/界面层.md`、`docs/specs/网络层.md`、`docs/specs/WebVPN登录弹窗.md`

## 背景

需要一个「登录学校 WebVPN 网关」的子窗体弹窗，要求：

1. 对接后端的 WebVPN 网关票层（`/school/webvpn-session` 的提交 / 查询 / 清除，
   见 `docs/specs/网络层.md` §3 WebVPN 网关票）；
2. 弹窗**置于最顶层**（盖住主界面、登录中心、设置面板等一切浮层）；
3. 符合当前风格（白绿 `NongyuTheme` + `uiScale` 自适应 + 圆角卡片 + 模态遮板）；
4. 有**不同意登录**的功能（用户可拒绝，且拒绝后不应反复骚扰）；
5. 弹窗在**登录后**或**被其他调度**后生效。

后端契约已经就位：网关票（`wengine_vpn_ticketwebvpn_sicau_edu_cn`）是 HttpOnly 会话 Cookie，
只能由客户端真实登录网关产生；客户端把票 `POST /school/webvpn-session` 交给后端保管，
后端代取教务页面。查询端点**只回 `present`/`expiresAt`，永不回显票值**。票失效 /
缺失时后端回 `webvpn_ticket_expired`(401) / `webvpn_ticket_missing`(409)，客户端据此
引导用户重新登录网关。

仓内此前**没有任何** WebView、CookieManager、二维码登录界面的落地代码（登录中心走的是
CAS/微信扫码/账密的另一条链路），也**没有** WebVPN 会话的 UI 调度点。

## 决策

新增**单数具名能力域**顶层包 `com.nongyu.appplus.webvpn`，按 ADR-0008 的
`model / logic / data / ui` 分层，依赖方向 `model ← logic ← data ← ui`，且 `design ← ui`。

- `model/`：弹窗触发源、同意态、弹窗 UI 状态、网关常量、错误码分类结果。
  **禁止** import `android.*` 与 `androidx.compose.*`（可纯 JVM 测）。
- `logic/`：可单测的纯逻辑 —— 弹窗「是否应弹出」的闸门判定、Cookie 头里网关票的
  提取与形态校验、后端错误码 → 触发原因的映射。同样禁止 `android.*`/`compose.*`。
- `data/`：
  - `WebVpnConsentStore`：不同意登录的偏好（按会话范围，进程内 + 可注入）。
  - `WebVpnDialogScheduler`：**全局调度接缝**（单例，Compose `StateFlow`/`mutableStateOf`
    持有「当前请求」），供「登录后」「后端错误码」「设置入口」等任意调度方调用。
  - `WebVpnSessionRepository`：对接后端 `WebVpnApi`（经 `NetHolder.webVpn()` 装配），
    提交票 / 查状态 / 清票。票值**只在内存里单向流动，绝不落盘、绝不打印**。
- `ui/`：`WebVpnLoginDialog`（顶层子窗体）—— 模态遮板 + 白绿圆角卡片 +
  承载网关登录页的 `WebView`（`AndroidView`）+ 「同意登录 / 不同意」双按钮；
  `WebVpnDialogHost` 观察调度器，渲染在**根节点最顶层**。

### 为什么是「根节点最顶层」而不是 `Dialog()`

Compose 的 `Dialog()` 会开一个独立 window，但它挂在调用它的 Composition 局部，
且本项目已有「设置面板高于星云球」的显式层级约定（`MainShell` 末尾）。
把弹窗放在 `NongyuApp` 根 `Box` 的**最后一个子节点**（在 `MainShell`/登录中心**之外、
之上**），才能保证：

- 登录中心 → 主界面的圆形揭示转场期间也能盖住；
- 设置面板、星云球、时间条遮罩等一切浮层都在它之下；
- 不依赖任何页面内部状态，天然响应「其他调度」。

### 为什么 WebView 承载登录页

网关票是 `HttpOnly`，JS 读不到；但 `android.webkit.CookieManager.getCookie(url)`
能读到**非 HttpOnly 视角的已存 Cookie 字符串**里网关票的值（旧 RN 探索文档 §295
记的是「JS/document.cookie 读不到」，不是 CookieManager 读不到）。WebView 完整跑完
学校 CAS 登录 SPA 后，从 CookieManager 提取票值交给后端 —— 这是「客户端真实登录网关
产生票」的唯一可行形态，与后端 `webvpn.go` 注释「只有客户端真实登录网关才会产生有效票」
一致。不引第三方库（WebView 是平台自带）。

### 同意 / 不同意语义

- **同意**：进入 WebView 登录流程；登录成功提取票 → 提交后端 → 关闭弹窗并回报成功。
- **不同意**：写入同意态偏好（本次会话不再自动弹），关闭弹窗并回报拒绝。
  后端的 `webvpn_ticket_missing/expired` 触发源尊重同意态，避免反复骚扰；
  但用户在设置里**手动**点「登录 WebVPN」属于显式调度，仍可再次弹出。

### 生效时机（调度点）

1. **登录后**：`LoginSessionHolder.onSignedIn()` 之后由 `NongyuApp` 观察登录去向，
   进入主界面时投递一次 `Trigger.SignedIn` 调度（有同意态闸门）。
2. **被其他调度**：
   - 后端返回 `webvpn_ticket_missing`(409) / `webvpn_ticket_expired`(401)：
     由 `WebVpnErrorDispatcher` 经网络层接缝投递 `Trigger.TicketMissing` / `Trigger.TicketExpired`；
   - 全局设置面板新增动作项「登录 WebVPN / 刷新网关票」投递 `Trigger.Manual`；
   - 任意模块可调 `WebVpnDialogScheduler.request(...)` —— 这是公开的唯一调度入口。

## 后果

- 新增一个顶层包与一份 ADR（本文件），符合 ADR-0008「新增顶层包要先写 ADR」。
- `model/`、`logic/` 全部纯 JVM 可测，弹窗闸门 / 票提取 / 错误码映射都有单测覆盖。
- 网络层只增加 `NetHolder.webVpn()` 装配点与 `WebVpnErrorDispatcher` 一个可选回调挂钩，
  **不改** `NongyuGateWay` 的既有门面语义，不破坏 ADR-0006（冷启动不碰网络：
  调度是首帧之后由登录/错误/手动触发，不在启动路径上装配实例）。
- WebView / CookieManager 只在 `ui/`、`data/` 的 Android 侧出现，`model/`、`logic/` 保持纯净。
- 代价：弹窗内含平台 WebView，release 包需确认 R8 不会裁掉 `android.webkit` 相关调用
  （平台类不受 R8 影响；`AndroidView` 由 Compose consumer rules 覆盖，proguard 无需新增 keep）。

## 待实测边界

- 网关登录页在 Android WebView 内能否完整跑完 CAS SPA（含可能的微信/验证码 Tab）：
  仓内 `WebVPN探索.md` 的一手实测来自浏览器/RN，WebView 内行为需真机验证。
- `CookieManager.getCookie` 能否稳定读到 `wengine_vpn_ticket*` 的**已认证态**值：
  网关在匿名访问 `/login` 时也会下发一张 16 位游客票，必须以「登录后是否跳回登录页」+ 后端
  代取结果为最终判据，不能只靠「Cookie 存在」判成功。
