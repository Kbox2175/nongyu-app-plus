# 规格：WebVPN 登录弹窗（子窗体 / 顶层浮层）

> 2026-11-17。落地见 `webvpn/` 包，决策见 `docs/adr/0010-新增-webvpn-顶层能力域.md`。
> 后端契约真源：`docs/specs/网络层.md` §3「WebVPN 网关票」、`docs/后端已就绪-前端对接须知.md`。

## 1. 需求原文与拆解

> 做一个子窗体弹窗，对接后端的 webvpn 层，这个弹窗用于登录学校的 webvpn 同时其至于最顶层，
> 符合当前风格，同时有不同意登录的功能，这个弹窗在登录后或者被其他调度后生效。

| # | 要求 | 落点 |
| --- | --- | --- |
| 1 | 子窗体弹窗 | `WebVpnLoginDialog`：模态遮板 + 圆角白绿卡片（不是独立 Activity） |
| 2 | 对接后端 webvpn 层 | `WebVpnSessionRepository` → `WebVpnApi` → `POST/GET/DELETE /school/webvpn-session` |
| 3 | 登录学校 WebVPN | 卡片内 `WebView` 加载 `https://webvpn.sicau.edu.cn/login`，CAS 登录后从 `CookieManager` 提取网关票 |
| 4 | 置于最顶层 | `WebVpnDialogHost` 挂在 `NongyuApp` 根 `Box` **最后一个子节点**，在 `MainShell` / 登录中心 / 设置面板之上 |
| 5 | 符合当前风格 | `NongyuTheme.colors` + `uiScaleFor` + `RoundedCornerShape` + 品牌绿主按钮（与 `JumpToDateDialog` / `FormButton` 同族） |
| 6 | 不同意登录 | 卡片常驻「不同意」按钮 → `WebVpnDialogScheduler.decline()`，写会话级同意态，抑制后续自动调度 |
| 7 | 登录后 / 被其他调度后生效 | 4 个触发源：`SIGNED_IN` / `TICKET_MISSING` / `TICKET_EXPIRED` / `MANUAL`，统一走 `WebVpnDialogScheduler.request()` |

## 2. 数据流

```
┌ 调度方 ────────────────────────────────────────────────┐
│ ① 登录中心→主界面转场   NongyuApp  → SIGNED_IN           │
│ ② 后端 409/401         Interceptor → Dispatcher → 票缺失/过期 │
│ ③ 设置面板动作项        MainShell  → MANUAL               │
│ ④ 任意模块              WebVpnDialogScheduler.request()   │
└──────────────────────┬────────────────────────────────┘
                       ▼  WebVpnGate.decide（纯逻辑，有单测）
              WebVpnDialogScheduler.currentRequest (StateFlow)
                       ▼
              WebVpnDialogHost（根节点最顶层）collectAsState
                       ▼
        WebVpnLoginDialog  ── 不同意 ──▶ decline + 关闭
                           ── 同意 ──▶ WebView 登录
                                        ▼ CookieManager 取票
                                        ▼ WebVpnTicket.looksLikeTicket（纯逻辑）
                                        ▼
                          WebVpnSessionRepository.submit(ticket)
                                        ▼
                     POST /school/webvpn-session  {"ticket":"..."}
                                        ▼  成功
                              关闭弹窗 + 轻提示
```

## 3. 触发源与闸门

| 触发源 | automatic | 来源 | 说明 |
| --- | --- | --- | --- |
| `SIGNED_IN` | 是 | `NongyuApp` 观察 `LOGIN_CENTER → MAIN` | 登录成功后弹一次；每进程只弹一次 |
| `TICKET_MISSING` | 是 | 后端 `409 webvpn_ticket_missing` | 没交过票 |
| `TICKET_EXPIRED` | 是 | 后端 `401 webvpn_ticket_expired` | 票失效 |
| `MANUAL` | 否 | 设置面板动作项 | 用户显式操作，**绕过同意态** |

闸门规则（`WebVpnGate.decide`，纯 JVM 可测）：

1. `MANUAL` 永远 `SHOW`（显式调度不能被「不同意」永久堵死）；
2. 同意态 = `DECLINED` → `SKIP_DECLINED`（自动调度不再骚扰）；
3. `SIGNED_IN` 且本进程已弹过 → `SKIP_ALREADY_SHOWN`；
4. 其余 → `SHOW`。

同意态是**会话级（进程内）**，不是永久持久化：新一次启动会重新问一次；否则后端
`webvpn_ticket_missing` 永远无法恢复课表刷新。手动入口始终可用。

## 4. 网关票

- 名称：`wengine_vpn_ticketwebvpn_sicau_edu_cn`（按前缀 `wengine_vpn_ticket` 匹配，容忍后缀变化）。
- 属性：HttpOnly / Secure / 会话级 / `Domain=webvpn.sicau.edu.cn`。
- **票值只在内存里单向流动**：`CookieManager → WebVpnTicket.extract → POST body`，
  不落盘、不打印、不进埋点、不写日志。后端也永不回显票值（查询只回 `present`/`expiresAt`）。
- 形态校验对齐后端 `LooksLikeWebVPNTicket`：长度 8..512、全可见 ASCII、不含 `<>"'` 与空白。
- 匿名访问 `/login` 时网关也会下发一张 16 位**游客票**，所以「Cookie 存在」不等于登录成功。
  自动判定用「票值相对首帧基线发生变化 + 已离开登录页」；另提供「我已完成登录」按钮兜底
  （WebView 内 CAS SPA 行为仓内无一手实测，见 ADR-0010 待实测边界）。

## 5. 网络层接线（不改门面语义）

- `NetHolder.webVpn(context)`：与 `cache()` 同构，复用同一 `Retrofit`/`OkHttp` 连接池。
- `NetHolder.setOnWebVpnTicketRequired(callback)`：仿 `setOnSessionInvalidUi`，**UI→net 注入**，
  net 层不 import webvpn，保持依赖方向干净。
- `OkHttpFactory.build(..., webVpnTicketRequired = null)`：新增**可选**参数（默认值 → 现有 10+
  处测试调用点零改动），挂 `WebVpnTicketRequiredInterceptor`（只在 401/409 时 peek 错误码）。
- `WebVpnErrorClassifier`（纯逻辑）把 `webvpn_ticket_missing`/`webvpn_ticket_expired` 映射成触发源，
  其余码（含 `cache_unavailable` 503）不弹窗。

`webvpn_ticket_expired` 是 **401**，但 `SessionInvalidRules.decide` 对 `/school/` + 401
只在 `code == session_expired` 时返回 `KEEP_SESSION_SCHOOL_AUTH`，其余落保守的
`KEEP_SESSION_UNKNOWN` —— **不会清 App 登录态**，已由 `CacheSessionInvalidRulesTest` 覆盖。

## 6. 层级（为什么是最顶层）

`NongyuApp` 根 `Box` 的子节点按声明顺序绘制，后声明者在上：

```
MainShell()                         ← 主界面（含设置面板、星云球、时间条遮罩）
登录中心 + 圆形揭示挖洞层           ← 转场期间仍存在
WebVpnDialogHost()                  ← 本弹窗，最后声明 ⇒ 盖住以上全部
```

放在根节点而不是 `MainShell` 内的好处：登录中心转场期间、以及未来任何新浮层之上都生效，
且不依赖任何页面内部状态。

## 7. 验收

- `:app:testDebugUnitTest`：`WebVpnGateTest` / `WebVpnTicketTest` / `WebVpnErrorClassifierTest`
  / `WebVpnDialogSchedulerTest` / `WebVpnSessionRepositoryTest`（MockWebServer 验 POST body 形状）。
- `:app:assembleDebug` 编译通过（含 WebView / AndroidView / CookieManager）。
- 静态核对：`model/`、`logic/` 无 `android.*` / `androidx.compose.*` import。
