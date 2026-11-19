# 农屿 · 翻新版（nongyu-app-plus）

农屿 App 翻新版的 Android 原生客户端，Kotlin + Jetpack Compose。

> **状态**：已进入功能开发阶段（9 个能力域、175 个 Kotlin 文件、约 24k 行）。
> 已完成登录（含 WebVPN 校外接入）、课表、皮肤、网络层与全局设置；
> 当前版本 `0.0.60test`（versionCode 68）。

## 技术栈

| 项 | 版本 | 说明 |
| --- | --- | --- |
| Gradle | 9.4.1 | AGP 9.x 要求 Gradle 9.1+ |
| Android Gradle Plugin | 9.2.1 | 该版本支持 compileSdk 36 |
| Kotlin | 2.3.0 | AGP 9 内置 Kotlin 支持，无需单独应用 kotlin-android 插件 |
| Compose BOM | 2025.12.00 | **不可直接升级到 2026.x**，见下方说明 |
| JDK | 17（编译目标） | `sourceCompatibility = VERSION_17` |

### 关于 Compose BOM 的版本锁定

`compose-bom:2026.09.00` 中的 `runtime-saveable` 要求依赖方 `compileSdk >= 37`，
而当前开发机的 Android SDK 仅安装到 `android-36`，直接使用会导致构建失败：

```
Dependency 'androidx.compose.runtime:runtime-saveable-android:1.12.1' requires libraries
and applications that depend on it to compile against version 37 or later of the Android APIs.
```

因此本工程锁定 `2025.12.00`。待 SDK 升级到 `android-37` 后，可同步放开该版本。

## 环境要求

- JDK 17 或更高
- Android SDK：`compileSdk = 36`、`build-tools 36.0.0`
- `ANDROID_HOME` 指向 Android SDK 根目录

## 构建与测试

```bash
./gradlew :app:assembleRelease          # 发布包（R8 + 资源收缩 + 不 debuggable）
./gradlew :app:assembleDebug            # 调试包
./gradlew :app:testDebugUnitTest        # 单元测试（纯 JVM）
```

Windows 下用 `gradlew.bat`。

当前用例规模：**807** 个，全部通过。

产物位置：
- release：`app/build/outputs/apk/release/app-release.apk`
- debug：`app/build/outputs/apk/debug/app-debug.apk`

### 后端地址

`net/NetConfig.kt` 按构建类型分叉：

| 构建类型 | BASE_URL |
| --- | --- |
| debug | `http://127.0.0.1:3000/` |
| release | `http://103.236.88.53:57979/`（公网测试后端）|

> ⚠️ debug 与 release 指向**不同后端，Redis 数据完全隔离**。
> 用 release 包排查「课表为空 / 数据不对」时，先确认它连的是哪台服务，别拿本机调试数据去对。

生产域名尚未落地。release 若留空串，`UrlBuilder.normalize` 会抛带明确文案的异常，不会静默变成坏 URL。

调 debug 包时需把本机 3000 端口映射到设备：`adb reverse tcp:3000 tcp:3000`。

## 包结构

顶层按**能力域**切分（ADR-0008），没有 `ui/` 中间包。域内按 `model / logic / data / ui` 分层，
依赖方向固定 `model ← logic ← data ← ui`，且 `design ← ui`。

`*/model/`、`*/logic/` **禁止** import `android.*` 与 `androidx.compose.*` —— 否则纯 JVM 单测无法覆盖决策逻辑。

```text
app/src/main/java/com/nongyu/appplus/
├── design/        主题 token（CompositionLocal 承载）、UI 尺度、纯文字 Toast
├── icon/          图标几何与内置矢量图回落
├── login/         统一身份认证：data / model / session / ui
├── net/           网络层：api / auth / core / model / sign
├── settings/      全局设置：model / logic / ui
├── shell/         应用外壳与底部栏：bar / dialogs / orb
├── skin/          皮肤包：加载、校验、色值与资源通道
├── timetable/     课表：data / logic / model / ui
└── webvpn/        校外接入：data / logic / model / ui
```

## 已实现能力

### 登录
统一身份认证（账密 / 短信 / 微信三种方式），登录态由 `SessionStore` 持久化，
圆形揭示转场；token 加密存储于 Keystore。

### 课表
- **本地优先**：本地有数据时**完全不请求后端**，断网也能看
- 读路径三级回落：本地 → 后端缓存 → `cache_miss` 时**自动抓取教务并回传**
- 「更新课表」沿用后端下发的取数配方（地址 / 方法 / Cookie / 解析器名全部由后端给，客户端不写死）
- 日/周视图切换、课程详情、日程添加、时间条拖拽

### 皮肤
- 色值通道走数据库目录接口，资源通道走皮肤包（ADR-0003/0004）
- 冷启动只读本地皮肤包、不碰网络（ADR-0006）
- 图标可由皮肤包位图覆盖，未覆盖则回落 `res/drawable` 矢量图（ADR-0009）

### 全局设置
课表工具条齿轮入口，右侧滑入面板（占屏 90%，左侧留 10%），齿轮克隆跟随动画。
设置按「分区 → 条目」组织，面板只消费 `SettingsContent`，**新增模块设置无需改面板**。

### WebVPN 校外接入
校外访问校园功能的入口。**不使用 WebView**，纯 HTTP 换网关票：

```
本地表单（账密 / 短信 / 微信） → 后端网关登录接口 → 网关票 → 后续请求自动带票
```

- 三种方式**按方式分别记忆**最近一次输入
- 凭据经 Keystore + AES/GCM 加密持久化，**登录成功才落盘**
- `PendingCampusCredential` 只活在内存（本次会话刚输入过什么），杀进程即丢
- 启动后探测教务域名可达性，判定校内/校外并上报 presence；校外缺票/过期时驱动登录弹窗
- 详见 `docs/specs/WebVPN接入-20261118.md` 与 ADR-0010

### 网络层
`net/api/` 目前定义 8 个子域：`Identity` / `School` / `Cache` / `WebVpn` / `Presence` /
`Announcement` / `Message` / `Post`。请求准入、签名、错误码分类见 `docs/specs/网络层.md`。

## 版本与发布

`app/build.gradle.kts` 的 `versionCode` / `versionName` 每次打包必须递增，且与 Release tag 一一对应。

发布流程：
1. 递增版本号并提交
2. 推送分支，建 GitHub Release 并上传 `app-release.apk`
3. **release 包装机验证不崩** —— R8 的风险不是构建失败，而是「构建成功但运行时某个入口崩」

## 文档

- `docs/adr/` —— 架构决策记录（0001-0010，逐条含依据与取舍）
- `docs/specs/` —— 规格与实施记录：`网络层.md`（唯一真源）、`界面层.md`、`WebVPN接入-20261118.md`、`活动层-H5-节日页.md` 等
- `AGENTS.md` —— 工程约定（包结构硬约束、构建发布流程、Kotlin 注释规则）

## 应用标识

| 项 | 值 |
| --- | --- |
| applicationId | `com.nongyu.appplus` |
| minSdk | 26 |
| targetSdk / compileSdk | 36 |
| 应用名称 | 农屿 |

## 当前边界

- **生产域名未落地**：release 现指向公网测试后端 `103.236.88.53:57979`，生产地址与 HTTPS 见 issue #70
- **皮肤字体**只构造未消费（issue #54）
- **设置项的开关值只存内存**，重启丢失；持久化方案待定
- 图标线宽 `iconStrokes` 的调节在图标资源化后对导航图标失效（已知取舍）
- 课表详情卡暂只展示 6 个字段（学分/学时等未进 DTO，按「谁消费谁声明」推迟）
