# LayerBridge App、安全与验收

| 项目 | 内容 |
|---|---|
| 状态 | 现行 |
| 更新日期 | 2026-10-29 |
| 所属 | [架构设计总览](lb-architecture.md) |
| 范围 | §14 App · §15 安全与信任 · §16 兼容性 · §17 里程碑 · §18 验收 · §19 风险 · §20 待确认 · 附录 |

> 上一篇：[运行时设计](lb-runtime.md)

## 14. App

### 14.1 页面

| 页面 | 内容 |
|---|---|
| 软件包 | 搜索、筛选（全部/已安装/可升级）、列表、详情、安装/卸载 |
| 仓库 | 启用开关、添加 URL/本地目录、更新索引、包数量 |
| 信任 | 根证书指纹、签发记录、撤销列表、导入根证书、受信仓库密钥（Termux/自定义） |
| 环境 | root 状态、前缀、模块状态、overlay/元模块状态、`lb` 可用性、磁盘占用 |
| 控制台 | 实时日志、取消、复制、安全拦截记录 |
| 设置 | 前缀路径、暴露后端（overlay/元模块/mkshrc）、前缀桥、清缓存、开发者模式 |

### 14.2 状态

- 单一 `MainViewModel` + `StateFlow`。
- 操作串行（Mutex），同一时间只允许一个安装/卸载。
- 失败可重试；安装过程中保持屏幕常亮。

### 14.3 root 识别

- 通过 `<queries>` 声明并检测：`com.topjohnwu.magisk`、`me.weishu.kernelsu`、`com.sukisu.ultra`、`me.bmax.apatch`。
- `su -c id -u == 0` 判断已授权。
- KernelSU/SukiSU 无弹窗：未授权时直接提示去管理器授权；Magisk 首次调用可能弹窗，超时给出提示。

### 14.4 信任与开发者模式

- 根证书：PC 端 `scripts/lb-sign.py init` 生成，复制为 `/data/adb/lb/trust/root.json`；未配置时本地包安装被拒绝。App 内引导与信任页未实现。
- 信任页未实现。当前无指纹查看、二级证书列表与撤销。
- 开发者模式：设置页连点版本号 7 次解锁，出现两个独立开关（强制签名、自动检测），并常驻警告条。
- 安装失败页：区分“未签名 / 链无效 / 过期 / 撤销 / 恶意检测命中”，提供查看日志与（仅开发者模式）放行入口。
- 恶意检测拦截记录：包名、来源、命中规则、处理结果（删除/隔离）。

---

## 15. 安全与信任

### 15.1 授权签名（两条路径）

默认只允许“授权签名包”。信任模型类似 HTTPS：

```
根证书（用户自签，私钥离线）
└── 二级证书（由根签发，可签包或再签叶证书）
    └── 包签名（二级/叶证书私钥）
```

- 信任锚：用户根证书公钥，固定在 `/data/adb/lb/trust/root.json`；只有链到该根的签名才被接受。
- 二级证书可签发给社区构建者；他们签的包视为授权包。
- 算法：ECDSA P-256 + SHA-256；证书为 JSON（语义对齐 X.509：链、有效期、用途、路径长度、包名前缀约束）。
- LBPA 签名在文件尾部 footer；deb 使用同名 `.sig` 分离签名。
- 校验顺序：魔数/header → manifest → 证书链 → 签名 → payload 哈希 → 解包。
- 撤销：根签名的 `revocations.json`，按 `serial`/`keyId` 拒绝。
- 失败处理：无签名（策略开）、链断、过期、用途/前缀不符、被撤销、签名/哈希错误 → 拒绝；签名错误同时删除缓存包。

**路径 B：仓库签名（Termux 等 apt 仓库）**

apt 仓库不对单个 deb 签名，而是对索引签名。lb 接受这条路径，默认信任 Termux 官方仓库：

- 信任仓库列表：`/data/adb/lb/trust/repos/<id>.json`，默认预置 `termux-main`。
- 条目内容：`url`、`keyFingerprint`、`keyFile`（公钥）、`suite`/`component`、`architectures`、`enabled`。
- 验证链：
  1. 取 `Release` + `Release.gpg`（或 `InRelease`），用固定指纹的 OpenPGP 公钥验证；
  2. 从已签名 `Release` 的 `SHA256` 段校验下载的 `Packages`；
  3. 用 `Packages` 里的 `SHA256` 校验 deb 文件；
  4. 检查 `Date` 新鲜度（默认 90 天，过期告警/拒绝）。
- Termux 指纹：`CC72CF8BA7DBFA0182877D045A897D96E57CF20C`（当前 `Release.gpg` 签名者）；密钥轮换需人工确认并更新条目。
- OpenPGP 验证用 BouncyCastle `bcpg`；不依赖系统 gpg。
- 未授权仓库：默认按未签名处理；自定义仓库信任未实现，目前只内置 Termux 官方一条。
- 两条路径关系：包签名有效 → 直接接受；包未签名但仓库已验证 → 接受；两者都无 → 拒绝。

与之前版本相比，Termux 官方 deb 现在默认可以安装（通过仓库签名），不再需要重签或开发者模式；其他未签名仓库仍需逐包签名或显式授权仓库。

### 15.2 包安全检查（恶意检测）

安装前做静态检查，默认开启；命中“明确恶意”规则时阻止安装并删除 lb 缓存中的包。

| 阶段 | 检查 |
|---|---|
| 结构（始终开启） | 路径穿越、绝对路径、设备/FIFO、重复条目、未知顶层目录、setuid/setgid、模块结构（`module.prop`/`customize.sh`） |
| 脚本（扫描开） | `preinst/postinst/prerm/postrm`、`hooks/`、`files/` 内 shebang 脚本 |
| ELF（扫描开） | 可执行文件中的危险路径字符串（`/system`、`/dev/block`、`/data/adb`）、可疑符号 |
| 元数据（扫描开） | 包名/依赖异常、`expose` 覆盖系统命令、boot 钩子 |

规则分级：

- **阻断（明显恶意）**：`rm -rf /`、`rm -rf /system`、`rm -rf /data`、`dd ... of=/dev/block/*`、`mkfs*`、`wipe data`、`reboot recovery`、`setenforce 0`、写入 `/system`、篡改 `/data/adb/modules`、`/data/adb/ksu`、`/data/adb/magisk`、修改 `mkshrc` 等。
- **警告（可疑）**：下载即执行（`curl|sh`）、`base64 -d|sh`、`eval` 混淆、`chmod -R 777`、脚本内 `su`、开机钩子、覆盖系统同名命令。
- 警告不阻断，但 UI 明确展示；安装日志留档。

删除策略：

- lb 下载到 `/data/adb/lb/cache` 的包：命中阻断立即删除。
- 用户通过 SAF 导入的原始文件：移入隔离区并提示，确认后删除；避免误删用户数据。
- 误报：只能通过开发者模式关闭自动检测；关闭后仍执行结构校验。

### 15.3 开发者模式

- 入口：设置页连续点击版本号 7 次解锁。
- 两个**独立**开关，默认都开启：
  1. **强制授权签名**（`require_signature`）——关闭后允许未签名包；但“有签名却验证失败”仍拒绝。
  2. **自动恶意检测**（`malware_scan`）——关闭后跳过启发式规则；结构校验仍执行。
- 配置：`/data/adb/lb/config.json`，CLI 可改：`lb config set require_signature false`。
- UI 常驻警告条；关闭状态记录日志；重新开启立即生效。

### 15.4 通用安全

- 包名白名单：`^[a-z0-9][a-z0-9+.-]*$`；版本、架构同样校验。
- 所有拼接进 `su -c` 的路径/参数使用单引号转义；用户输入不直接进入 shell。
- 文件清单删除前做前缀内校验，拒绝绝对路径与 `..`。
- 下载必须校验 `SHA256`；HTTPS 优先，HTTP 仅用于镜像并在 UI 标注。
- 拒绝安装 root 管理器模块，防止绕过 lb 的热路径直接改 `/system`。
- **LBPA 先验魔数再解析**：header 越界、manifest 超 1 MiB、`formatVersion` 不支持、payload 哈希不符 → 拒绝。
- **LBPA 路径校验**：绝对路径、`..`、反斜杠、重复条目、未知顶层目录、设备/FIFO 条目 → 拒绝。
- **LBPA 解包防越界**：先解压到暂存目录并校验符号链接/硬链接，再合并进前缀；清除 setuid/setgid/sticky。
- wrapper 只允许指向 `/data/adb/lb/usr` 内的路径；生成时校验命令名与真实路径。
- 不提供任意命令执行 UI（终端除外，后续单独设计）。
- 模块/overlay 写入限定固定路径；拒绝跟随符号链接写入。

---

## 16. 兼容性与限制

- **仅 aarch64**（当前设备与仓库）；x86_64 模拟器不支持。
- 系统级暴露依赖 overlayfs 或元模块；两者都不可用时退化为交互 shell 环境。
- 环境变量不能注入已运行进程；lb 以“命令级 wrapper”满足 root 应用调用需求。
- 部分 Termux 包（Perl、带绝对路径的包）可能不可用，需前缀桥或重新编译。
- `app_process` 运行 CLI 依赖 App 已安装且 APK 副本存在；App 更新后需重新启动一次以刷新副本。
- `/data/local` 的 SELinux 上下文可能因 ROM 不同而拒绝执行；失败时回退 `/data/local/tmp/lb`。
- 模块 system 文件更新需要重启（除非元模块）；**包安装/卸载永远不重启**。
- LBPA 当前只支持 `formatVersion=1`、`xz`/`gzip` 压缩；更高版本或未知压缩必须由新版 lb 处理。
- **Termux 官方仓库通过仓库签名默认允许**；其他未签名仓库需逐包签名；仓库信任导入未实现。
- 仓库签名只保护元数据与哈希链，不证明单个包构建者身份；需要更强保证时使用包签名。
- 首次使用需先放好信任根（`/data/adb/lb/trust/root.json`），否则未签名本地包被拒绝；仓库包不受影响。
- 恶意检测为静态启发式，存在误报/漏报；开发者模式可关闭检测，但结构校验始终开启。
- 根私钥若在设备上生成，设备被攻破即信任链失守；推荐离线保管。
- 未在真机验证前，以上运行时行为均视为待验证。

---

## 17. 里程碑

| 阶段 | 内容 | 产出 |
|---|---|---|
| P0 | 工具链 spike：Gradle/AGP/Kotlin/Compose 与 NTFS/镜像环境 | 可构建空壳 |
| P1 | 核心纯逻辑：ar、control、索引、版本、依赖、重定位、状态库、wrapper 生成、LBPA 解析/打包、证书链验证、仓库 OpenPGP 验证、静态检测规则 | 单测全绿 |
| P2 | App：仓库、搜索、安装/卸载/升级、控制台、环境页、信任页、开发者开关 | 可安装 APK |
| P3 | 模块：overlay 挂载、`lb` 入口、mkshrc、App 写入模块 | `lb-module.zip` |
| P4 | 系统暴露：wrapper + upper 同步 + 元模块后端；CLI `LbMain`（含 pack/inspect/ca/sign/verify/trust/config） | 任意终端/root 应用可用（真机验证） |
| P5 | 前缀桥、GUI 包细节、性能与体验 | 可选能力 |

---

## 18. 验收

### 18.1 本机（当前无 adb 设备）

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_HOME="$HOME/Android/Sdk"

./gradlew --no-daemon :app:assembleDebug
# 期望：BUILD SUCCESSFUL，app/build/outputs/apk/debug/app-debug.apk 存在

./gradlew --no-daemon :app:testDebugUnitTest
# 期望：全部通过（版本、依赖、ar/control、索引、重写、状态库、wrapper 生成、
# LBPA 解析、证书链/签名、仓库 OpenPGP 验证、恶意规则、CLI 参数解析）

./gradlew --no-daemon :app:lintDebug
# 期望：0 Error

bash scripts/build-module.sh
unzip -l build/lb-module.zip
# 期望包含 module.prop、post-fs-data.sh、service.sh、system/bin/lb
```

### 18.2 真机清单

已在 Android 17 + SukiSU（内核 6.6）跑过 1–10；其余项待补。

1. 安装 APK，授予 root。
2. 模块管理器安装 `lb-module.zip` → 重启 → `su -c 'lb doctor'`：`module: true`、`entry` 为 `-rwx------`。
3. 非 root 进程 `stat /data/adb/lb` → `EACCES`；`ls /system/bin/lb` 只有名字。
4. 装根证书：PC 端 `scripts/lb-sign.py init`，把 `certs/lb-root.json` 复制为 `/data/adb/lb/trust/root.json`（0600）→ `lb doctor | grep trust` 为 true。
5. 装已签名本地 deb（配 `.sig`）→ 成功；`lb info` 的 `Signer` 显示证书 subject。
6. 装无签名本地 deb → 拒绝「未授权签名」。
7. 加 `--allow-unsigned` 重装 → 成功。
8. `su -c hello` 直接可用（root 软链）；`lb expose-root --remove` 后失效，`lb expose-root` 恢复。
9. `lb remove` 保留数据文件；`lb purge` 删除干净。
10. `lb upgrade` 升降级；旧版本文件按清单清理。
11. 安装中 `kill -9`，再 `lb remove` → 清理暂存、事务、wrapper、脚本。
12. Magisk 模块 zip 改后缀为 `.deb` → 魔数/结构校验拒绝。
13. 维护脚本含 `rm -rf /system` → 静态检测阻断。
14. 开发者模式关闭强制签名/自动检测，行为符合开关，重新开启后恢复。
15. 重启后确认前缀权限、`/system/bin/lb` 权限、root 软链、已装包状态保持。

---

## 19. 风险与回退

| 风险 | 影响 | 回退 |
|---|---|---|
| 系统级暴露开启后 overlayfs 不可用 | 命令不进 `/system/bin` | 退回 stealth；用 `lb exec` / `lb shell` |
| SELinux 拒绝 upper 执行 | root 应用无法运行命令 | 调整上下文/改用元模块；日志提示 |
| 与 Magisk/其他模块挂载冲突 | 系统不稳定 | 检测并让位；禁用自有 overlay |
| Termux 包硬编码路径 | 部分包损坏 | 告警 + 前缀桥；优先自编译包 |
| 维护脚本失败 | 包不可用 | 日志定位，重跑脚本；不阻塞其他包 |
| app_process 被 SELinux 拒绝 | CLI 失效 | App 仍可用；后续改静态二进制 CLI |
| 模块目录写入被管理器忽略 | 重启后无 `lb` | 提供 zip 手动安装 |
| 误删系统文件 | 系统损坏 | 文件清单严格限制在前缀内 |
| LBPA 解析器漏洞（路径穿越/解压炸弹） | 前缀外写入/磁盘耗尽 | 魔数+manifest 校验、暂存解包、路径/链接检查、大小上限 |
| 根私钥存放在设备 | 设备失守即信任链失守 | 默认引导导入离线根；设备生成时强警告、600 权限 |
| 恶意检测误报 | 正常包被阻断 | 展示命中规则、日志；开发者模式可关闭检测 |
| 删除本地导入文件 | 用户数据丢失 | 默认隔离 + 确认删除；仅缓存副本自动删除 |
| 依赖包未签名 | 安装链断裂 | 依赖同样走签名校验；失败整体拒绝并列出包 |
| Termux 仓库密钥泄露 | 该仓库所有包失去保障 | 可移除受信仓库/更新指纹；包签名仍可独立生效 |
| 设备时钟错误 | `Release` 新鲜度误判 | 超期只告警或可配置阈值；记录日志 |
| 构建环境（NTFS/沙箱） | 构建失败 | 工作区 Gradle home、`--no-daemon`、Kotlin in-process |

---

## 20. 待确认

1. 前缀默认 `/data/adb/lb/usr` 是否接受？
2. 系统暴露默认 **关闭**（ADR 0007）；需要时用自有 overlay，元模块作为备选。
3. 模块由 App 自动写入 `/data/adb/modules/lb` + 一次重启，同时提供 zip，是否接受？
4. 根私钥：只允许离线导入，还是允许 `lb ca init` 在设备生成并保存？
5. 本地导入的恶意包：默认隔离+确认删除，还是直接删除？
6. 二级证书默认绑定包名前缀（如 `com.example.`），是否接受？
7. Termux 官方仓库默认受信（固定指纹 `CC72…`），其他仓库需显式授权，是否接受？
8. 仓库公钥轮换时：要求手动确认新指纹，是否接受？

---

## 附录 A：命令示例

> 已实现：env、shell、exec、install、upgrade、remove、purge、list、info、doctor、expose-root。
> 其余命令（search、repo、pack、inspect、trust、expose）为规划。

```sh
# 搜索并安装
lb search hello
lb install hello

# 查看环境
lb env
. /data/adb/lb/env.sh

# 进入带环境的 shell
lb shell

# 任意 root 应用/终端直接调用（overlay 生效后）
hello

# 仓库
lb repo add community https://example.com/repo
lb repo list

# 安装本地 deb / LBPA
lb install /sdcard/Download/foo_1.0_aarch64.deb
lb install /sdcard/Download/foo_1.0_aarch64.lbpa

# 打包与检查
lb pack ./pkg-root --name foo --version 1.0-1 --arch aarch64 -o foo.lbpa
lb pack --from-deb foo.deb -o foo.lbpa
lb inspect foo.lbpa

# 暴露命令
lb expose my-gui-tool

# 自检
lb doctor
```

## 附录 B：状态库示例

`installed.tsv`：

```
hello	2.12.1-1	aarch64	lbpa	local	2026-10-28T05:00:00Z	Community A	pass	ok
bash	5.2.37-1	aarch64	deb	termux-main	2026-10-28T05:01:00Z	-	warn	ok
```

`info/hello.list`：

```
bin/hello
share/man/man1/hello.1.gz
```
