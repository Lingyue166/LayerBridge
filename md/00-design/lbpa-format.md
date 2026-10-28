# LAYERBRIDGEPACKONANDROID（LBPA）格式规范 v1

| 项目 | 内容 |
|---|---|
| 格式全名 | LAYERBRIDGEPACKONANDROID |
| 简称 / 魔数 | LBPA |
| 主后缀 | `.lbpa` |
| 兼容后缀 | `.lpa`、`.lb`、`.lbp`（字节完全相同，解析以魔数为准） |
| 规范版本 | v1（`formatVersion = 1`） |
| 状态 | 草案（随实现冻结） |
| 关联设计 | [lb 架构设计](lb-architecture.md) |
| 关联决策 | [ADR-0003](../60-changes/adr/0003-lbpa-format.md) · [ADR-0004：强制签名与恶意检测](../60-changes/adr/0004-signing-and-scan.md) · [ADR-0005：Termux 仓库签名](../60-changes/adr/0005-termux-repo-signature.md) |

> 本文中的“必须/禁止”为规范约束；“可选”字段读取方必须在缺失时回退到默认行为。

---

## 1. 目标

- 只有 lb 解析和安装这个格式。
- manifest 带版本号：未知可选字段忽略，未知必选特性报错；可与 deb 互转。
- manifest 在文件头，payload 流式解压。
- payload 的 SHA-256 必填，证书链签名默认强制校验。
- 静态检测命中时阻断安装，并删除缓存包。

## 2. 后缀与识别

| 后缀 | 说明 |
|---|---|
| `.lbpa` | 推荐后缀 |
| `.lpa` | 别名，内容相同 |
| `.lb` | 简称别名，内容相同 |
| `.lbp` | 兼容别名，内容相同 |

- 解析不依赖后缀：读取前 8 字节魔数，匹配即视为 LBPA。
- 后缀只用于 UI 过滤、文件关联与仓库索引提示。
- 其他格式即使后缀改成 `.lbpa`，也必须因魔数不符而拒绝。

## 3. 文件布局

```
偏移              长度        内容
0                 8           Magic: 4C 42 50 41 0D 0A 1A 0A  ("LBPA\r\n\x1a\n")
8                 2           format major (u16 BE) = 1
10                2           flags (u16 BE)，bit0=已签名，其余必须为 0
12                4           manifestLength (u32 BE)，1 .. 1,048,576
16                N           manifest（UTF-8 JSON，无 BOM，无结尾 NUL）
16+N              P           payload（压缩 tar 流），P = manifest.payload.size
16+N+P            4           signatureLength (u32 BE)，仅 flags.bit0=1 时存在
16+N+P+4          S           signature（UTF-8 JSON），S = signatureLength
```

- `flags` 的未知位必须拒绝；v1 允许 `0x0000`（未签名）与 `0x0001`（已签名）。
- `manifestLength` 超限、为 0、或超过文件剩余长度 → 拒绝。
- payload 起点固定为 `16 + manifestLength`，长度由 manifest 声明。
- **已签名**：payload 结束后必须是 4 字节签名长度 + 签名 JSON；签名长度 1 .. 65,536，且文件必须恰好结束。
- **未签名**：payload 结束后不得有任何多余字节，否则视为格式错误。
- payload 的压缩方式、长度、哈希均在 manifest 中声明。

## 4. Manifest

### 4.1 顶层字段

| 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `format` | string | 是 | 必须等于 `LAYERBRIDGEPACKONANDROID` |
| `formatVersion` | int | 是 | v1 必须为 `1` |
| `minLbVersion` | string | 否 | 语义化版本；低版本 lb 必须拒绝安装 |
| `createdBy` | string | 否 | 打包工具及版本，如 `lb-pack/0.1.0` |
| `createdAt` | string | 否 | ISO-8601 UTC |
| `package` | object | 是 | 见 4.2 |
| `relations` | object | 否 | 见 4.3 |
| `payload` | object | 是 | 见 4.4 |
| `scripts` | object | 否 | 维护脚本路径，见 4.5 |
| `hooks` | object | 否 | 开机/事件钩子，见 4.6 |
| `upgrade` | object | 否 | 升级策略，见 4.9 |
| `env` | object | 否 | 安装后追加的环境变量 |
| `expose` | array | 否 | 需要暴露到 `/system/bin` 的命令路径 |
| `data` | array | 否 | 用户数据/配置路径，卸载默认保留，见 4.10 |
| `gui` | bool | 否 | 图形工具提示，仅用于 UI |
| `installedSize` | int | 否 | 安装后大小（KiB） |
| `description` | string | 否 | 简介 |
| `homepage` | string | 否 | 主页 |
| `maintainer` | string | 否 | 维护者 |
| `license` | string | 否 | 许可证 |

签名不放在 manifest 内，而是位于文件尾部的独立 signature 段（见第 9 节），避免 JSON 规范化问题。

未知顶层字段：**读取方必须忽略**，不得报错（向前兼容）。

### 4.2 `package`

| 字段 | 类型 | 必须 | 约束 |
|---|---|---|---|
| `name` | string | 是 | `^[a-z0-9][a-z0-9+.-]{0,127}$` |
| `version` | string | 是 | dpkg 版本语法，非空、无空白 |
| `architecture` | string | 是 | `aarch64` 或 `all` |
| `description` | string | 否 | 纯文本 |

### 4.3 `relations`

每个字段都是字符串数组，语法与 deb 一致（`pkg (>= 1.0)`、`a | b`）：

```json
{
  "depends": ["libc++"],
  "preDepends": [],
  "provides": ["hello-virtual"],
  "conflicts": [],
  "breaks": [],
  "replaces": [],
  "recommends": [],
  "suggests": []
}
```

- `depends` / `preDepends` / `provides` / `conflicts` / `breaks` / `replaces` 参与解析。
- `recommends` / `suggests` 仅展示，默认不安装。
- 未知字段忽略。

### 4.4 `payload`

| 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `compression` | string | 是 | v1 支持 `xz`、`gzip` |
| `size` | int | 是 | 压缩后 payload 字节数；必须与 payload 区域实际长度一致（不含签名 footer） |
| `sha256` | string | 是 | 压缩后 payload 的 SHA-256（小写 hex） |
| `uncompressedSize` | int | 否 | 解压后 tar 字节数，用于空间检查 |
| `sha256Uncompressed` | string | 否 | 解压后 tar 的 SHA-256 |

- 压缩方式未知 → 拒绝，并提示升级 lb。
- 哈希/长度不匹配 → 拒绝，不写入前缀。

### 4.5 `scripts`

路径必须指向 payload 中 `control/` 下的普通文件：

```json
{
  "preinst": "control/preinst.sh",
  "postinst": "control/postinst.sh",
  "prerm": "control/prerm.sh",
  "postrm": "control/postrm.sh"
}
```

- 缺失表示没有对应脚本。
- 脚本以 `$PREFIX/bin/bash`（存在时）或 `/system/bin/sh` 执行。
- 参数按 dpkg 惯例近似：`postinst configure <old-version>`。

### 4.6 `hooks`

```json
{
  "boot": ["control/hooks/10-service.sh"],
  "install": ["control/hooks/20-setup.sh"],
  "remove": ["control/hooks/30-cleanup.sh"]
}
```

- `install` 在文件落地后立即执行；`remove` 在卸载时执行；`boot` 由模块 `service.sh` 开机重放。
- 全部路径必须在 payload `control/` 下。
- 失败只记录，不阻塞其他包/开机。

### 4.7 `env`

```json
{ "PYTHONHOME": "/data/adb/lb/usr" }
```

- 键：`^[A-Za-z_][A-Za-z0-9_]*$`，值不含 NUL/换行。
- 禁止覆盖 `PATH`、`LD_LIBRARY_PATH`、`PREFIX`（由 lb 统一生成）；出现则拒绝。
- 写入 `env.sh` 的包专属段，卸载时移除。

### 4.8 `expose`

```json
["bin/hello", "bin/my-gui-tool"]
```

- 相对前缀的路径；必须是 payload 中存在的普通可执行文件。
- 缺省时：`files/bin/*` 与 `files/sbin/*` 中带执行位的文件自动暴露。
- 与 `/system/bin` 同名命令默认跳过；需用户显式 `--force`。

### 4.9 `upgrade`

LBPA 允许任意版本之间升级（含降级、同版本重装），不要求版本相邻，也不依赖中间版本。

```json
{
  "keep": ["etc/hello.conf"],
  "remove": ["share/hello/obsolete.txt"],
  "scripts": {
    "pre": "control/upgrade-pre.sh",
    "post": "control/upgrade-post.sh"
  }
}
```

| 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `keep` | array | 否 | 升级前备份、解包后恢复的路径；旧内容优先，防止新版覆盖配置（自动并入 `data`） |
| `remove` | array | 否 | 升级时额外删除的旧路径 |
| `scripts.pre` | string | 否 | 解包前执行，参数 `<old-version>`；缺失时回退 `scripts.preinst` |
| `scripts.post` | string | 否 | 解包后执行，参数 `<old-version>`；缺失时回退 `scripts.postinst` |

- 路径相对前缀、不得含 `..`；脚本路径必须在 `control/` 下。
- 未声明 `keep` 时，旧包中已不存在于新包的文件按文件清单自动清理。
- 旧包的 `prerm` 在新包脚本落盘之前以 `prerm upgrade <new-version>` 执行，失败则中止升级。

### 4.10 `data`

```json
["etc/hello.conf", "var/lib/hello"]
```

- 包声明的用户数据/配置，路径相对前缀、不得含 `..`。
- **卸载默认保留**：`lb remove <pkg>` 不删这些文件；`lb remove --force`（或 `lb purge`）才删除。
- 升级时自动并入 `upgrade.keep`，即新版 payload 不会覆盖它们。
- 未声明时：deb 用控制文件的 `Conffiles`，LBPA 不保留任何文件。

## 分册

| 分册 | 范围 |
|---|---|
| [载荷、安装与签名](lbpa-payload-signing.md) | §5–§9 |
| [安全约束与工具链](lbpa-tools.md) | §10–§13 |
