# 通知下沉（NotificationSinking）

为 OPPO Find X8（ColorOS 16.0.9.400 / Android 16）开发的 LSPosed 模块，实现 iOS 风格的
**锁屏岛 / 胶囊态通知**与 **AOD 息屏通知**整体下沉效果。

- 包名：`com.example.notificationsinking`
- 所有 Hook **默认关闭**，用户手动开启后才生效（防开机崩溃）
- 配置修改**实时生效**，无需重启 SystemUI
- 不修改通知内容、不收集任何数据，只调整布局偏移

---

## 1. 项目目录结构

```
NotificationSinking/
├── settings.gradle                        # 根工程（含 pluginManagement / 仓库）
├── build.gradle                           # AGP 8.9.2（compileSdk 36）
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   # Gradle 8.11.1
└── app/
    ├── build.gradle                       # compileOnly de.robv.android.xposed:api:82（JCenter 阿里云镜像）
    ├── proguard-rules.pro                 # 入口类 keep 规则
    └── src/main/
        ├── AndroidManifest.xml            # xposedmodule 元数据 + SettingsActivity + PrefsProvider
        ├── assets/
        │   ├── module.prop                # LSPosed 模块元数据（xposedsharedprefs=true）
        │   ├── java_init.list             # 现代入口（libxposed）：ModernEntry（XposedModule 子类）
        │   ├── xposed_init                # 传统入口（de.robv）：MainHook（IXposedHookLoadPackage）
        │   └── scope.list                 # 推荐作用域（systemui/exsystemui/aod 三个包）
        ├── java/com/example/notificationsinking/
        │   ├── ModernEntry.java            # 现代入口：io.github.libxposed.api.XposedModule 子类
        │   ├── ModernHooks.java            # 现代 Hook API（hook(Executable).intercept）实现
        │   ├── Compat.java                 # 兼容工具：Context 获取（反射）、双入口去重
        │   ├── SinkState.java              # 视图偏移状态机（现代/传统共用）
        │   ├── MainHook.java               # 传统入口（旧框架备用，已不在入口清单）
        │   ├── ResourceHook.java           # 资源替换（默认不启用）
        │   ├── BaseSinkHook.java           # 传统入口下沉基类
        │   ├── LockscreenSinkHook.java     # 传统入口：锁屏下沉
        │   ├── AodSinkHook.java            # 传统入口：AOD 下沉
        │   ├── ConfigStore.java            # 配置通道：Provider 主 + 现代远程偏好 + 传统 XSP
        │   ├── PrefsProvider.java          # ContentProvider 跨进程配置通道
        │   ├── HookLog.java                # 现代 log() / 传统 XposedBridge（反射）/ Log.e 三通道
        │   └── ui/SettingsActivity.java    # 设置界面（总开关/锁屏/AOD/滑块/预览/诊断中心）
        ├── resources/META-INF/xposed/      # 现代 API 模块声明（v1.2.1 起唯一入口布局）
        │   ├── java_init.list              # → ModernEntry（XposedModule 子类）
        │   ├── scope.list                  # 推荐作用域：systemui / exsystemui / aod
        │   └── module.prop                 # minApiVersion=101, targetApiVersion=102, staticScope=false
        └── res/
            ├── layout/activity_settings.xml
            ├── values/{strings,colors,themes}.xml
            ├── drawable/{bg_card,bg_preview,ic_notification,ic_launcher_foreground}.xml
            └── mipmap-anydpi-v26/ic_launcher.xml
```

---

## 2. 调研结论（参考模块反编译/源码核对）

| 来源 | 结论 |
| --- | --- |
| [rikumi/coloros-mod](https://github.com/rikumi/coloros-mod)（= 用户点名的 `com.rikumi.colorosmod`，[LSPosed 仓库镜像](https://github.com/Xposed-Modules-Repo/com.rikumi.colorosmod)） | 内置「锁屏通知区域下移」功能，测试环境 **Android 16 / ColorOS 16.1.0（Find X8 Ultra 同代）**。实现：hook 三个返回 int 的“通知顶部间距”方法并叠加同一偏移量（见 §3 Tier-0）。这正是本模块锁屏下沉的主路径。 |
| [Xposed-Modules-Repo/com.op.notification.sinking](https://github.com/Xposed-Modules-Repo/com.op.notification.sinking) | 目标一致（“ColorOS 锁屏/AOD 通知下沉，支持自定义修改”）。仓库只含说明，无源码，但其功能描述与 coloros-mod 的实现思路吻合。 |
| [Qjj7679/ColorOS_Aod_Enhance](https://github.com/Qjj7679/ColorOS_Aod_Enhance)（[模块页](https://modules.lsposed.org/module/com.op.aod.enhance/)） | 专门 Hook AOD 的模块，v1.4 适配 **ColorOS 16.0.5+（Android 16）**。核实的类：`com.oplus.systemui.aod.aodclock.off.AodUpdateManager`、`com.oplus.systemui.aod.display.SmoothTransitionController`、`com.oplus.systemui.aod.scene.*`、`com.oplus.systemui.keyguard.gesture.OplusDoubleClickSleep` 等。**关键事实：ColorOS 15/16 的 AOD 由 SystemUI 进程渲染**（命名空间 `com.oplus.systemui.aod.*`），旧版 ColorOS 才用独立 `com.oplus.aod` APK。其作用域同时勾选 `com.android.systemui` 与 `com.oplus.aod`。 |

coloros-mod 工程文档同时核实了以下**工程级事实**，本模块已全部落实：

1. **ColorOS 丢弃 `Log.d/v/i/w`** → 日志必须走 `Log.e`（HookLog 同时写 `XposedBridge.log`，供 LSPosed 管理器日志页查看）；
2. 配置跨进程首选 **ContentProvider/Binder 通道**（绕开 SELinux 对 `app_data_file` 的限制，不用 `MODE_WORLD_READABLE`）——与 coloros-mod 完全同方案，ColorOS 16 实测可靠；LSPosed RemotePreferences 在真机上出现过「重启后 CPU 高占用 / 读不到值」问题，**v1.0.1 起不再使用**；
3. 非锁屏时 `getKeyguardNotificationStaticPadding` 原始返回 0，**必须校验 `isKeyguardShowing()` 后再叠加偏移**；
4. 三个顶部间距方法“汇入 updateTopPadding 且参与内部差值计算”，必须**叠加同一偏移量**，否则动画起止值不一致。

---

## 3. ColorOS 16 适配方案总览（v1.0.1 收敛版）

> v1.0.1 依据真机反馈（重启后 CPU 高占用、无效果）做了风险收敛：移除全局 `View#onAttachedToWindow`
> 兜底 Hook、控制器触发方法 Hook、RemotePreferences 通道、system_server（android）与 aodplugin 作用域、
> ResourceHook 默认不注册。只保留可枚举的定点 Hook。

### 锁屏下沉 —— 两层

| 层级 | 目标 | 方式 |
| --- | --- | --- |
| **Tier-0（主路径，已实测）** | `com.android.systemui.shade.NotificationPanelViewController#getKeyguardNotificationStaticPadding()` | after 叠加偏移（仅 `isKeyguardShowing()` 为真时） |
| | `com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl#getNtfTopPaddingInLockscreen()` | after 叠加偏移 |
| | 同上 `#getNtfTopPaddingInLockscreenNtfCenter()` | after 叠加偏移 |
| **Tier-1（候选视图类名）** | `CapsuleNotificationView`、`OplusNotificationCapsuleView`、`OplusLockScreenNotificationView`、`OplusIslandView` 等 8 个候选（仅 View 子类） | `findClassIfExists` 动态查找 → onAttachedToWindow/onLayout 后 `translationY` 下沉；非 View 类静默跳过 |

### AOD 下沉 —— 候选类名 + 资源兜底（可选）

| 层级 | 目标 | 方式 |
| --- | --- | --- |
| **Tier-1（候选视图类名）** | `com.oplus.systemui.aod.notification.AodNotificationView(/Container/GroupView/...)` 与旧版 `com.oplus.aod.notification.*` 共 10 个候选 | 动态查找（仅 View 子类）→ `translationY` 下沉 |
| **资源替换（ResourceHook，默认未注册）** | 目标包资源的 `aod_notification_margin_top` 等候选 dimen | 见 `ResourceHook.java` 类注释：需手动加入 `assets/java_init.list` 后重新构建才启用 |

### 偏移双方案（BaseSinkHook）

- **方案 A（默认）`translationY`**：纯视觉、方向确定（正值向下）、不参与测量，不与系统动画/布局冲突 —— ColorOS 首选；
- **方案 B（可选）`MarginLayoutParams.bottomMargin`**：参与布局流，但方向依赖容器锚定方式（`MARGIN_SIGN` 常量可调 +1/-1），需针对固件实测后开启（`USE_MARGIN_SCHEME = false` 默认关闭）。

### 实时生效链路（v1.0.1）

```
SettingsActivity 写 SharedPreferences（<pkg>_preferences）
   └─ ContentProvider notifyChange ──► SystemUI / AOD 进程 ConfigStore
                                        └─ 主线程、300ms 去抖后派发 Listener
                                             └─ BaseSinkHook.onConfigChanged()：重刷已登记视图
```

配置读取：**ContentProvider（Binder 查询，主通道）→ XSharedPreferences（旧框架只读兜底）**。
变更派发带 300ms 去抖合并，任何异常高频回调都会被压平。

---

## 4. 构建与部署

**环境**：JDK 17+（21 亦可）、Android Studio Ladybug+ 或命令行 Gradle 8.11.1。

```bash
# 方式一：Android Studio 直接打开 NotificationSinking/ 目录，Build → Build APK(s)
# 方式二：命令行（首次会自动下载 Gradle 8.11.1 与 AGP）
gradle wrapper --gradle-version 8.11.1      # 若本机没有 wrapper jar
./gradlew :app:assembleDebug                # 产物 app/build/outputs/apk/debug/app-debug.apk
```

**部署**：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**已预构建 APK（可直接使用）**：项目根目录附带 `NotificationSinking-v1.3.0.apk`（release 签名、
minSdk 27 / targetSdk 36、versionCode 107），可直接 `adb install -r NotificationSinking-v1.3.0.apk` 安装。
所有版本使用**同一签名密钥**，覆盖安装即可平滑升级。升级后请在 LSPosed 中
**关闭再重新启用**本模块并确认作用域（入口布局变化需要框架重新扫描模块信息），然后重启作用域。
签名信息：

- keystore：`notificationsinking.keystore`（位于项目根目录，**请妥善保管**，后续升级必须使用同一密钥签名）
- 别名 `notificationsinking`，口令 `nsink2026`（见 `tools/build_apk.ps1`，可自行修改后重新构建）
- 证书 SHA-256：`b32eb8b9f0e05ec1a28795c70cd498b41570aa6658d85ae443dd6126143df298`

重新构建（无需 Gradle，已在 Windows 上验证通过）：

```bash
# 前置：tools\jdk17、android-sdk（platforms;android-36 + build-tools;36.0.0）、tools\downloads\api-82.jar
powershell -File tools\build_apk.ps1
```

该脚本用 aapt2 + javac + d8 + zipalign + apksigner 手工完成编译、dex、打包与签名，
输出覆盖项目根目录的 `NotificationSinking-v1.0.0.apk`。

1. 打开 LSPosed 管理器 → 模块 → 启用「通知下沉」；
2. 作用域勾选：**系统界面（com.android.systemui）**、**息屏（com.oplus.aod）**；
   如有 `com.oplus.exsystemui` 进程也可勾选（老固件兼容）；
3. 打开模块 App → 底部「作用域管理」点**重启 SystemUI / AOD 作用域**
   （需 Root 授权；或重启设备 / `adb shell su -c 'pkill -f com.android.systemui'`）；
4. 在 App 内打开总开关后立即生效。

> `scope.list` 仅作为**推荐作用域**（模块未在 module.prop 声明 `staticScope`，即动态作用域），
> 用户可随时在 LSPosed 管理器中自由勾选/取消。

---

## 5. 分阶段测试（按开发顺序，每步独立验证）

### 阶段一：锁屏下沉（先验证不崩溃）

1. 只打开「总开关」+「锁屏通知下沉」，下沉值 40dp；
2. 发送一条通知后锁屏，观察锁屏岛/胶囊通知整体下移约 40dp；
3. 下拉进入锁屏通知中心，检查展开/收起动画无跳变（三件套同偏移量保证差值不变）；
4. 解锁进入通知中心，确认**非锁屏**通知位置不变（`isKeyguardShowing()` 守卫）；
5. 关闭开关，确认布局还原。

```bash
adb logcat -s NotificationSinking:E
# 期望输出（主路径命中）：
# ConfigStore: initialized, state=enabled=true, lockscreen=true, aod=true, sinkDp=40
# HOOK OK com.android.systemui.shade.NotificationPanelViewController#getKeyguardNotificationStaticPadding (lockscreen_sink)
# HOOK OK com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl#getNtfTopPaddingInLockscreen (lockscreen_sink)
# HOOK OK ...OplusLockscreenShadeTransitionControllerExImpl#getNtfTopPaddingInLockscreenNtfCenter (lockscreen_sink)
```

### 阶段二：AOD 下沉

1. 打开「AOD 息屏通知下沉」；
2. 熄屏进入 AOD，发送通知，观察 AOD 通知下移；
3. 若视图无变化：检查日志中 AOD 候选类是否命中（`HOOK OK ... (view layout)`）；
   若无命中，按 §7 的反编译方法确认本机固件实际类名后补充候选表。

### 阶段三：实时同步验证

1. 拖动滑块，**不重启 SystemUI**，锁屏/AOD 通知位置实时变化（Tier-0 三个方法每次调用都读最新偏移；已登记视图即时重刷）；
2. `adb shell content query --uri content://com.example.notificationsinking.prefs` 核对配置值；
3. 关闭总开关 → 全部 Hook 卸载、布局还原（日志出现 `hooks detached & layout restored`）。

---

## 6. 日志输出与「类找不到」降级逻辑

所有类查找用 `XposedHelpers.findClassIfExists`，**找不到时静默跳过并写日志**，然后进入下一级回退：

| 日志（tag `NotificationSinking`） | 含义 | 后续行为 |
| --- | --- | --- |
| `ConfigStore: initialized, state=enabled=true, ..., sinkDp=40` | 配置读取成功（Provider 通道） | 按当前开关安装 Hook |
| `ConfigStore: changed, state=...` | 模块 App 侧配置发生变化 | 主线程去抖后派发，实时重刷 |
| `ConfigStore: ContentObserver registered on content://...` | Provider 监听就绪 | 配置写入后 onChange 触发 |
| `ConfigStore: no application context in this process` | 该进程无 App 上下文（如 system_server） | 不注入（v1.0.1 已不在 system_server 注入） |
| `LockscreenSink: class not found (skip): com.oplus.xxx` | 该类在本固件不存在 | 下一个候选；全部落空则该路径无效（不影响稳定性） |
| `LockscreenSink: non-view class skipped ...` | 候选是控制器而非视图 | 不 hook（v1.0.1 起控制器类一律跳过） |
| `HOOK FAIL 类#方法` | 类存在但方法不存在（固件差异） | 其余 Hook 不受影响 |
| `LockscreenSink: hooks detached & layout restored` | 总开关关闭，Hook 卸载 | 系统完全恢复原状 |

**如果所有层级全部落空**（日志中全是 `class not found` 且无 `dimen patched`），说明固件类名/资源名与候选不同。降级处理：

1. 拉取本机 SystemUI / AOD APK 反编译：
   ```bash
   adb pull /system_ext/priv-app/SystemUI/SystemUI.apk
   adb pull /system/app/AodService/AodService.apk   # 若存在
   # jadx / jadx-gui 搜索关键词：Capsule、LockScreenNotification、AodNotification、Island
   ```
2. 把找到的类名加入 `LockscreenSinkHook.CANDIDATE_CLASSES` / `AodSinkHook.CANDIDATE_CLASSES`；
   或在资源文件（`resources.arsc`）中搜索 `aod_notification` / `margin_top` 类 dimen 名，加入 `ResourceHook.DIMEN_*` 候选表；
3. 重编译安装，重启 SystemUI 验证。候选表是唯一需要按固件维护的地方，Hook 框架无需改动。

---

## 7. 已知风险

| 风险 | 说明 | 应对 |
| --- | --- | --- |
| SystemUI 崩溃 | ColorOS 16 SystemUI Hook 风险高 | 默认全关；所有 Hook 回调内层 try-catch（Throwable）；异常绝不冒泡；类查找全部动态化 |
| 开不了机 | 模块在开机早期崩溃 | LSPosed 安全模式：开机时**反复按音量减/加键**（视版本）临时禁用全部模块，进入系统后在管理器中禁用本模块 |
| 动画跳变 | 三件套只命中一部分 | Tier-0 三处始终以同一偏移量安装；单处失败不影响其余，但若某固件只存在其中一两个方法，动画差值可能变化 —— 如遇跳变请关闭锁屏下沉 |
| AOD 无效果 | AOD 通知视图类名随固件变化 | 按 §6 反编译本机固件补类名；ResourceHook 资源替换保留源码，确认安全后可手动启用 |
| 悬浮胶囊位置相反 | 底部锚定容器 + margin 方案方向反 | 默认方案是 translationY（无此问题）；使用 margin 方案前先实测 `MARGIN_SIGN` |
| ColorOS 日志被丢弃 | `Log.i/d/v/w` 不可见 | 统一 `Log.e` + XposedBridge.log 双写 |

**紧急恢复命令**（模块导致 SystemUI 反复崩溃时）：

```bash
adb shell su -c 'pm disable com.example.notificationsinking'   # 或直接卸载
adb shell su -c 'pkill -f com.android.systemui'
```

---

## 8. ColorOS 16 适配说明（版本差异）

- **目标环境**：Find X8（Android 16 / API 36，ColorOS 16.0.9.400）。Tier-0 主路径在
  ColorOS 16.1.0（Find X8 Ultra 同代固件，density≈2.96）上由参考模块实测通过；
  16.0.x 与 16.1.x 属同一代，类名一致概率高，本模块对不一致处全部有降级路径。
- **AOD 进程分布**：ColorOS 15/16 的 AOD 在 SystemUI 进程（`com.oplus.systemui.aod.*`）；
  作用域勾选 `com.oplus.aod` / `com.oplus.exsystemui` 以兼容老固件。
- **作用域（v1.0.1 收敛）**：仅 `com.android.systemui` / `com.oplus.exsystemui` / `com.oplus.aod`
  三个包。v1.0.0 的 `android`（system_server）与 `com.oplus.aodplugin` 注入与真机
  「重启后 CPU 高占用」相关，已移除（本模块不需要 framework 进程注入）。
- **入口布局（v1.2.1 起，遵循 LSPosed 现代 API 规范）**：入口 `META-INF/xposed/java_init.list`
  → `ModernEntry`（`io.github.libxposed.api.XposedModule` 子类）；作用域 `META-INF/xposed/scope.list`；
  配置 `META-INF/xposed/module.prop`（`minApiVersion` / `targetApiVersion` / `staticScope`）。
  模块名称与描述改用 `android:label` / `android:description`，**不再使用 `assets/` 下的
  `xposed_init` / `module.prop`**（LSPosed 2.x 只识别现代布局）。源码中仍保留传统入口
  `MainHook`（旧框架备用），但不在现代入口清单中声明。
- **16KB 页面**：Android 16 部分设备启用 16KB 页，老式 native 入口模块可能无法注入。
  本模块为**纯 Java 模块**（无 native 库），天然规避该问题。
- **LSPosed 版本**：需要支持 Android 16 的 LSPosed（1.10.x/1.11.x 或对应 mod 分支）。
  配置通道为 ContentProvider（Binder），不依赖 RemotePreferences。
- **注意**：部分 ColorOS 版本在「设置 → 通知」中有「锁屏通知方式（卡片/胶囊/隐藏）」选项，
  不同方式下通知容器不同；本模块覆盖三件套 + 胶囊候选两条路径。

---

## 9. 常见问题

**Q：装好并给了权限，为什么一点效果都没有？**
默认**全关**！必须在模块 App（通知下沉）里打开「总开关」，并确认 LSPosed 日志出现
`ConfigStore: initialized, state=enabled=true, ...`。只有 LSPosed 作用域权限、不开总开关，
模块不会安装任何 Hook（这是防开机崩溃的设计）。

**Q：打开开关后没变化？**
先确认 LSPosed 日志中 `HOOK OK` 输出；再确认模块在 LSPosed 中已启用且勾选了 com.android.systemui；
ColorOS 的 LSPosed 管理器日志在「日志」页可查（本模块双写 XposedBridge.log）。

**Q：滑块拖动时预览在动，锁屏没动？**
预览只模拟锁屏卡片；真实锁屏走三件套偏移，需锁屏状态下观察。
若 Tier-0 全部 `HOOK FAIL`，检查固件版本是否与候选方法签名不一致，按 §6 反编译补类名。

**Q：配置通道选哪个？**
无需选择。v1.0.1 为 ContentProvider（Binder）主通道 + XSharedPreferences 只读兜底，
日志会说明当前状态。两条通道读写同一 SharedPreferences 文件，值永远一致。

**Q：卸载后还有下沉效果？**
重启设备即可（Hook 随进程消失）。正常关闭开关路径会自动卸载 Hook 并还原布局。

---

## 10. 版本记录

### v1.3.1（双布局入口 + 诊断修复）

- **双布局打包**：同时携带传统布局 `assets/module.prop` + `assets/xposed_init`（→ 传统入口
  `MainHook`）与现代布局 `META-INF/xposed/{java_init.list,module.prop,scope.list}`（→ 现代入口
  `ModernEntry`）。真机日志显示同机 `me.yun.fkwechat`（模块名 `XposedBridge`）等传统模块仍被
  LSPosed 2.1.1 正常加载，故同时提供两套入口以覆盖新旧框架对元数据位置的不同要求；
  三件套顶部间距 Hook 增加跨入口去重（`Compat.tryClaim("padding:<pkg>")`），避免双入口同时
  加载时偏移被叠加两次。
- **修复日志导出被截断**：上一版把全部诊断拼成一条超长 root 命令，真机上 grep LSPosed 的
  大日志文件把 40s 总超时耗尽，导致 [3]/[4]/[5] 段根本没执行。v1.3.1 改为**分段执行**（
  `Diag.sections()`），每段独立超时、只对日志文件读尾部（`tail -c`），末尾加 `==== EOS ====`
  标记以便判断是否完整；`briefSections()` 提供一屏内的精简诊断。
- `assets/module.prop` 的 name/description 使用 ASCII（避免中文编码歧义；现代入口的显示名仍
  取 `android:label`=通知下沉）。

### v1.3.0（日志导出）

- **新增「导出日志为 txt 文件」**：一键采集完整诊断日志，写入
  `/sdcard/Download/NotificationSinking-log-<时间戳>.txt`（另在 App 私有目录留一份），
  弹窗中可「分享」（ACTION_SEND 到微信/邮件/电脑）或「复制路径」；
  经 `LogFileProvider`（无 androidx 依赖的最小实现）授权分享，比长截图可靠。
- **新增「查看精简日志（可直接截图）」**：只抓关键行（本模块日志 / LSPosed 提及本模块的行 /
  已装版本 / APK 入口文件清单），正常不超过一屏，便于直接截图。
- 诊断命令抽到 `Diag.full()` / `Diag.brief()`；完整日志新增「APK 入口文件」检查段。

### v1.2.1（现代入口布局修复 · 关键版本）

依据 LSPosed 官方文档 [Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
与真机日志（LSPosed 2.1.1）确认根因：**现代 API 的入口/元数据必须放在 `META-INF/xposed/`，
`assets/` 布局已不被识别**，且框架不再提供 `XposedHelpers` 等传统类。此前各版本把入口放在
`assets/`（`xposed_init` / `java_init.list` / `module.prop`），因此框架从未把本模块识别为模块，
表现为「无日志、无效果、CPU 异常」。

本版修复：

- 入口改为 `META-INF/xposed/java_init.list` → `ModernEntry`（`XposedModule` 子类）；
- 作用域改为 `META-INF/xposed/scope.list`；配置改为 `META-INF/xposed/module.prop`
  （`minApiVersion=101`、`targetApiVersion=102`、`staticScope=false`）；
- 删除全部 `assets/` 下的 LSPosed 文件，避免框架误判布局；
- Manifest 增加 `android:description`（现代 API 的模块描述来源）；
- 代码彻底摆脱传统类：`HookLog` / `Compat` / `ConfigStore` 对 `XposedBridge`、
  `AndroidAppHelper`、`XSharedPreferences` 全部改为反射调用，缺失即静默；
- 诊断中心：`[1]` logcat 过滤（剔除 App 自身窗口噪音）`[2]` LSPosed 日志目录与
  `log/*.log` 内容 `[3]` 用二进制 grep 检查 `modules_config.db` 是否登记本模块
  `[4]` 已安装 APK 的 versionCode/codePath `[5]` logcat 自检。

### v1.2.0（双入口兼容版）

真机日志证据：LSPosed 工作正常（另一模块 `com.suqi8.oshin` 已注入 `android` 进程），
但本模块在任何进程都**零日志**——说明模块从未被框架加载。据此改为双入口并升级诊断：

- **新增现代入口 `ModernEntry`**（`io.github.libxposed.api.XposedModule` 子类，`onModuleLoaded` /
  `onPackageLoaded`），使用现代 Hook API（`hook(Executable).intercept`，
  `ExceptionMode.PROTECTIVE`、`HookHandle.unhook()` 支持启停）；`java_init.list` 指向它；
- 传统入口 `MainHook` 保留（`xposed_init`），两者共存互不干扰（`Compat.tryClaim` 去重）；
- `module.prop` 增加 `targetApiVersion=102`、`autoHotReload=true`，与参考模块的现代声明对齐；
- 新增 `Compat`（不依赖 `AndroidAppHelper` 的 Context 获取）、`SinkState`（传统/现代共用的视图偏移状态机）；
- `ConfigStore` 读取顺序调整为 Provider（主）→ 现代远程偏好 → 传统 XSharedPreferences；
- 诊断中心升级：`[1]` 全量 logcat 大小写不敏感过滤本模块包名与 LSPosed；
  `[2]` 多路径探测 LSPosed 目录与模块日志；`[3]` 用 sqlite3（若可用）直读 LSPosed 配置库的
  模块启用/作用域记录；`[4]` logcat 自检。

### v1.1.2

- **移除 `assets/java_init.list`，仅保留传统 `xposed_init` 入口**：修复部分 LSPosed 版本
  优先读取 java_init.list 且只认新版 `XposedModule` 入口、导致传统入口类被忽略的问题
  （真机症状：模块不注入 → 无日志 → 无效果）；
- 诊断中心升级：全量抓取后 grep 过滤（含 info 级 LSPosed/Xposed 行）+ 直读 LSPosed
  模块日志文件（`/data/adb/lspd/log/modules/`）+ logcat 末尾 5 行自检，一次看清注入状态。

### v1.1.1

- **新增「诊断中心」卡片**：root 下抓取 `NotificationSinking` / `LSPosed` / `Xposed` 标签的
  日志并在弹窗中展示（可选中复制），支持「查看模块日志」与「重启作用域并抓取日志」两个按钮，
  排查「无效果 / 异常」无需连接电脑；卡片同时显示 App 侧当前配置状态。

### v1.1.0

- **新增「作用域管理」卡片与「重启 SystemUI / AOD 作用域」按钮**：root 下执行
  `pkill -f com.android.systemui` 等命令，修改 LSPosed 作用域或更新模块后
  一键重启相关进程，**无需整机重启**（首次使用需在 Root 管理器放行本 App；
  无 root 时弹窗给出 adb 手动命令）；
- **界面美化**：渐变头部卡片、白色分区卡片（功能开关 / 下沉距离 / 实时预览 / 作用域管理）、
  深色锁屏风格预览区、主题色按钮与滑块、分区标题与版本页脚。

### v1.0.1（稳定版）

依据 Find X8 / ColorOS 16 真机反馈（重启后 CPU 高占用 + 无效果）做风险收敛：

- **移除 LSPosed RemotePreferences 通道**（疑似监听风暴/读取失败源）→ 改为 ContentProvider + ContentObserver（与 coloros-mod 同方案，ColorOS 16 实测可靠），派发加 300ms 去抖；
- **移除全局 `View#onAttachedToWindow` 特征兜底 Hook**（每个视图 attach 都进回调，高风险面）；
- **移除控制器类触发方法 Hook**（方法名为推测，可能命中高频方法）；
- **ResourceHook 默认不再注册**（入口清单仅 MainHook），保留源码供手动启用；
- **作用域收敛**：去掉 `android`（system_server）与 `com.oplus.aodplugin`；
- 签名与 v1.0.0 一致，覆盖安装即可升级。

### v1.0.0（初版）

完整功能：Tier-0 三件套 + 类名候选 + 特征兜底 + 资源替换 + RemotePreferences 实时同步。
存在上述风险，不建议继续使用。

---

## 11. 免责声明

本模块仅调整通知布局偏移，不修改通知内容、不收集数据、不联网。
Hook 系统界面存在固有风险，请自行备份数据；因固件差异导致的异常请按本文档降级排查。
