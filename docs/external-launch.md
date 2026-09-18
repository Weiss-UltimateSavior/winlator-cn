# 外置跳转启动 exe 与运行时参数覆盖 —— 设计文档

> 状态：**P1 / P2 已实现**（含 `save=true` 持久化容器配置与**目录传递/自动盘符挂载**）；P3 的 `ACTION_VIEW` / `content://` 未实现
> 目标版本：11.2.cn.x
> 影响面：纯 Java，无 native、无构建流程改动（`.github/workflows/*`、`.cnb.yml` 均不受影响）

## 0. 实现索引

| 内容 | 位置 |
|---|---|
| 外部入口 Activity | `app/src/main/java/com/winlator/ExternalLaunchActivity.java` |
| 路径解析、盘符分配/拼接、挂载点探测 | `app/src/main/java/com/winlator/core/LaunchPathResolver.java` |
| 覆盖参数白名单与校验、运行时取值 | `app/src/main/java/com/winlator/container/LaunchArgs.java` |
| 临时盘符不落盘（`setTransientDrives`） | `app/src/main/java/com/winlator/container/Container.java` |
| 会话接入（三级优先级 / onNewIntent 重启 / 目录启动） | `app/src/main/java/com/winlator/XServerDisplayActivity.java` |
| 设置开关 | `SettingsFragment.java`、`res/layout/settings_fragment.xml`（`CBAllowExternalLaunch` / `CBExternalLaunchConfirm`） |
| 字符串 | `res/values/strings.xml`、`res/values-zh/strings.xml` |
| Manifest | `AndroidManifest.xml`（`ExternalLaunchActivity` + `winlator://launch`） |

## 1. 背景与目标

Winlator-CN 当前只能从应用内部启动 exe：

- 容器文件管理器点击文件 → `ContainerFileManagerFragment.java:375-390` → `XServerDisplayActivity` + `exec_path`
- 快捷方式 → `ShortcutsFragment.runFromShortcut()` / `ShortcutLauncherActivity.java:11-34` + `shortcut_path`

而 `XServerDisplayActivity` 是 `exported="false"`（`AndroidManifest.xml:43-49`），外部应用（Tasker、游戏前端启动器、adb 脚本、浏览器）无法显式跳转启动，也无法在启动时临时调整配置。

本设计新增一个导出的入口 Activity，实现：

1. 外部应用通过显式 Intent / deeplink 启动容器内任意 exe（含参数）
2. 三级配置优先级：**容器配置 < 快捷方式配置 < 启动时临时覆盖**（覆盖不落盘）
3. 复用现有全部机制，不改变现有行为

**明确不做**（避免范围膨胀）：

- 不导出 `XServerDisplayActivity`，保留 `exported="false"`
- 不允许外部应用默认持久化修改容器配置（Phase 3 可选，见 §11）
- 不改 `Container` 落盘格式（`config.json` 不动）

## 2. 现有可复用基础

| 机制 | 位置 | 说明 |
|---|---|---|
| `exec_path` 直启 | `XServerDisplayActivity.java:246-247, 263, 1063-1070` | exe 路径经 `WineUtils.unixToDOSPath()` 映射后交给 winhandler |
| 快捷方式覆盖 | `XServerDisplayActivity.java:221-243`、`Shortcut.java:101-122` | screenSize/graphicsDriver/dxwrapper/envVars/execArgs 等 18 项覆盖 |
| 环境变量运行时注入 | `XServerDisplayActivity.java:623-625, 1113-1116` | `overrideEnvVars` 机制 |
| 导出的跳板 Activity | `ShortcutLauncherActivity.java` + `AndroidManifest.xml:51-55` | 校验后转发到 XServerDisplayActivity 的既有模式 |
| 路径映射 | `WineUtils.java:262-286`（`unixToDOSPath` / `dosToUnixPath`） | drives 映射与 `C:`/`Z:` 处理 |

## 3. 总体架构

```
外部应用 ──Intent/deeplink──▶ ExternalLaunchActivity (新, exported)
                                   │ 1. 协议解析 / 白名单校验 / 路径解析
                                   │ 2. 容器定位
                                   │ 3. 确认弹窗（可跳过）
                                   ▼
                          XServerDisplayActivity (内部, exported=false)
                                   │ LaunchArgs 中间层
                                   │ 优先级: intent overrides > shortcut > container
                                   ▼
                          现有 XEnvironment 启动链路
```

核心原则：**解析与执行分离**。`ExternalLaunchActivity` 只负责「外部协议 → 内部标准 extra」；`XServerDisplayActivity` 只理解内部 extra。外部协议演进不污染会话核心。

## 4. 外部接口契约

### 4.1 显式 Intent（Phase 1，主要形态）

```java
Intent i = new Intent();
i.setComponent(new ComponentName("com.winlator",
        "com.winlator.ExternalLaunchActivity"));
i.putExtra("container_id", 1);
i.putExtra("exe_path", "D:\\Games\\game.exe");
i.putExtra("exec_args", "-windowed -novid");
i.putExtra("overrides", "{\"screenSize\":\"1920x1080\",\"dxwrapper\":\"dxvk-2.4.1\"}");
startActivity(i);
```

| extra | 类型 | 必填 | 说明 |
|---|---|---|---|
| `container_id` | int | 三选一 | 容器 id |
| `container_name` | String | 三选一 | 容器名，忽略大小写精确匹配 |
| `shortcut_path` | String | 三选一（可与容器指定叠加） | 复用现有 `.desktop`，其 extras 作为中间层 |
| `exe_path` | String | 与 shortcut_path 二选一 | exe 路径，支持 DOS / Unix / `file://` 三种格式（§7）；可为相对 `dir_path` 的文件名 |
| `dir_path` | String | 否 | 要挂载并打开的目录（如 `/sdcard/galgame`）；可单独使用（打开该目录）或与 `exe_path` 组合 |
| `exec_args` | String | 否 | 追加命令行参数 |
| `overrides` | String(JSON) | 否 | 批量覆盖配置，白名单见 §5 |
| `confirm` | boolean | 否 | 是否弹确认框；`false` 仅在全局设置允许时生效（§10） |
| `save` | boolean | 否 | 除本次启动外，把覆盖项**写入容器配置**（`Container.saveData()`）；始终弹确认框 |
| `launch_id` | String | 否 | 调用方自定义标识，用于日志与防抖 |

**为什么 `overrides` 用 JSON 而不是 `KeyValueSet`**：`KeyValueSet` 以逗号分隔（见 `Container.java:30-32` 的驱动格式），而 `execArgs`、Windows 路径、环境变量值经常含逗号与空格，转义成本高。项目已依赖 `org.json`（`Container.java:14`），JSON 更稳。常用参数保留独立 extra，降低调用方拼 JSON 的门槛。

### 4.2 Deeplink（Phase 2）

```xml
<!-- AndroidManifest.xml, ExternalLaunchActivity -->
<intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="winlator" android:host="launch"/>
</intent-filter>
```

```
winlator://launch?container=1&exe=D%3A%5CGames%5Cgame.exe&args=-windowed&overrides=%7B...%7D
```

Query 参数映射到 §4.1 同名 extra（`container`→`container_id`，`exe`→`exe_path`，`args`→`exec_args`），URL decode 后走**同一套校验**，不提供任何绕过确认的捷径。

### 4.3 文件关联（Phase 3，未实现）

`ACTION_VIEW` + mime `application/x-msdos-program` / `application/vnd.microsoft.portable-executable`，处理 `content://`（§7.3）。国产文件管理器多数支持显式 Intent，Phase 1 已覆盖主要场景。

### 4.4 调用示例

```bash
# adb 显式 Intent
adb shell am start -n com.winlator/.ExternalLaunchActivity \
  --ei container_id 1 \
  --es exe_path 'D:\Games\game.exe' \
  --es exec_args '-windowed' \
  --es overrides '{"screenSize":"1920x1080","dxwrapper":"dxvk"}'

# deeplink
adb shell am start -a android.intent.action.VIEW \
  -d 'winlator://launch?container=1&exe=D%3A%5CGames%5Cgame.exe&overrides=%7B%22screenSize%22%3A%221920x1080%22%7D'

# 容器名定位 + 持久化覆盖（会弹确认框）
adb shell am start -n com.winlator/.ExternalLaunchActivity \
  --es container_name 'MyContainer' \
  --es exe_path '/storage/emulated/0/Download/game.exe' \
  --es overrides '{"box64Preset":"PERFORMANCE"}' \
  --ez save true
```

外部调用方（Tasker、游戏前端）只需要 EXTRA 或 URL query，内部标准 extra 由 `ExternalLaunchActivity` 负责转换。

目录传递示例：

```bash
# 只传目录：自动挂载 W: 并打开该目录
adb shell am start -n com.winlator/.ExternalLaunchActivity \
  --ei container_id 1 --es dir_path '/sdcard/galgame'

# 目录 + 相对 exe
adb shell am start -n com.winlator/.ExternalLaunchActivity \
  --ei container_id 1 --es dir_path '/sdcard/galgame' --es exe_path 'Game/game.exe'

# exe 不在映射内：自动挂载其所在目录
adb shell am start -n com.winlator/.ExternalLaunchActivity \
  --ei container_id 1 --es exe_path '/sdcard/galgame/Game/game.exe'

# deeplink
adb shell am start -a android.intent.action.VIEW \
  -d 'winlator://launch?container=1&dir=%2Fsdcard%2Fgalgame&exe=Game%2Fgame.exe'
```

## 5. Overrides 白名单

与 `ShortcutSettingsDialog.java:147-193` 的写入口径完全对齐：**快捷方式能配的，外置启动也能覆盖**。

| key | 取值 / 校验 | 现有消费点 |
|---|---|---|
| `screenSize` | `ScreenInfo` 解析，宽高 ≥ `MIN_WIDTH/MIN_HEIGHT`（320x160，`ScreenInfo.java:8-9`） | `XServerDisplayActivity.java:229` |
| `screenOrientation` | `landscape` / `portrait` | `:231` |
| `swapResolution` | `true` / `false` | `:233` |
| `graphicsDriver` | `GraphicsDrivers` 标识符 | `:222` |
| `graphicsDriverConfig` | 原样透传（驱动内部校验沿用现有逻辑） | `:227` |
| `dxwrapper` | `DXWrappers.parseIdentifier` | `:224` |
| `dxwrapperConfig` | `KeyValueSet` 格式 | `:226` |
| `audioDriver` | `AudioDrivers` | `:223` |
| `audioDriverConfig` | `KeyValueSet` 格式 | `:228` |
| `wincomponents` | `KeyValueSet` 格式 | `:225` |
| `drives` | 盘符串 `L:pathL:path`，盘符仅 A–Z 且排除 C/X/Z，路径须以 `/` 开头且不含 `..`，最多 `MAX_DRIVE_LETTERS` 个 | `WineUtils.createDosdevicesSymlinks` |
| `envVars` | `EnvVars` 格式（空格分隔 `K=V`） | `:574` |
| `execArgs` | 字符串 | `:1053` |
| `box64Version` | 必须存在于 `DefaultVersion.BOX64` 或已安装列表 | `:578` |
| `box64Preset` | `Box64Preset` 校验 | `:577` |
| `controlsProfile` | int id，`0` = 显式禁用 | `:682-689` |
| `dinputMapperType` | `0` / `1` | `:249-250` |
| `forceFullscreen` | `0` / `1` | `:646` |
| `toggleFullscreen` | `0` / `1` | `:647` |

校验策略：

- 未知 key → 忽略，并在确认弹窗 / 日志中提示
- 非法值 → **拒绝整个启动**并 toast 指明 key，不做静默降级
- 破坏性强的项（`box64Version` 等）校验不通过直接拒绝

## 6. 容器定位策略

优先级从高到低：

1. `container_id` → `ContainerManager.getContainerById()`（`ContainerManager.java:234`）
2. `container_name` → 遍历 `getContainers()` 精确匹配（`ContainerManager.java:39`）
3. 仅 `shortcut_path` → 由 shortcut 所在目录推断（与 `ShortcutsFragment.runFromShortcut()` 等价）
4. 均未指定 → **最近使用容器**：读 `home/xuser` symlink 指向的 `xuser-N`
   （`ContainerManager.activateContainer()` 维护该 symlink，`ContainerManager.java:67-72`）
5. symlink 不存在（从未启动过）→ 报错并引导打开主界面

> 关键约束：`activateContainer()` 是全局单例状态（切换 symlink），同一时刻只允许一个会话，与现有 singleTask 语义一致（§9）。

## 7. 路径解析与目录传递

`com.winlator.core.LaunchPathResolver` 负责归一化；`ExternalLaunchActivity` 负责决定挂载。

### 7.1 默认盘符与自动挂载

容器默认只有 `D:`（Download）与 `E:`（应用内部存储），游戏常放在 `/sdcard/galgame` 等其他目录。
本实现采用**自动临时挂载**（不拷贝文件）：

1. 输入路径已在 drives 映射内 → 行为不变
2. 不在映射内但存在：
   - 文件 → 挂载其**所在目录**，`exe_path` 归一化为原 unix 路径
   - 目录 → 挂载该目录；`exe_path` 即目录时以「目录模式」启动
3. 空闲盘符从 `W:` 递减分配（跳过保留的 `C`/`X`/`Z` 与容器已用盘符）
4. 生成 `drives` 覆盖注入本次会话：`D:...E:...W:/sdcard/galgame`
5. `save=false`（默认）时**只对本次会话生效**：`Container.setTransientDrives()` 确保会话期间
   `container.saveData()` 仍写入原始 drives（不会泄漏）；`save=true` 才调用 `setDrives()` 持久化

### 7.2 `dir_path` 组合用法

| 输入 | 行为 |
|---|---|
| 仅 `dir_path=/sdcard/galgame` | 挂载并**打开该目录**（`wfm.exe`） |
| `dir_path` + `exe_path=game.exe`（相对） | 挂载目录并以 `<mount>/game.exe` 启动 |
| `dir_path` + `exe_path=F:\game.exe`（未占用盘符） | 把目录挂到指定盘符 `F:` 并启动 `F:\game.exe` |
| `dir_path` + `exe_path=C:\windows\wfm.exe`（已映射） | 挂载目录，exe 仍走既有映射 |

一期限制：一次启动最多新增 **1 个**额外目录，超过或盘符冲突直接报错。

### 7.3 DOS 路径

- 匹配 `^[A-Za-z]:[\\/]`，直接使用
- 校验盘符 ∈（container drives ∪ `C` / `Z`）；未知盘符且未配合 `dir_path` 仍报 `ERROR_UNMAPPED`
- 宿主侧无法校验 `.lnk` 目标是否存在，运行时由 wine 报错，确认弹窗中提示

### 7.4 Unix 路径 / file://

- `file://` 先转 path
- 调 `WineUtils.unixToDOSPath()`（`WineUtils.java:262-286`）
- unix 路径可在宿主侧 `File.exists()` 校验，不存在时给明确错误
- 目录启动命令：`/dir C:\windows "wfm.exe" "<DOS 目录>"`
  （WFM 源码 `WinMain`：`numArgs > 1` 时以 `args[1]` 为起始目录；exe 保持裸文件名是既有已验证形式）

### 7.5 content:// （Phase 3，未实现）

- 优先取 `_data` 列拿到真实路径
- 拿不到 → 复制到 `AppUtils.getInternalStorage()`（即 E 盘，见 `AppUtils.java:63-67`）下的临时目录再启动

### 7.6 与现有 exec_path 的兼容

- `ContainerFileManagerFragment.java:383` 继续传 unix 格式 `exec_path`，原逻辑不变
- 外置入口把解析结果归一化成 unix `exec_path`，落到同一 extra
- 目录型 `exec_path` 仅由外置入口产生；内部入口不会传目录

## 8. `XServerDisplayActivity` 改造：LaunchArgs 中间层

### 8.1 现状问题

配置解析散落成三元表达式 `shortcut != null ? shortcut.getExtra(k, container.getX()) : container.getX()`（`:221-250`、`:577-578`、`:646-647`、`:682-689`），且多处用 `shortcut != null` 做分支（`:263`、`:569`），无法插入第三层。

### 8.2 新增 `LaunchArgs`

```java
public class LaunchArgs {
    private final JSONObject overrides;   // 可空（来自 intent "launch_overrides"）
    private final Shortcut shortcut;      // 可空
    private final boolean hasExecPath;    // 是否存在有效 exec_path

    public String getExtra(String name, String fallback) {
        if (overrides != null && overrides.has(name)) return overrides.optString(name, fallback);
        if (shortcut != null) return shortcut.getExtra(name, fallback);
        return fallback;
    }

    public boolean has(String name) { /* overrides > shortcut > hasExecPath */ }
}
```

### 8.3 改动清单（最小侵入）

| 位置 | 现状 | 改为 |
|---|---|---|
| `:201-202` | 加载 shortcut | 不变，另解析 `launch_overrides` JSON |
| `:204-255` | 三元表达式 | `launchArgs.getExtra(k, container.getX())` |
| `:242-247` | `win32AppWorkarounds` 双分支 | 合并：按最终 exec 路径 basename 应用 |
| `:263` | `shortcut != null \|\| exec_path` | `launchArgs.hasExecutable()` |
| `:569` | `desktopName` 判断 | 同上（无 exe 才用 `shell`） |
| `:574-578` | shortcut 独占 | `launchArgs.getExtra("envVars", container.getEnvVars())` 等 |
| `:646-647` | shortcut 独占 | `launchArgs.getExtra("forceFullscreen","0")` |
| `:682-689` | shortcut / container 分支 | 合并为 `launchArgs.getExtra("controlsProfile", container.getExtra(...))` |
| `:1047-1091` | `getWineStartCommand()` | 支持 `exec_args` extra 合并进 `execArgs` 逻辑 |

预计改动 ~60 行，`LaunchArgs` 本身 ~70 行。

### 8.4 环境变量合并顺序（保持现有语义）

`container.envVars` → `launchArgs.envVars` → `overrideEnvVars`（现有运行时注入，`:623-625`）；`EXTRA_EXEC_ARGS` 特殊处理保留（`:1086-1089`）。

## 9. onNewIntent（singleTask 会话重启）

`XServerDisplayActivity` 是 `singleTask`（`AndroidManifest.xml:47`）且**未实现 `onNewIntent`**：会话运行中二次外置启动会静默丢参数。必须处理。

已实现（`onNewIntent` 内）：

1. 非外置启动（没有 `external_launch=true` 标记）→ 沿用默认行为（快捷方式重复点击不会打扰当前会话）
2. 外置启动 → 弹确认「结束当前会话并启动新的程序？」
3. 相同 `launch_id` 重复到达 → 静默忽略（防抖）
4. 确认后：
   - 先摘掉 `GuestProgramLauncherComponent` 的终止回调，避免停环境 kill guest 时触发 `exit()` 再次重启应用
   - 把新 Intent 存入静态 `pendingLaunchIntent`，调用 `recreate()`
   - 旧实例 `onDestroy` 停环境（`environment.stopEnvironmentComponents()`），新实例 `onCreate` 消费静态 Intent 并 `setIntent()`

采用 `static pending + recreate()` 而不是 `finish() + startActivity()`：`singleTask` 下对正在 finish 的实例再 `startActivity` 可能被重新投递回旧实例，而 `recreate()` 保证 `onDestroy` 一定先于新 `onCreate`，环境清理与新会话初始化不会并发。

未实现：暂不区分「调用方已在入口确认过」与「会话内二次确认」，已运行会话收到外置请求会再弹一次确认框。

## 10. 安全设计

| 措施 | 说明 | 状态 |
|---|---|---|
| 全局开关 | 设置项「允许外部应用启动（Intent / 深链）」，默认**开**；关闭后 `ExternalLaunchActivity` 直接拒绝并 toast | 已实现 `PREF_ALLOW_EXTERNAL_LAUNCH` |
| 确认弹窗 | 默认每次弹窗：容器名、程序路径、覆盖项、调用方包名；`save=true` 时额外提示会写入容器配置 | 已实现 `PREF_EXTERNAL_LAUNCH_CONFIRM` |
| 免确认 | 仅当调用方 `confirm=false` **且** 全局「外部启动时弹窗确认」已关闭时跳过；`save=true` 强制确认 | 已实现（未实现按调用方「记住」） |
| 白名单 | §5 之外：忽略并写 logcat（`Ignored unknown overrides`） | 已实现 |
| 非法值 | 拒绝整个启动并 toast 指明 key（如 `Invalid launch overrides: box64Version`） | 已实现 |
| 路径限制 | DOS 路径只校验盘符映射；unix 路径校验 drive 映射 + 文件存在 + 拒绝 `..` 段 | 已实现 |
| 暴露面 | 仅新增一个 Activity，无新增 exported 服务 / Provider；`XServerDisplayActivity` 保持 `exported=false` | 已实现 |
| 日志 | `ExternalLaunch` tag 记录调用方包名（`getReferrer()`）与参数摘要 | 已实现 |

不采用 signature 级权限：会挡住 Tasker / 前端启动器这类目标用户，与需求冲突。

## 11. 分期与工作量

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | `ExternalLaunchActivity`（解析、校验、确认、转发） | ✅ 已实现 |
| P1 | `LaunchArgs` + `XServerDisplayActivity` 接入 | ✅ 已实现 |
| P1 | `LaunchPathResolver` 路径解析与校验工具 | ✅ 已实现 |
| P1 | Manifest 注册 + 设置开关 + strings（values / values-zh，其余语言回退英文） | ✅ 已实现 |
| P2 | deeplink intent-filter + 参数映射 | ✅ 已实现 |
| P2 | `onNewIntent` 会话重启 | ✅ 已实现（static pending + recreate） |
| P3 | ACTION_VIEW / `content://` | ❌ 未实现 |
| P3 | `save=true` 持久化容器配置 | ✅ 已实现（仅容器级字段；`execArgs` / `forceFullscreen` / `toggleFullscreen` 为会话级，不写入） |
| 扩展 | 目录传递：`dir_path`、未映射路径自动挂载、`drives` 覆盖、目录模式启动 | ✅ 已实现 |

**已实现与原设计的差异**：

- 「确认弹窗可勾选记住」未实现，替换为全局设置开关「外部启动时弹窗确认」
- `onNewIntent` 采用 `static pending + recreate()`（§9）
- `save=true` 会先写容器配置再启动会话；若此时已有会话在运行，用户在会话重启确认框取消时，容器配置已被写入（`save` 是调用方显式请求，视为可接受）
- `content://` 路径直接拒绝（`ERROR_FORMAT`），不做 SAF 拷贝

## 12. 测试矩阵

- **入口**：adb `am start` / Tasker / 浏览器 deeplink / 无调用方
- **容器**：id、name、最近使用、不存在、多容器未指定
- **路径**：`D:\a.exe`、`/storage/emulated/0/Download/a.exe`、`file://`、不存在、drive 外路径、`.lnk`、中文文件名、带空格参数
- **覆盖**：每个 key 单独生效、非法值拒绝、未知 key 忽略、overrides 与 shortcut 叠加的优先级
- **会话**：冷启动、会话中二次启动（确认 / 取消）、竖屏容器、`generate_wineprefix` 分支不受影响
- **安全**：开关关闭时拒绝、免确认开关、确认弹窗「记住」
- **回归**：容器文件管理器直启、快捷方式启动、桌面图标启动

## 13. 验证记录（模拟器）

环境：Pixel_Tablet AVD（arm64-v8a / API 35）+ 完整 native debug 构建（`assembleDebug`）。

| 场景 | 结果 |
|---|---|
| 不存在的 `container_id` | ✅ toast `Container not found` |
| 未映射盘符 `X:\nope.exe` | ✅ toast `Path is not inside the container drives` |
| 映射内但文件不存在 | ✅ toast `File not found` |
| overrides JSON 损坏 / 非法值 | ✅ toast `Invalid launch overrides: <key>` |
| 未知 key | ✅ 忽略并写 logcat `Ignored unknown overrides` |
| 合法启动 | ✅ 确认弹窗显示容器/路径/覆盖项/来源，确认后 wfm.exe 正常渲染 |
| overrides 生效 | ✅ `screenSize=640x360` 会话窗口明显变小；`graphicsDriver=turnip` 生效 |
| 会话中二次外置启动 | ✅ 弹「结束当前会话并启动新的程序」，确认后会话重建 |
| `save=false` | ✅ 容器 `.container` 配置未被改动 |
| `save=true` + `confirm=false` | ✅ 仍强制确认；确认后 `screenSize`/`graphicsDriver`/`box64Preset` 写入容器配置 |
| 全局关闭「外部启动时弹窗确认」 | ✅ 入口确认弹窗被跳过 |
| deeplink `winlator://launch?...` | ✅ 参数解析并转发成功 |
| 相同 `launch_id` 重复请求 | ✅ 会话内静默忽略，不打扰当前会话 |
| 全局关闭「允许外部应用启动」 | ✅ toast `External launch is disabled in Settings`，不启动 |

**模拟器已知问题（与本次改动无关）**：容器默认 `graphicsDriver=vortek,zink` 时，从 UI 正常启动容器也会在
`GPUHelper.vkGetApiVersion()` → `vkGetPhysicalDeviceProperties` 处原生 SIGSEGV（模拟器 SwiftShader Vulkan 探测）。
绕过方式：外置启动覆盖 `{"graphicsDriver":"turnip"}`（本页测试即用此方式）。真机（Adreno）不受影响。

### 13.1 目录传递实机验证（Huawei MAA-AN10 / Android 16，Adreno）

| 场景 | 结果 |
|---|---|
| `exe_path=/sdcard/galgame/wfm.exe`（不在映射内） | ✅ 自动挂载 `W: /sdcard/galgame`，会话内 WFM 磁盘列表出现 W: |
| `dir_path=/sdcard/galgame`（仅目录） | ✅ 自动挂载并**直接打开该目录**（WFM 显示 39 个游戏目录） |
| `dir_path` + `exe_path=wfm.exe`（相对） | ✅ 解析为 `/sdcard/galgame/wfm.exe` 并启动 |
| `save=false`（默认） | ✅ 会话运行/保存配置后 `.container` 的 drives 仍是原始值，无 W: 泄漏（`setTransientDrives`） |
| `save=true` | ✅ 确认后 `W:/sdcard/galgame` 写入容器配置 |
| 会话运行中再次外置启动（标准 `am start`） | ✅ 弹出入口确认框；确认后弹会话重启确认；重启后 W: 正常 |
| deeplink / `launch_id` 防抖 / 非法参数 | ✅ 与模拟器一致 |
| `android:taskAffinity=""` | ✅ 修复了部分设备（EMUI）上会话在顶时入口 Intent 被投递到会话而丢弃的问题 |

## 14. 附录：关键代码索引

| 功能 | 文件:行 |
|---|---|
| 外部入口（解析/校验/确认/转发/save） | `app/src/main/java/com/winlator/ExternalLaunchActivity.java` |
| 覆盖白名单与校验 | `app/src/main/java/com/winlator/container/LaunchArgs.java` |
| 路径解析与校验 | `app/src/main/java/com/winlator/core/LaunchPathResolver.java` |
| 会话核心 onCreate | `app/src/main/java/com/winlator/XServerDisplayActivity.java:146-303` |
| 快捷方式覆盖解析 | `XServerDisplayActivity.java:221-243` |
| 启动命令组装 | `XServerDisplayActivity.java:1047-1091` |
| overrideEnvVars | `XServerDisplayActivity.java:623-625, 1113-1116` |
| 会话销毁清理 | `XServerDisplayActivity.java:368-373` |
| 跳板先例 | `app/src/main/java/com/winlator/ShortcutLauncherActivity.java` |
| 容器激活 symlink | `app/src/main/java/com/winlator/container/ContainerManager.java:67-72` |
| Shortcut extras | `app/src/main/java/com/winlator/container/Shortcut.java:101-144` |
| 覆盖项写入口径 | `app/src/main/java/com/winlator/contentdialog/ShortcutSettingsDialog.java:147-193` |
| 路径映射 | `app/src/main/java/com/winlator/core/WineUtils.java:262-286` |
| 内部存储路径（E 盘） | `app/src/main/java/com/winlator/core/AppUtils.java:63-67` |
| Manifest | `app/src/main/AndroidManifest.xml:43-55` |
