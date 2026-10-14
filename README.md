# 通知下沉 · NotificationSinking

> ## ⚠️ 本项目由 AI 全程编写（AI 生成代码）
>
> 从需求梳理、逆向分析、Hook 实现、配置通道，到界面设计与构建脚本，**全部由 AI 助手（DeepSeek Harness 中的 deepseek 系列模型）在一次对话内完成**；人类参与者只负责真机测试、反馈现象与提供日志。
> 因此：代码风格、注释与调试痕迹都带有明显的"AI 迭代"特征。逻辑已在真机验证，但**不保证适配下列机型以外的固件**。
>
> 说人话：**这是 AI 写的模块，能用，但请把它当作"AI 出品的逆向工程实验品"**，自行评估风险。

---

## 这是什么

一个 **LSPosed 模块**：让 ColorOS 16 的**锁屏 / AOD 通知**像 iOS 那样"下沉"到屏幕下方，不再被超大时钟压住；并可选保持大时钟、折叠多条通知、切换系统自带的锁屏通知显示方式。

| 项目 | 值 |
|---|---|
| 实测机型 / 固件 | OPPO Find X8（PKB110）· ColorOS 16.0.9.400 · Android 16 / API 36 |
| 框架 | LSPosed 2.1.1（KernelSU + Zygisk） |
| 模块入口 | 现代 libxposed API（`META-INF/xposed/java_init.list`，`targetApiVersion=102`）+ 传统 `assets/xposed_init` 兼容入口 |
| 包名 | `com.example.notificationsinking` |
| 状态 | 功能可用（真机验证通过），**所有 Hook 默认关闭** |

## 功能一览

| 功能 | 说明 | 默认 |
|---|---|---|
| **通知下沉** | Hook 锁屏通知区顶部间距整体下移；**只在锁屏静止态叠加**，展开通知中心时完全不干预 | 关 |
| **有音乐卡片时自动让位** | 音乐卡片占住内容块顶部，此时下沉量自动减去 150dp，保证两者同屏 | 自动 |
| **保持大时钟** | 强制时钟尺寸决策返回大尺寸；下拉通知中心时自动让位（避免留在模糊层后） | 关 |
| **通知折叠** | 只保留最上面 N 条，其余收进系统自带的「+N 条」汇总行；面板展开时暂停折叠 | 关 |
| **锁屏通知显示方式** | 写入系统键 `lock_screen_notification_style`：`0`＝列表/堆叠，`1`/`2`＝胶囊 | 跟随系统 |
| **AOD 下沉** | 对 AOD 通知视图类按固件类名候选 Hook，类不存在时静默跳过 | 关 |
| **灵动胶囊固定复位** | 永不位移胶囊容器，并主动清零历史位移 | 强制 |

## 原理（逆向得到的关键结论）

ColorOS 16 上决定锁屏通知位置的是这三个方法——本模块的核心 Hook 点：

```
com.android.systemui.shade.NotificationPanelViewController#getKeyguardNotificationStaticPadding
com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl
        #getNtfTopPaddingInLockscreen
        #getNtfTopPaddingInLockscreenNtfCenter
```

实测要点（都是踩坑换来的）：

1. 这三个方法的返回值就是通知区/通知中心的顶部位置，**叠加偏移即可下沉**；
2. **展开动画期间系统基线会自己变大**（622 → 1309+），此时若仍叠加偏移，内容会被直接顶到屏幕底部（表现为"音乐卡片跳到最底再展开"）→ 必须"**只在静止态叠加**"；
3. ColorOS 16 的锁屏通知是 **capsule（胶囊）** 体系（`com.oplus.systemui.notification.lockscreen.capsule.*`）；`NotificationStackScrollLayout` 属于下拉面板，**平移它对锁屏无效**；
4. 对胶囊容器做 `translationY` 会被 `CapsuleScrollCalculator` 每帧覆盖 → **视觉位移方案在本固件不可行**；
5. 锁屏通知显示方式由系统键 `lock_screen_notification_style` 控制（`0`=列表/堆叠，`1`/`2`=胶囊），`settings put system` 即可切换；
6. 音乐卡片会占据通知内容块的**顶部**，因此有音乐卡片时必须**减少**下沉量，否则通知堆叠会被挤出屏幕。

## 安装

1. 安装 `dist/NotificationSinking-v1.13.0.apk`
2. LSPosed 中启用模块，作用域勾选：**系统界面**（`com.android.systemui`）、**息屏**（`com.oplus.aod`，可选 `com.oplus.exsystemui`）
3. 打开模块 App → 打开**总开关** → 按需调整下沉距离
4. 点「**重启 SystemUI / AOD 作用域**」（App 内一键，无需重启手机）

> APK 为自签名（`notificationsinking.keystore` **不随仓库分发**）。签名不一致时卸载重装即可（会清空配置）。

## 配置

既可在 App 界面调整，也可直接编辑**兜底配置文件**（三处任一，改动即时生效、无需重启）：

```
/data/adb/lspd/modules/0/com.example.notificationsinking/config.properties   # App 用 root 写入（SystemUI 直读 + FileObserver）
/data/adb/notificationsinking.conf
/sdcard/Download/notificationsinking.conf                                    # 无需 root，文件管理器即可改
```

```properties
enabled=1              # 总开关
lockscreen=1           # 锁屏下沉
aod=1                  # AOD 下沉
keep_clock=1           # 保持大时钟
fold_notif=1           # 通知折叠
fold_max=2             # 折叠后最多显示条数（1..6）
classic_cap_pct=100    # 下沉上限（屏高百分比）；>=100 表示不限制，仅保留"屏高-500px"可见性护栏
sink_dp=520            # 静止态下沉量（0..1200，步长 4）
sink_dp_media=-1       # 有音乐卡片时的下沉量；-1＝自动（sink_dp − 150dp）
lock_notif_style=-1    # 锁屏通知显示方式；-1＝不改系统键，0/1/2 写入系统键
```

配置读取优先级：`ContentProvider` → 框架远程偏好 → 兜底文件 → 传统 `XSharedPreferences`。

## 目录结构

```
NotificationSinking/
├── app/src/main/java/com/example/notificationsinking/
│   ├── ModernEntry.java        # 现代 libxposed 入口（java_init.list 声明）
│   ├── ModernHooks.java        # 全部 Hook 逻辑（下沉 / 折叠 / 保持大时钟 / 胶囊复位）
│   ├── ConfigStore.java        # 配置读取（Provider → 远程偏好 → 兜底文件 → XSP）
│   ├── ConfigWriter.java       # 配置写入（root 落三处）+ 系统键写入
│   ├── RemotePrefsBridge.java  # 现代框架 Remote Preferences 桥（XposedServiceHelper）
│   ├── DexMiner.java           # 从 SystemUI dex 挖本机真实类名（供"保持大时钟"补挂 Hook）
│   ├── SinkState.java          # 视图级下沉状态（translationY）
│   ├── Compat.java / HookLog.java
│   ├── Diag.java               # 一键诊断（logcat / LSPosed 日志 / 配置库 / dex 类名 / 系统键）
│   ├── PrefsProvider.java      # 配置跨进程只读通道
│   ├── LogFileProvider.java    # 日志 txt 导出与分享
│   ├── NsApp.java              # Application：启动日志 + 配置播种 + 偏好迁移
│   ├── MainHook.java / LockscreenSinkHook.java / AodSinkHook.java / BaseSinkHook.java
│   │                           # 传统 de.robv API 入口（兼容旧框架）
│   └── ui/SettingsActivity.java
├── app/src/main/resources/META-INF/xposed/   # 现代入口声明（java_init.list / scope.list / module.prop）
├── app/src/main/assets/                      # 传统入口（xposed_init / module.prop）
├── dist/                                     # 已构建 APK
├── docs/                                     # 开发过程记录（含旧版 README）
└── tools/                                    # 免 Gradle 构建脚本
```

## 自行构建

仓库内附带**免 Gradle** 的构建脚本（`aapt2 + javac + d8 + zipalign + apksigner`）：

```powershell
# 依赖：JDK 17、Android SDK（build-tools 36.0.0 + platforms/android-36）
#      以及 libxposed api(102) / service(102) 的 jar（放到 tools/downloads/）
powershell -File tools/build_apk.ps1
```

脚本内的 `$jdk` / `$sdk` / `$tools` 路径请按本机情况修改，产物输出到项目根目录。

## 已知限制（诚实清单）

- **只在这一个固件上验证过**：类名与键名和 ColorOS 版本强相关，换机型/换版本很可能需要重新挖类名（模块内置 dex 挖掘与诊断，见 `Diag.java`）；
- **"大时钟 + 通知位置很靠上"物理上不可兼得**：大时钟占据屏幕上部约 1/3，不缩小时钟就必须把内容放到它下面；
- 下沉越深，留给卡片的可见高度越少，超过"屏高 − 500px"会被护栏收回；
- **iOS / ColorOS 17 那种精致的"堆叠 / 列表"动画与样式属于系统层实现，本模块无法提供**（ColorOS 16 只有 `0=列表/堆叠`、`1/2=胶囊` 两套视图）；
- 视觉位移（`translationY`）方案在本固件会被系统动画覆盖，**已从代码中移除**；
- 开发过程中尝试过但**已删除**的方案：视觉模式、自动对齐、时钟上移、音乐卡片单独平移、堆叠紧密度——原因见 `docs/`。

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) 与 [libxposed](https://github.com/libxposed) —— 框架与 API
- [rikumi/coloros-mod](https://github.com/rikumi/coloros-mod) —— ColorOS Hook 的工程笔记，为本项目提供了重要参考

## 许可

MIT License，详见 [LICENSE](LICENSE)。

---

### AI 生成声明（再次强调）

本仓库的**全部源代码、构建脚本与本文档均由 AI 生成**；人类参与者提供需求、真机测试与日志反馈。
AI 可能产生错误或过时信息，使用前请自行审阅。因使用本模块导致的任何后果（包括但不限于系统异常、数据丢失、保修问题）**由使用者自行承担**。
