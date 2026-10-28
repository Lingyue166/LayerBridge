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

- `/data/adb` 的父目录是 `700 root:root`，普通应用无法遍历，`stat` 也会失败；
  安装的包、状态库、脚本对非 root 完全不可见。
- 代价是命令必须经 `su -c 'lb shell'` / `lb exec` 使用，普通 shell 不能直接敲；
  这与「默认隐藏」的决策一致（见 ADR 0007、0008）。
- 因此包文件与状态放 `/data/adb/lb`，模块静态文件放 `/data/adb/modules/lb`。

### 4.3 为什么包不能是 root 管理器模块

- Magisk/KSU 模块的生效依赖开机挂载，与“安装不允许重启”直接冲突。
- 模块会修改 `/system`，难以热卸载，且与其他模块冲突。
- 因此 lb 只接受通用 `.deb`（可带 lb 元数据），显式拒绝 `module.prop`、`customize.sh`、zip 模块包。

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



## 分册

| 分册 | 范围 |
|---|---|
| [运行时设计](lb-runtime.md) | §7 模块、§8 暴露、§9 包管理、§10 Termux、§11 钩子、§12 overlay、§13 CLI |
| [App、安全与验收](lb-app-security.md) | §14–§20 与附录 |
