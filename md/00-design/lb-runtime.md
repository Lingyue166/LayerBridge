# LayerBridge 运行时设计

| 项目 | 内容 |
|---|---|
| 状态 | 现行 |
| 更新日期 | 2026-10-29 |
| 所属 | [架构设计总览](lb-architecture.md) |
| 范围 | §7 模块 lb · §8 系统级暴露 · §9 包管理核心 · §10 Termux 兼容 · §11 钩子 · §12 overlay · §13 CLI |

> 上一篇：[架构设计总览](lb-architecture.md) · 下一篇：[App、安全与验收](lb-app-security.md)

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

R7 要求所有 root 应用、任意终端都能按命令名调用已安装包，并拿到正确环境。

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
- 已运行进程的环境变量无法修改，受内核与进程模型限制。lb 用“命令级 wrapper”等价满足“所有 root 应用可用”。
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

**D. 互转**：`lb pack --from-deb foo.deb -o foo.lbpa` 无损转换；两种格式可在同一仓库共存。（工具未实现）

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

模块 `/system/bin/lb` → `app_process` 加载 `/data/adb/lb/lb.apk` → `com.lingyue.layerbridge.cli.LbMain`。以调用者身份运行；写操作要求 uid=0，子进程经 `su` 执行。

### 13.2 命令

已实现：

| 命令 | 说明 |
|---|---|
| `lb install [--allow-unsigned] [--skip-scan] <文件>` | 安装本地 deb / LBPA |
| `lb upgrade [选项] <文件>` | 升降级（要求已安装） |
| `lb remove [--force] <包>` / `lb purge <包>` | 卸载；默认保留数据 |
| `lb list` | 已安装列表 |
| `lb info <包>` | 版本、来源、签名者、关系 |
| `lb doctor` | root、前缀权限、入口、trust、root 软链 |
| `lb env` | 打印 `env.sh` |
| `lb shell` | 进入带环境的 shell |
| `lb exec <命令...>` | 带环境执行一条命令 |
| `lb cli` | 交互模式 |
| `lb expose-root [--remove]` | root shell 软链开关（默认开） |
| `lb version` | 版本 |

未实现：

| 命令 | 说明 |
|---|---|
| `lb search` / `lb repo` | 仓库搜索与管理（目前只在 App） |
| `lb pack` / `lb inspect` | LBPA 打包与检查 |
| `lb ca` / `lb sign` / `lb verify` | 证书与签名（PC 端脚本 `scripts/lb-sign.py` 可用） |
| `lb trust` | 信任根、仓库信任管理（与 `trust/root.json` 一致） |
| `lb config` | 开发者开关（目前由 App 与安装参数控制） |
| `lb scan` | 单独运行静态检测 |
| `lb hook` / `lb module` | 钩子与模块状态 |
| `lb update` | 占位，未实现 |

### 13.3 输出与退出码

当前实现：

- 输出到 stdout，日志逐行打印。
- `0` 成功；`1` 业务失败（未安装、签名、依赖、脚本）；`2` 用法错误。

其余退出码（3–8）属于早期规划，未实现。

---

