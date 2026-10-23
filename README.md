# 酷狗概念版 VIP 助手（KuGouLiteVipAutoClaim）

酷狗概念版（`com.kugou.android.lite`）的 Xposed / LSPosed 模块：**启动即自动领取每日听歌会员、自动升级概念版 VIP，领取弹窗在显示前就被拦截，全程无感。**

v1.5.0 起整套代码由 Java 重写为 **Kotlin**，并针对 1.4.0「延迟高、升级要点手动」两个问题做了系统性优化。

---

## 功能

- **主动领取每日听歌会员** — 酷狗启动时直接调官方接口，不依赖弹窗、不等你点
- **自动升级概念版 VIP** — 领取成功后自动升级（默认开启，可关），失败自动重试
- **弹窗无感拦截** — 领取/签到类弹窗在 `show()` 前拦截，根本不出现；漏网的自动点击兜底
- **凭证自动捕获** — Hook 酷狗 OkHttp 请求提取 token/userid/dfid/mid（URL / Header / Cookie / FormBody 四路兜底）
- **每日去重** — 按北京时间自然日去重，同一天不重复领
- **配置秒级生效** — 模块 APP 里改开关，广播通知酷狗进程立即刷新，不用重启酷狗
- **手动触发** — 模块 APP 一键补领/补升级（已领取未升级时也能单独补升级）

## v1.5.0 优化内容（对比 1.4.0）

### 延迟账：每一笔都记在案

| # | 1.4.0 的问题 | 1.5.0 的做法 |
|---|---|---|
| 1 | 触发太晚：只有 `Application.onCreate` + `Activity.onResume`（节流 10 秒） | 新增 `Application.attachBaseContext` 最早触发（进程内第一个可用时机）；resume 节流 10s → 3s |
| 2 | 轮询太慢：看门狗固定 5 秒一次 | 渐进轮询 1s → 2s → 3s → 5s |
| 3 | 重试太慢：领取失败固定等 8 秒 × 3 次 | 2s / 4s 退避重试；风控错误不重试 |
| 4 | 重 IO：每次轮询全量遍历酷狗全部 SP 文件 | 扫描成功 5 分钟缓存、失败退避 10 秒 |
| 5 | 关键路径跨进程：每次判断同步 `ContentProvider` 读配置、同步回写状态（模块进程没起时会被拉起，秒级延迟压在关键路径上） | 配置进程内秒读 + 单次同步初始化 + 异步刷新；状态回写全异步 |
| 6 | 凭证只存内存，下次冷启动重新等 OkHttp 捕获 | 凭证变更即时落盘（30 秒去重），冷启动 SP 直接命中 |

凭证缓存命中的常规路径：**酷狗进程启动 → 1~2 秒内完成领取 + 升级 + 通知**。模块日志每行带 `[+启动后毫秒数]` 前缀，延迟可直接度量：

```bash
adb pull /data/data/com.kugou.android.lite/files/kugou_vip_module.log
```

### 自动升级修复（不再需要进模块手点）

1.4.0 升级失败一次即放弃，且跨进程读 `auto_upgrade` 失败时默认 `false`，开关形同虚设。v1.5.0：

- `auto_upgrade` **默认开启**，配置读缓存化 + 本地镜像兜底，开关真实生效；
- 升级独立重试（3s × 2 次），与领取状态**解耦**：
  - 已领取未升级（如早上关着升级开关、晚上打开）→ 单独补升级，不重复调领取接口；
  - 手动触发会重置升级完成标记 → 今日已领取时直接补升级；
- 升级响应 `error_code=131001`（今日已升级）识别为成功态。

### Kotlin 重写

- 20 个源文件全部重写，业务逻辑逐行对照 1.4.0 移植；
- **签名算法经过新旧实现逐位对比验证**（23 个用例全过，含特殊字符/空 Map/各错误码，见下文「验证」）；
- compat 层（libxposed API 102 + 旧版 API 82 双后端）同步 Kotlin 化，双格式入口不变。

### 弹窗体收紧

- 关键词去掉「活动/福利/赠送/充值/续费」等宽泛词，减少对酷狗正常对话框的误伤；
- 点击节奏：Dialog 500/1500ms → 250/750ms，WebView 注入 2/4/6s → 0.8/1.6/2.4s。

## 工作原理

```
酷狗概念版进程启动
    │
    ▼
MainHook.onPackageReady（libxposed API 100+）
  或 LegacyEntry.handleLoadPackage（旧版 API 82）
    │
    ▼
Installer.install(classLoader)
    ├── NetCapture.install()        Hook okhttp3.Request$Builder.build()
    │                               四路提取凭证：URL query / Header / Cookie / FormBody
    ├── PopupBlocker.install()      Dialog/WindowManager/startActivity/PopupWindow 前置拦截
    ├── AutoClicker.install()       漏网弹窗自动点击 + 关闭
    ├── hook Application.attachBaseContext  ★ 最早触发点（v1.5.0 新增）
    ├── hook Application.onCreate          兜底触发（幂等）
    └── hook Activity.onResume             兜底触发（节流 3s）
            │
            ▼
      ClaimWorker.start()
            ├── 凭证解析：OkHttp 捕获 → 进程内缓存 → 模块 SP 缓存 → 酷狗 SP 扫描（退避）
            ├── 看门狗渐进轮询（1s→2s→3s→5s，最长 10 分钟）
            ▼  凭证就绪
      attemptClaim()
            ├── 开关判断（进程内缓存，零跨进程）
            ├── 今日已领取？→ 跳过领取阶段
            ▼
      KugouApi.claimVip()   POST /youth/v1/recharge/receive_vip_listen_song
            ├── status=1 → 领取成功
            ├── error_code=131001 → 今日已领取
            ├── error_code=20028 → 账号风控（不重试）
            └── 失败 → 2s/4s 退避重试
            ▼  （成功或已领取，且 auto_upgrade 开启）
      KugouApi.upgradeVip() POST /youth/v1/listen_song/upgrade_vip_reward
            └── 失败 → 3s 后重试一次；仍失败留给看门狗/手动触发
            ▼
      NotifyUtil 通知 + Settings.putStatus 异步回写模块 APP
```

## 关键接口

| 接口 | 方法 | 路径 |
|------|------|------|
| 领取听歌VIP | POST | `https://gateway.kugou.com/youth/v1/recharge/receive_vip_listen_song` |
| 升级概念版VIP | POST | `https://gateway.kugou.com/youth/v1/listen_song/upgrade_vip_reward` |

签名算法：`signature = MD5(salt + sorted_kv_string + body + salt)`

- `salt` = `LnT6xpN3khm36zse0QzvmgTZ3waWdRSA`（概念版专用）
- `sorted_kv_string` = 参数按 key 字母序排列，拼接为 `key1=value1key2=value2...`
- `body` = 请求体（这两个接口均为空）

公共参数：`appid=3116`、`clientver=11440`、`clienttime`（秒级时间戳）、`mid`、`dfid`、`uuid=-`、`token`、`userid`；领取接口另带 `source_id=90139`、`receive_day`（**北京时间**日期），升级接口另带 `kugouid=userid`、`ad_type=1`。

### 错误码

| 返回 | 含义 | 处理 |
|------|------|------|
| `status=1` | 成功 | 标记今日完成，继续升级 |
| `error_code=131001` | 今日已领取/已升级 | 标记今日完成 |
| `error_code=20028` | 账号风控 | 通知用户前往手机端领取，本轮不重试 |

## 构建

### 环境要求

- JDK 17+
- Android SDK（compileSdk 35，build-tools 34.0.0+）
- Gradle 8.9+（仓库已带 wrapper，`./gradlew` 即可）

### 命令行

```bash
# 配置 SDK 位置（或写入 local.properties: sdk.dir=/path/to/android-sdk）
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### Android Studio

直接用 Android Studio 打开本目录，等 Gradle 同步完成后 `Build → Build APK(s)`。

### 关于两个 API jar

`app/libs/` 下固定了两份编译期依赖（运行期由 Xposed 框架提供，不打进 APK）：

- `api-102.0.0.jar` — libxposed API 102（现代入口 `MainHook`，来自 Maven Central `io.github.libxposed:api`）
- `api-82.jar` — 旧版 Xposed API 82（`LegacyEntry`，来自 api.xposed.info）

### 签名

`keystore/debug.keystore` 为业界公开的 Android 调试密钥（口令 `android`），与 1.4.0 保持一致，可覆盖安装不丢数据。**别拿它发正式版。**

## 安装使用

1. 安装构建出的 APK
2. LSPosed 管理器 → 模块 → 启用本模块
3. **作用域勾选「酷狗概念版」**（`com.kugou.android.lite`）
4. **关闭 LSPosed 设置里的「Xposed API 调用保护」**（否则 Hook 静默失效）
5. 重启酷狗概念版，等待通知即可

### 配置选项（模块 APP 内）

| 选项 | 默认 | 说明 |
|------|------|------|
| 自动领取每日会员 | 开 | 启动酷狗时自动领取 |
| 自动升级概念版VIP | **开**（v1.5.0 起） | 领取成功后自动升级，获得更高音质 |
| 隐藏桌面图标 | 关 | 隐藏后仍可从 LSPosed 管理器「打开」按钮进入 |

开关变更通过广播即时同步到酷狗进程，无需重启酷狗。

## 项目结构

```
KuGouLiteVipAutoClaim/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/          # Gradle 8.11.1 wrapper
├── keystore/debug.keystore            # 调试签名密钥（口令 android）
├── tools/parity/                      # 新旧实现对比测试（见下）
└── app/
    ├── build.gradle                   # AGP 8.7.3 + Kotlin 2.0.21，R8 仅裁 stdlib
    ├── proguard-rules.pro             # 保留本模块全部类（注入进程，正确性优先）
    ├── libs/                          # 两份 Xposed API 编译期依赖
    └── src/main/
        ├── AndroidManifest.xml        # 双格式模块声明
        ├── assets/xposed_init         # 旧版入口 → LegacyEntry
        ├── resources/META-INF/xposed/ # 现代入口三件套（module.prop / java_init.list / scope.list）
        ├── kotlin/com/moekoe/xposed/kugoulite/
        │   ├── MainHook.kt            # 现代入口（libxposed API 100+）
        │   ├── LegacyEntry.kt         # 旧版入口（API 82，禁止引用 libxposed）
        │   ├── Installer.kt           # 统一装配（attachBaseContext 最早触发）
        │   ├── ClaimWorker.kt         # 领取/升级编排（低延迟核心）
        │   ├── KugouApi.kt            # 接口封装 + 签名 + 错误码
        │   ├── NetCapture.kt          # OkHttp 凭证捕获（四路兜底）
        │   ├── PopupBlocker.kt        # 弹窗前置拦截
        │   ├── AutoClicker.kt         # 弹窗自动点击兜底
        │   ├── PrefStore.kt           # 凭证/状态/配置镜像（酷狗 SP）
        │   ├── Settings.kt            # 配置缓存 + 异步刷新
        │   ├── StateProvider.kt       # 跨进程 ContentProvider
        │   ├── MainActivity.kt        # 模块配置界面（纯原生 UI）
        │   ├── ModuleLog.kt           # 日志（带启动后耗时前缀）
        │   ├── NotifyUtil.kt          # 通知
        │   └── compat/                # 双框架兼容层
        │       ├── XposedBridge.kt / XposedHelpers.kt / HookBackend.kt
        │       ├── ModernBackend.kt   # libxposed 拦截器链 → before/after 语义
        │       ├── LegacyBackend.kt   # 旧版 before/after
        │       └── XC_MethodHook.kt / MethodHookParam.kt
        └── res/                       # 原生布局与资源（无 AndroidX 依赖）
```

## 验证

`tools/parity/` 是一组 JVM 对比测试：把 1.4.0 的 Java 版 `KugouApi`（`oldimpl` 包）与 1.5.0 的 Kotlin 版并排反射调用，逐位比对签名、URL、Cookie、响应解析输出：

```bash
# 先编译模块 Kotlin 代码
./gradlew compileReleaseKotlin

cd tools/parity
javac -encoding UTF-8 -nowarn \
  -cp "../../app/build/tmp/kotlin-classes/release:json.jar:$ANDROID_HOME/platforms/android-35/android.jar" \
  -d out src/android/util/Log.java src/oldimpl/KugouApi.java src/Harness.java
java -cp "out:../../app/build/tmp/kotlin-classes/release:json.jar:kotlin-stdlib.jar" Harness
# 期望输出：========== 结果: 23 通过, 0 失败 ==========
```

覆盖：签名算法（普通/业务参数/特殊字符/已含 signature 键/空 Map）、公共参数构造（全量/null 默认值）、Cookie 拼接、URL 拼接、领取响应解析（成功/已领/风控/未知错误码/坏 JSON/空响应）、日期与 MD5。

## 排障

| 现象 | 排查 |
|------|------|
| 完全没反应 | ① LSPosed 里作用域勾了酷狗概念版吗 ② 「Xposed API 调用保护」关了吗 ③ 看日志有没有「模块已注入目标应用」 |
| 领取成功但不升级 | 模块 APP 里「自动升级概念版VIP」开关是否开启；日志看「升级结果」行 |
| 提示账号风控 | 服务器限制，去手机端手动领一次，次日再试 |
| 想看延迟 | 日志每行 `[+1234ms]` 前缀即启动后耗时 |

日志位置：`/data/data/com.kugou.android.lite/files/kugou_vip_module.log`（超过 1MB 自动轮转）。

## 致谢

- [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi) — 酷狗音乐 API 接口
- [iAJue/MoeKoeMusic](https://github.com/iAJue/MoeKoeMusic) — 参考实现
- [libxposed](https://github.com/libxposed/api) — 现代 Xposed API

## 免责声明

本模块仅供学习交流使用，请遵守酷狗音乐相关服务条款。使用本模块产生的一切后果由使用者自行承担。
