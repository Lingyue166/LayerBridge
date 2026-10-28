# LayerBridge（lb）设计文档

| 项目 | 内容 |
|---|---|
| 文档版本 | v0.5（草案，待评审） |
| 更新日期 | 2026-10-28 |
| 工作区 | `/run/media/lingyue/Coding/Android/ROOT_MODEL/LayerBridge` |
| 关联决策 | [ADR-0001](../60-changes/adr/0001-lb-architecture.md) · [ADR-0002](../60-changes/adr/0002-lb-system-exposure.md) · [ADR-0003：LBPA 包格式](../60-changes/adr/0003-lbpa-format.md) · [ADR-0004：强制签名与恶意检测](../60-changes/adr/0004-signing-and-scan.md) · [ADR-0005：Termux 仓库签名](../60-changes/adr/0005-termux-repo-signature.md) |
| 关联规范 | [LAYERBRIDGEPACKONANDROID（LBPA）格式规范 v1](lbpa-format.md) |
| 状态 | 待确认（前缀、overlay 后端、模块安装方式） |

---

## 1. 定位

三部分：

- **App**：仓库、搜索、安装、卸载、升级、状态、日志。
- **root 模块 `lb`**：提供 `lb` 命令与开机钩子；Magisk / KernelSU / SukiSU / APatch。
- **CLI `lb`**：与 App 共用核心。

两种包格式：

- **`.deb`**：Termux 官方、自编译、社区包。
- **LBPA**：自有格式。

安装后原生执行：内核直接加载，`/system/bin/linker64` 动态链接；不用 chroot、proot、虚拟机、Termux 沙箱。

bionic 约束：Debian/Ubuntu 官方 deb 链接 glibc，ABI 不兼容。

图形运行时（X11/Wayland）不在范围。

---

## 2. 需求

| 编号 | 需求 | 落地方式 |
|---|---|---|
| R1 | 热安装 / 热卸载 | 只改 `/data/adb/lb` 与 overlay upper，不重启 |
| R2 | 模块安装一次，重启后生效 | 模块写入 `/data/adb/modules/lb`，重启时由管理器挂载 |
| R3 | 大部分包自动加入交互 shell 环境 | `env.sh` + 模块注入的 `mkshrc` |
| R4 | 钩子 / 依赖自动处理，无须重启 | 依赖拓扑安装；维护脚本立即执行；开机钩子模块重放 |
| R5 | 模块简称 `lb`，命令 `lb` | 模块 id `lb`；`/system/bin/lb` |
| R6 | `lb` 在任意终端可执行 | `/system/bin/lb` + `/data/adb/lb/bin/lb` 双入口 |
| R7 | 所有 root 应用能用已安装命令并获得正确环境 | `/system/bin` wrapper + overlay 热合并 |
| R8 | 支持 CLI 与 GUI 包；GUI 运行时不在 lb 内 | 两种格式统一安装；保留 `DISPLAY`/`XDG_RUNTIME_DIR` |
| R9 | 包不能是 root 管理器模块；只收通用格式或 lb 格式 | 接受 `.deb` 与 LBPA；拒绝 `module.prop`/`customize.sh`/zip |
| R10 | 安装、卸载不允许重启 | 包操作全程热路径；仅模块首次安装需一次重启 |
| R11 | 可选元模块 | overlay 后端支持自有挂载或第三方元模块 |
| R12 | 自有格式需版本化、可兼容演进 | LBPA v1：魔数 + manifest + payload，见格式规范 |
| R13 | 默认只允许授权签名包 | 根证书 → 二级证书 → 包签名链；默认 `require_signature=true` |
| R14 | 基础恶意检测，命中阻止并删除 | 静态规则扫描脚本/ELF；命中阻断规则删除缓存包 |
| R15 | 开发者模式可分别关闭签名/检测 | 两个独立开关，关闭后仍做结构校验与坏签名拒绝 |
| R16 | 允许 Termux 仓库签名作为授权来源 | 验证 `Release.gpg`/`InRelease` OpenPGP 签名 → `Packages` 哈希 → deb 哈希；默认信任 Termux 指纹 |

---

## 3. 术语

| 术语 | 含义 |
|---|---|
| 前缀（prefix） | Linux 用户态根目录，默认 `/data/adb/lb/usr` |
| 热路径 | 不重启、不修改 `/system` 静态内容的运行时操作 |
| 冷路径 | 需要重启或重挂载的操作（仅模块首次安装） |
| 模块 | 位于 `/data/adb/modules/lb` 的 Magisk 格式模块 |
| 元模块 | 第三方 overlay/meta 模块，负责把模块 `system/` 热合并到 `/system` |
| wrapper | `/system/bin` 下的转发脚本，设置环境后 exec 真实二进制 |
| 维护脚本 | deb/LBPA 自带的 `preinst/postinst/prerm/postrm` |
| 钩子 | 安装时/开机时执行的脚本，统一放在 `hooks/` |
| overlay upper | overlayfs 的可写层，热更新只改这里 |
| 前缀桥 | 可选的 `/data/data/com.termux/files/usr` 符号链接兼容层 |
| LBPA | LAYERBRIDGEPACKONANDROID，lb 自有包格式；`.lbpa`/`.lpa`/`.lb`/`.lbp` |

---

## 4. 总体架构

```
┌──────────────────────┐        ┌──────────────────────────────┐
│  App（Compose GUI）  │        │  CLI `lb`（app_process）      │
│  仓库/搜索/安装/日志 │        │  install/remove/list/env/... │
└──────────┬───────────┘        └──────────────┬───────────────┘
           │ 直接调用                          │ 复用同一份核心代码
           ▼                                   ▼
┌─────────────────────────────────────────────────────────────┐
│  核心（纯 Kotlin/JVM，可单测）                              │
│  仓库索引 · 依赖解析 · 版本比较 · ar/tar 解包 · 状态库      │
│  维护脚本 · 钩子 · 环境生成 · wrapper 生成 · su 执行封装    │
└──────────┬──────────────────────────────────┬───────────────┘
           │ su 脚本 / tar 流                  │ 写 upper / 模块目录
           ▼                                  ▼
┌──────────────────────────────┐   ┌───────────────────────────┐
│  /data/adb/lb              │   │  /system/bin（overlay）   │
│  usr/ bin/ env.sh            │   │  hello → wrapper          │
│  system/{bin,upper,work}     │   │  lb → 模块入口            │
│  db/ hooks/ cache/           │   └───────────────────────────┘
└──────────────▲───────────────┘
               │ 开机挂载 overlay / 环境注入 / 钩子重放
┌──────────────┴─────────────────────────────────────────────┐
│  模块 lb（/data/adb/modules/lb）                            │
│  system/bin/lb · system/etc/mkshrc · service.sh             │
│  post-fs-data.sh · module.prop                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.1 组件职责

| 组件 | 职责 | 不负责 |
|---|---|---|
| App | GUI、仓库配置、安装编排、模块安装引导、日志 | 常驻服务、开机钩子、图形桌面 |
| 核心 | 包管理全部纯逻辑与 su 操作 | UI、Android 权限申请 |
| 模块 | `lb` 入口、overlay 挂载、环境注入、开机钩子 | 包下载、依赖解析 |
| 元模块 | 可选：把模块 `system/` 热合并到 `/system` | 包管理逻辑 |

### 4.2 为什么前缀放在 `/data/adb/lb`

- `/data/adb` 的父目录是 `700 root:root`，普通应用**连遍历都不行**，`stat` 也会失败——
  安装的包、状态库、脚本对非 root 完全不可见。
- 代价：命令必须经 `su -c 'lb shell'` / `lb exec` 使用，普通 shell 不能直接敲；
  这与「默认隐藏」的决策一致（见 ADR 0007、0008）。
- 结论：**包文件与状态放 `/data/adb/lb`，模块静态文件放 `/data/adb/modules/lb`。**

### 4.3 为什么包不能是 root 管理器模块

- Magisk/KSU 模块的生效依赖开机挂载，与“安装不允许重启”直接冲突。
- 模块会修改 `/system`，难以热卸载，且与其他模块冲突。
- 因此：**lb 只接受通用 `.deb`（可带 lb 元数据），显式拒绝 `module.prop`、`customize.sh`、zip 模块包。**

---

## 5. 目录布局

### 5.1 设备侧

```
/data/adb/modules/lb/                 # 模块（管理器挂载）
├── module.prop
├── post-fs-data.sh                   # 建目录、挂 overlay、备份 mkshrc
├── service.sh                        # 开机执行 hooks/
├── system/bin/lb                     # 系统级入口（重启后可用）
├── system/etc/mkshrc                 # 注入 env.sh
└── system/bin/…                      # 元模块后端的 wrapper 同步目录

/data/adb/lb/                       # 运行时数据（root 写，shell 可读/执行）
├── usr/                              # 包前缀
│   ├── bin/ lib/ share/ etc/ var/
│   └── tmp/
├── bin/lb                            # 模块生效前的入口（同 system/bin/lb）
├── env.sh                            # 热环境（每次装/卸重写）
├── mkshrc.orig                       # 安装模块时备份的原 /system/etc/mkshrc
├── lb.apk                            # CLI 使用的 App 副本
├── system/
│   ├── bin/                          # wrapper 源文件（备份/生成）
│   ├── upper/                        # overlay 可写层（热暴露到 /system/bin）
│   └── work/                         # overlay workdir
├── db/
│   ├── installed.tsv
│   └── info/<package>.list
├── trust/
│   ├── root.json                     # 根证书（信任锚）
│   └── revocations.json(+.sig)       # 撤销列表
├── ca/                               # 可选：设备生成根/二级私钥（600）
├── logs/                             # 安装与安全日志
├── config.json                       # 开发者策略开关
├── hooks/
├── cache/
├── home/
└── tmp/
```

### 5.2 源码侧（规划）

```
app/src/main/java/com/lingyue/layerbridge/
├── core/          # Shell、Root、PathGuard、EnvWriter、WrapperWriter
├── repo/          # 仓库模型、索引拉取、Packages 解析
├── deb/           # ar、control、版本比较、依赖、解包、重定位
├── format/        # LBPA：魔数/manifest 解析、payload、打包（lb pack）
├── install/       # 安装/卸载/升级编排、overlay 同步
├── expose/        # wrapper 生成、overlay/元模块后端
├── cli/           # LbMain 命令行入口
└── ui/            # Compose 页面与 ViewModel

app/src/main/assets/module/            # 模块模板（打进 APK，供 App 安装）
app/src/test/java/...                  # 纯 JVM 单元/集成测试
scripts/build-module.sh                # 产出 build/lb-module.zip
md/00-design/lbpa-format.md            # LBPA 格式规范
md/                                    # 设计与变更记录
```

---

## 6. 热路径设计

### 6.1 安装（不重启）

1. 解析目标包与依赖，生成安装顺序（`Pre-Depends` → `Depends`，拓扑排序）。
2. 对每个包：下载 → 校验 SHA256 → 按魔数识别格式：
   - `!<arch>` → `.deb`：解析 ar、读 control；
   - `LBPA\r\n\x1a\n` → LBPA：读 manifest、校验 payload；
   - 其他 → 拒绝。
3. 校验格式：deb 必须含 `debian-binary` 与 control；LBPA 必须 `format=LAYERBRIDGEPACKONANDROID`、`formatVersion=1`、payload 哈希匹配；两者都 **拒绝** `module.prop`/`customize.sh`/zip。
4. **验证授权签名**（默认强制，两条路径）：
   - 路径 A（包签名）：LBPA footer 证书链 → 信任根；deb 同名 `.sig`；失败拒绝（退出码 7）；
   - 路径 B（仓库签名）：仓库在信任列表且 `Release.gpg`/`InRelease` 验证通过 → `Packages` 哈希 → deb 哈希；Termux 官方默认启用；
   - 两条都不满足 → 拒绝（退出码 6）；策略关闭时允许未签名，但“有签名却验证失败”仍拒绝。
5. **静态恶意检测**（默认开启）：扫描脚本、钩子、ELF 字符串；命中阻断规则 → 拒绝并删除缓存包。
6. 执行 `preinst`（如有），环境带入前缀变量。
7. 解压 payload（deb 的 `data.tar.{xz,gz}` 或 LBPA 的 payload）：逐条重写脚本 shebang，再以 tar 流写入 `su -c "tar -x -C $PREFIX"`；LBPA 同时读取 `control/` 脚本与钩子。
8. 执行 `postinst`（如有），失败只告警。
9. 更新状态库（版本、来源、文件清单、签名者、检测结果）。
10. 重写 `env.sh`。
11. 为包内每个可执行文件生成 wrapper：
    - 写入 `/data/adb/lb/system/bin/<cmd>`（备份）；
    - 写入 overlay upper `/data/adb/lb/system/upper/<cmd>`（系统可见）；
    - 元模块后端则写入 `/data/adb/modules/lb/system/bin/<cmd>`；
    - 与 `/system/bin` 同名命令默认跳过，需显式覆盖。
12. 执行该包注册的钩子（`hooks/`），依赖包的钩子已先执行。

### 6.2 卸载（不重启）

1. 读文件清单，逐项校验必须位于前缀内。
2. `prerm` → 删除文件与空目录 → `postrm`。
3. 删除对应 wrapper（upper/模块目录），命令立即从 `/system/bin` 消失。
4. 从状态库移除、删除对应钩子。
5. 重写 `env.sh`。

### 6.3 升级

- 视为“安装新版本 + 清理旧版本独有文件”。
- 维护脚本以 `upgrade <old-version>` 调用。
- 失败时保留旧版本状态，提示可回滚（重新安装旧版本）。

### 6.4 环境与命令生效边界

- **`/system/bin` 下的命令**：依靠启动时建立的 overlay（或元模块）热合并，所有进程（含 root 应用）按名查找即可见；wrapper 内部设置环境。
- **交互 shell**：`mkshrc` 自动 source `env.sh`，无需重启。
- **已开 shell**：`. /data/adb/lb/env.sh` 或 `lb shell`。
- **已运行进程的环境变量**：无法回溯修改；通过 wrapper/新进程获得环境是唯一可靠方式。

---

## 7. 模块 lb

### 7.1 module.prop

```properties
id=lb
name=LayerBridge
version=v0.1.0
versionCode=1
author=lingyue
description=Android 原生 Linux 包管理器：lb 命令、系统级暴露、环境注入、开机钩子
```

### 7.2 关键文件

| 文件 | 作用 |
|---|---|
| `post-fs-data.sh` | 创建 `/data/adb/lb` 子目录、备份 `mkshrc`、建立 overlay（自有后端） |
| `service.sh` | 以 root 依次执行 `hooks/*.sh`，并校验 overlay 挂载状态 |
| `system/bin/lb` | 启动 CLI：`app_process` 加载 `/data/adb/lb/lb.apk` |
| `system/etc/mkshrc` | 先 source 原始 mkshrc，再 source `env.sh` |

`system/bin/lb` 草案：

```sh
#!/system/bin/sh
APK=/data/adb/lb/lb.apk
[ -f "$APK" ] || { echo "lb: $APK missing" >&2; exit 1; }
exec app_process -Djava.class.path="$APK" /system/bin \
    com.lingyue.layerbridge.cli.LbMain "$@"
```

### 7.3 安装方式

- **App 自动安装（默认）**：通过 su 把 `assets/module` 写入 `/data/adb/modules/lb`，并把自身 APK 复制为 `/data/adb/lb/lb.apk`；提示“重启后 lb 生效”。这是唯一需要重启的步骤。
- **zip 安装（备用）**：`scripts/build-module.sh` 产出 `lb-module.zip`，可在管理器里手动安装。
- App 同时写 `/data/adb/lb/bin/lb`，重启前也可显式调用。

### 7.4 卸载

- 管理器卸载模块 → 开机不再注入/挂载；`/data/adb/lb` 保留（内含已装包）。
- App 提供“清空前缀”危险操作，需二次确认。

---

## 8. 系统级暴露与全局环境

这是 R7 的核心：**让所有 root 应用、任意终端都能按命令名调用已安装包，并拿到正确环境。**

### 8.1 wrapper 生成

包内每个可执行文件生成一个 wrapper，内容：

```sh
#!/system/bin/sh
LB_PREFIX=/data/adb/lb/usr
export LB_PREFIX PREFIX="$LB_PREFIX"
export PATH="$LB_PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$LB_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export HOME="${HOME:-/data/adb/lb/home}"
export TMPDIR="$LB_PREFIX/tmp"
# 保留调用方的图形环境，不覆盖
: "${DISPLAY:=}"; : "${XDG_RUNTIME_DIR:=}"; : "${WAYLAND_DISPLAY:=}"
exec "$LB_PREFIX/bin/<cmd>" "$@"
```

- 使用 `/system/bin/sh`，不依赖前缀里的 shell。
- 只设置 lb 相关变量，`DISPLAY`/`XDG_RUNTIME_DIR`/`WAYLAND_DISPLAY` 保持调用方原值（图形工具由使用者自行解决显示）。
- 同名系统命令默认不生成 wrapper；显式 `lb expose <cmd> --force` 才覆盖。

### 8.2 暴露后端

| 后端 | 机制 | 热生效 | 覆盖范围 | 备注 |
|---|---|---|---|---|
| **overlay（可选，ADR 0007；默认关闭）** | 模块 `post-fs-data.sh` 在 zygote 前把 `/system/bin` 叠加为 overlay，`upper=/data/adb/lb/system/upper` | 是（upper 写入即时可见） | 所有进程（含 root 应用） | 需要内核 overlayfs；失败自动降级 |
| **元模块** | 第三方元模块把 `/data/adb/modules/lb/system` 热合并 | 是 | 所有进程 | 与已有 overlay 方案冲突时二选一 |
| **mkshrc（降级）** | 仅交互 shell 注入 `env.sh` | 是 | 交互 shell | 无系统级命令暴露 |

overlay 挂载草案（`post-fs-data.sh`）：

```sh
U=/data/adb/lb/system/upper
W=/data/adb/lb/system/work
mkdir -p "$U" "$W"
mount -t overlay overlay \
  -o "lowerdir=/system/bin,upperdir=$U,workdir=$W" \
  /system/bin || log -t lb "overlay mount failed; fallback to mkshrc"
```

- 必须在 zygote 启动前完成，已有进程/应用命名空间才能看到合并结果。
- 之后热装/热卸只改 upper，不需要新增 mount。
- overlay 失败时记录日志，系统级命令不可用，但 `lb shell`、`lb run`、新开交互 shell 仍可用。

### 8.3 “所有 root 应用获得环境”的准确含义

- root 应用执行 `su -c "命令"` 时，命令经 `/system/bin` 解析到 wrapper，wrapper 注入 `PATH/LD_LIBRARY_PATH/PREFIX` 后 exec 真实二进制。
- 子进程继承该环境；因此 `bash`、`python` 等启动后，其子命令也能工作。
- **不能**修改已运行进程的环境变量；这是内核/进程模型限制。lb 用“命令级 wrapper”等价满足“所有 root 应用可用”。
- 若 ROM 不允许 overlay 且无元模块，则退化为交互 shell 环境，文档与 UI 明确提示。

### 8.4 mkshrc 注入

模块的 `system/etc/mkshrc`：

```sh
[ -f /data/adb/lb/mkshrc.orig ] && . /data/adb/lb/mkshrc.orig
[ -f /data/adb/lb/env.sh ] && . /data/adb/lb/env.sh
```

安装模块时把当前 `/system/etc/mkshrc` 备份到 `/data/adb/lb/mkshrc.orig`，避免丢失系统默认行为。

### 8.5 `lb shell` / `lb run`

- `lb shell`：启动一个已注入环境的 shell（优先 `$PREFIX/bin/bash`，否则 `/system/bin/sh`）。
- `lb run <cmd...>`：带环境执行单条命令，适合脚本与非交互场景。

---

## 9. 包管理核心

### 9.1 包格式

lb 接受两种格式，解析后归一到同一个 `PackageMeta` 与安装管线：

**A. 通用格式 `.deb`**（ar + control.tar + data.tar），Termux/自编译/社区包通用。
可选 lb 元数据（放在 `control.tar` 内，不改变 deb 兼容性）：

- `lb-hooks/`：开机钩子脚本；
- `lb-env`：追加环境变量；
- `lb-expose`：需要强制暴露的命令列表。

**B. 自有格式 LAYERBRIDGEPACKONANDROID（LBPA）**

- 后缀：`.lbpa`、`.lpa`、`.lb`、`.lbp`，字节完全相同；解析以魔数 `LBPA\r\n\x1a\n` 为准。
- 结构：8 字节魔数 + header + UTF-8 JSON manifest + 压缩 tar payload（+ 可选签名 footer）；payload 内 `control/`（脚本/钩子）与 `files/`（文件树）。
- 版本化：`formatVersion`、`minLbVersion`；未知可选字段忽略，未知必选特性拒绝。
- 完整规范见 [LBPA 格式规范 v1](lbpa-format.md)。

**C. 拒绝的格式**：`.zip`、含 `module.prop`/`customize.sh` 的 root 管理器模块、无 control 的 deb、魔数与 manifest 不符的伪 LBPA。

**D. 互转**：`lb pack --from-deb foo.deb -o foo.lbpa` 无损转换；两种格式可在同一仓库共存。

### 9.2 仓库

| 类型 | 说明 |
|---|---|
| Termux 官方 | `https://packages.termux.dev/apt/termux-main`，`dists/stable/main/binary-aarch64/Packages`（`Packages.gz` 可用）；`Release.gpg`/`InRelease` 由 Termux OpenPGP 密钥签名（指纹 `CC72CF8BA7DBFA0182877D045A897D96E57CF20C`） |
| 自定义 HTTP | base URL + 可选显式索引地址，支持 apt 布局或“根目录 `Packages` + deb/lbpa”布局；仓库签名需显式授权 |
| 本地目录 | 目录中放 `Packages` 与 deb/lbpa，供自编译包使用；默认按未签名仓库处理 |
| 单包 | SAF 选 `.deb` / `.lbpa` / `.lpa` / `.lb` / `.lbp` 直接安装 |

### 9.3 索引解析

- 控制文件格式（RFC822 段落），字段：`Package`、`Version`、`Architecture`、`Depends`、`Pre-Depends`、`Provides`、`Conflicts`、`Breaks`、`Replaces`、`Filename`、`Size`、`SHA256`、`Installed-Size`、`Description`、`Format`。
- `Format`（可选）：`deb` 或 `lbpa`；缺省按 `Filename` 后缀与内容魔数探测。
- **仓库签名**：对信任仓库先取 `Release` + `Release.gpg`，用固定指纹验证；再从已签名 `Release` 的 `SHA256` 段校验 `Packages` 哈希；最后用 `Packages` 里的 `SHA256` 校验 deb。
- `InRelease`（cleartext）可作为等价来源；v1 优先用分离签名，便于字节级校验。
- 按魔数识别压缩：gzip `1F 8B`、xz `FD 37 7A 58 5A 00`、否则明文。
- 仅接受 `aarch64` 或 `all`。

### 9.4 依赖与版本

- 版本比较严格按 dpkg 算法：`epoch:upstream-revision`，非数字段按字符序，`~` 排在空串之前。
- 依赖表达式：`a (>= 1.0) | b`，逗号分组。
- 安装集合 = 目标包 + `Pre-Depends` + `Depends`；`Provides` 满足虚拟依赖；`Conflicts`/`Breaks` 阻止安装；`Replaces` 允许覆盖文件。
- 排序：拓扑排序，保证被依赖者先装；检测环并报错。

### 9.5 包结构

**deb**

```
ar 归档
├── debian-binary
├── control.tar.{gz,xz}   # control、preinst、postinst、prerm、postrm、lb-*
└── data.tar.{gz,xz}      # 文件
```

**LBPA**

```
[8B magic "LBPA\r\n\x1a\n"]
[u16 format=1][u16 flags=0][u32 manifestLen]
[manifest JSON UTF-8]
[payload: 压缩 tar]
  ├── control/…           # 脚本、钩子（不安装进前缀）
  └── files/…             # 安装进前缀的文件树
[可选 footer: u32 sigLen + signature JSON]   # 授权签名，见 ADR-0004
```

### 9.6 安装流水线（详细）

1. 依赖解析，得到有序包列表。
2. 对每包：下载到 `cache/`，校验 SHA256 与大小。
3. 按魔数识别格式：
   - deb：解析 control，读取维护脚本与 lb 元数据；
   - LBPA：校验 manifest（format/formatVersion/minLbVersion）、payload SHA256，读取 scripts/hooks/env/expose；
   - 两者都做格式校验（非模块包）。
4. `preinst`（经 `su`，前缀环境）。
5. 解压 payload：
   - 遍历 tar 条目（deb 的 `data.tar` 或 LBPA 的 `files/` + `control/`）；
   - 普通文件若首行是 `#!` 且包含 Termux 前缀，替换为目标前缀；
   - 重打包为 tar 流，写入 `su -c "tar -x -C $PREFIX"` 的 stdin；
   - 保留权限、符号链接、硬链接；uid/gid 归 0；清除 setuid/setgid/sticky。
6. `postinst`。
7. 写状态库与文件清单（路径必须相对前缀、无 `..`；记录格式 `deb`/`lbpa`）。
8. 重写 `env.sh`（含 LBPA 的 `env` 段）。
9. 生成 wrapper 并同步到暴露后端（见第 8 节；LBPA 的 `expose` 优先）。
10. 执行钩子（维护脚本之后、暴露之后；LBPA 的 `hooks.install`）。

失败处理：

- `preinst` 失败 → 中止该包，不写状态。
- 解包失败 → 标记失败，提示重装，不做半成品登记。
- `postinst`/钩子失败 → 包仍登记，日志标红，提供“重跑脚本”入口。

### 9.7 状态库

`db/installed.tsv`（Tab 分隔，无表头）：

```
package  version  architecture  format  repo  installedAt  signer  scan  scriptsStatus
```

- `signer`：包签名证书 `subject`/`keyId`，或 `repo:<id>`（仓库签名路径）；未签名留空。
- `scan`：`pass`/`warn`/`blocked`/`skipped`。

`db/info/<package>.list`：每行一个相对路径。

所有路径在删除前校验：非空、非绝对、不含 `..`、拼接后仍在前缀内。

### 9.8 文件冲突

- 同一路径被两个包提供 → 默认拒绝安装并列出冲突；`Replaces` 或用户确认后覆盖。
- 系统自带命令（`sh`、`ls` 等）不进入 wrapper 列表，避免覆盖 Android 关键命令。

### 9.9 图形化包

- 安装方式与 CLI 包完全一致；依赖（X11/Wayland 库、dbus 等）照常解析。
- lb 不启动/管理显示服务；wrapper 保留调用方的 `DISPLAY`、`XDG_RUNTIME_DIR`、`WAYLAND_DISPLAY`。
- `.desktop` 等桌面文件落到前缀 `share/`，如何集成由使用者决定。

---

## 10. Termux 兼容

| 问题 | 处理 |
|---|---|
| shebang 写死 Termux 前缀 | 安装时改写为 `/data/adb/lb/usr` |
| 动态库 rpath 指向 Termux lib | wrapper/环境注入 `LD_LIBRARY_PATH=$PREFIX/lib`（`DT_RUNPATH` 下优先生效） |
| 脚本依赖 `$PREFIX` | `env.sh`/wrapper 设置 `PREFIX` |
| Python 编译期前缀 | 运行 `python` 时设 `PYTHONHOME=$PREFIX`（包装/环境处理） |
| Perl/其他编译期绝对路径 | 无法保证，安装时告警；可开启前缀桥 |
| `termux-exec` | 不加载，避免宿主冲突 |
| 架构不符 | 仅安装 `aarch64`/`all` |

兼容等级在包详情中显示：`原生`（自编译目标前缀）、`Termux 兼容`（重写后可用）、`实验`（有硬编码路径风险）。

### 10.1 前缀桥（可选，实验）

Termux 包把 `/data/data/com.termux/files/usr` 写死在 shebang、rpath、脚本里。默认策略是重写 shebang + wrapper 环境；对仍有绝对路径依赖的包，可开启：

```sh
ln -s /data/adb/lb/usr /data/data/com.termux/files/usr
```

- 仅当 Termux 未安装时允许；检测到 Termux 则禁用并提示。
- 影响全局路径，默认关闭。

---

## 11. 钩子

### 11.1 维护脚本

- `preinst` / `postinst` / `prerm` / `postrm` 在安装/卸载过程中立即执行，使用前缀环境。
- 解释器：优先 `$PREFIX/bin/bash`，否则 `/system/bin/sh`。
- 参数按 dpkg 惯例近似：`postinst configure <old-version>`。

### 11.2 注册钩子

- 路径：`/data/adb/lb/hooks/<package>-<name>.sh`。
- 来源：deb 内 `lb-hooks/` 或用户 `lb hook add`。
- 安装后立即执行一次；模块 `service.sh` 开机按文件名顺序重放。
- 钩子失败只记录，不阻塞开机。

### 11.3 “钩子依赖”

- 依赖包先安装，其钩子先执行；同一包内 `preinst` → 文件落地 → `postinst` → wrapper → 钩子。
- 升级/卸载时，依赖方的钩子不受影响；被依赖包卸载前检查反向依赖并警告。
- 整个流程不需要重启。

---

## 12. overlay 与元模块

### 12.1 自有 overlay（可选，ADR 0007 起默认关闭）

- 模块 `post-fs-data.sh` 挂载 overlay 到 `/system/bin`。
- 热安装写 upper，命令即时可见；卸载删 upper 文件，即时消失。
- 无需元模块；依赖内核 overlayfs 与 SELinux 策略。

### 12.2 元模块后端（可选）

- 适用于 ROM 自带 overlay 或用户已装 MetaModule 的情况。
- lb 只把 wrapper 写进 `/data/adb/modules/lb/system/bin/`，由元模块热合并。
- App 检测到元模块时自动切换；与自有 overlay 二选一，避免叠加冲突。

### 12.3 冲突与回退

- overlay 挂载失败：记录日志，回退 mkshrc；系统级命令不可用，`lb shell`/`lb run` 可用。
- 与系统命令同名：默认跳过，需 `--force`。
- 与已有模块冲突：启动时检查 `/system/bin` 是否已是 overlay，优先让位给元模块。

---

## 13. CLI `lb`

### 13.1 启动方式

模块 `/system/bin/lb` → `app_process` 加载 `/data/adb/lb/lb.apk` → `com.lingyue.layerbridge.cli.LbMain`。以 shell 身份运行；需要 root 的步骤内部调用 `su`。

### 13.2 命令

| 命令 | 说明 |
|---|---|
| `lb search <关键词>` | 搜索所有启用仓库 |
| `lb list [installed\|available\|upgradable]` | 列表 |
| `lb info <包>` | 详情、依赖、脚本、来源 |
| `lb install <包...>` | 解析依赖并热安装 |
| `lb install ./x.deb` | 安装本地 deb |
| `lb install ./x.lbpa` | 安装本地 LBPA（`.lpa`/`.lb`/`.lbp` 同） |
| `lb pack <目录> -o x.lbpa` | 从目录生成 LBPA |
| `lb pack --from-deb x.deb -o x.lbpa` | deb → LBPA 无损转换 |
| `lb inspect <包>` | 查看格式、manifest、payload 哈希、脚本、钩子 |
| `lb ca init/issue/revoke` | 根证书初始化、签发二级证书、撤销 |
| `lb sign <包> --key ... --cert ...` | 签 LBPA 或生成 deb 的 `.sig` |
| `lb verify <包> [--sig ...]` | 验证证书链与签名 |
| `lb trust import/list` | 信任根管理 |
| `lb trust repo add/list/remove` | 仓库签名信任（Termux/自定义仓库） |
| `lb config get/set` | 开发者开关（签名/检测） |
| `lb scan <包>` | 单独运行恶意检测 |
| `lb remove <包...>` | 卸载（检查反向依赖） |
| `lb upgrade [包...]` | 升级 |
| `lb repo list/add/remove` | 仓库管理 |
| `lb env` | 打印 `env.sh` |
| `lb shell` | 进入带环境的 shell |
| `lb run <cmd...>` | 带环境执行 |
| `lb expose <cmd> [--force]` | 手动生成/覆盖系统 wrapper |
| `lb hook list/run` | 钩子管理 |
| `lb module status/install` | 模块状态与安装 |
| `lb doctor` | 自检：root、overlay、前缀、环境 |

### 13.3 输出与退出码

- 人类可读输出到 stdout，日志/进度到 stderr。
- `0` 成功；`1` 参数/网络错误；`2` 依赖或冲突；`3` root 不可用；`4` 安装脚本失败；`5` 格式/模块包被拒绝；`6` 未授权签名；`7` 签名/证书校验失败；`8` 恶意检测拦截。

---

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

- 首次启动引导：生成根证书（`lb ca init`）或导入已有根证书；未配置信任根时安装全部拒绝并给出入口。
- 信任页：根证书指纹、已签发二级证书列表、撤销列表、导入/替换根证书。
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
- 未授权仓库：默认按未签名处理；需 `lb trust repo add` 显式授权（含指纹核对）后才能走路径 B。
- 两条路径关系：包签名有效 → 直接接受；包未签名但仓库已验证 → 接受；两者都无 → 拒绝。

**与之前版本的差异**：Termux 官方 deb 现在默认可以安装（通过仓库签名），不再需要重签或开发者模式；其他未签名仓库仍需逐包签名或显式授权仓库。

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
- **Termux 官方仓库通过仓库签名默认允许**；其他未签名仓库需逐包签名或显式授权仓库（`lb trust repo add`）。
- 仓库签名只保护元数据与哈希链，不证明单个包构建者身份；需要更强保证时使用包签名。
- 首次使用必须先建立信任根（`lb ca init` 或 `lb trust import`），否则无法安装任何包。
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
# LBPA 解析/打包、证书链/签名、仓库 OpenPGP 验证、恶意规则、开发者策略）

./gradlew --no-daemon :app:lintDebug
# 期望：0 Error

bash scripts/build-module.sh
unzip -l build/lb-module.zip
# 期望包含 module.prop、post-fs-data.sh、service.sh、system/bin/lb、system/etc/mkshrc
```

### 18.2 真机清单（待执行，当前标“未验证”）

1. 安装 App，授予 root（KSU/SukiSU 管理器内授权）。
2. App 安装模块 → 重启 → `which lb`、`lb doctor` 正常。
3. 新开 `adb shell`，`echo $PREFIX`、`echo $PATH` 含前缀。
4. `lb ca init` 或导入根证书 → `lb trust list` 显示指纹；`lb trust repo list` 显示 Termux 指纹。
5. `lb install hello`（Termux 官方仓库）→ 通过仓库签名验证，安装成功；`hello`、`su -c hello` 均可用。
6. 篡改本地 `Packages` 或某个 deb 一个字节 → 仓库签名/哈希校验拒绝。
7. 直接装未签名本地 `.deb` → 拒绝（退出码 6），缓存被删除。
8. 用二级证书签名 LBPA → `lb install hello.lbpa` 成功。
9. 篡改 LBPA payload 一个字节 → 拒绝（签名/哈希），缓存删除。
10. 构造含 `rm -rf /system` 的钩子包 → 被阻断，缓存删除，日志可查。
11. 开发者模式：关闭“强制签名”→ 未签名可装；关闭“自动检测”→ 恶意包不阻断但结构校验仍生效；重新开启后恢复。
12. `lb remove hello` → 命令立即消失（`/system/bin/hello` 不存在），无需重启。
13. 尝试安装 Magisk 模块 zip → 拒绝，退出码 5。
14. `lb pack --from-deb hello.deb -o hello.lbpa` → `lb inspect` 正常；改名为 `.lb`/`.lpa` 仍可安装。
15. 重启后确认 overlay/命令/钩子持久化。

---

## 19. 风险与回退

| 风险 | 影响 | 回退 |
|---|---|---|
| overlayfs 不可用/挂载失败 | 系统级命令不可见 | 元模块后端；或 mkshrc + `lb shell` |
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
