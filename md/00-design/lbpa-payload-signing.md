# LBPA 载荷、安装与签名

| 项目 | 内容 |
|---|---|
| 状态 | 草案 |
| 更新日期 | 2026-10-29 |
| 所属 | [LBPA 格式规范](lbpa-format.md) |
| 范围 | §5 Payload · §6 安装语义 · §7 兼容规则 · §8 与 deb 互操作 · §9 签名与完整性 |

> 上篇：[LBPA 容器与 manifest](lbpa-format.md) · 下篇：[安全约束与工具链](lbpa-tools.md)

## 5. Payload

### 5.1 布局

payload 是压缩后的 POSIX tar（pax 推荐），顶层只允许两个目录：

```
control/…     # 脚本与钩子，不安装进前缀
files/…       # 安装进前缀的文件树
```

- `files/bin/hello` → `$PREFIX/bin/hello`。
- `control/` 内容按需读取，不落到前缀。
- v1 未知顶层目录 → 拒绝。新增顶层目录必须提升 major 版本。

### 5.2 条目约束

- 名称：UTF-8、相对路径、不以 `/` 开头、不含 `..` 段、不含反斜杠、长度 ≤ 4096。
- 类型：普通文件、目录、符号链接、硬链接；禁止字符设备/块设备/FIFO/套接字。
- 权限：保留 `0777` 位；**强制清除 setuid/setgid/sticky 位**。
- uid/gid：忽略，安装后归 root。
- 硬链接：只能指向同一 payload 内已出现的 `files/` 路径。
- 符号链接：允许绝对目标（兼容系统库），但安装器必须防止“先建链接、再写入链接目标”的越界写入；实现上先解压到暂存目录并做路径/链接校验，再合并进前缀。
- 重复路径：拒绝（防覆盖歧义）。
- 文件数 ≤ 100,000；单文件 ≤ 4 GiB；解压总量 ≤ 8 GiB（可配置）。

### 5.3 文件内容处理

- `files/` 下首行为 `#!` 且包含 `/data/data/com.termux/files/usr` 的文件：替换为目标前缀（只替换前缀部分）。
- 其余内容逐字节保留。

## 6. 安装语义

1. 读取魔数与 header → 校验 manifest → 校验 payload 哈希。
2. 验证签名（默认强制，见第 9 节）；失败则拒绝。
3. 静态恶意检测（默认开启）；命中阻断规则则拒绝并删除缓存包。
4. 归一化为 `PackageMeta`（与 deb 解析结果同构）。
5. 依赖解析、拓扑排序。
6. 执行 `scripts.preinst`。
7. 流式解压 payload，`files/` 写入前缀，`control/` 暂存。
8. 执行 `scripts.postinst`。
9. 写状态库（版本、来源、文件清单、格式标记 `lbpa`、签名者、检测结果）。
10. 重写 `env.sh`（含 `env` 段）。
11. 生成 wrapper 并同步到暴露后端（overlay/元模块）。
12. 执行 `hooks.install`。

卸载：

1. 执行 `scripts.prerm`、`hooks.remove`。
2. 按「状态库文件清单 ∪ 事务文件清单」删除前缀内文件，清理空目录；
   `data`（deb 为 `Conffiles`）默认跳过，`--force` 时一并删除。
3. 删除 wrapper、清理 `env.sh` 段、删除钩子。
4. 执行 `scripts.postrm`。
5. 删除状态行、事务文件、`db/scripts/<name>`、暂存与升级备份目录。

默认卸载后数据文件仍保留，其清单记录在 `$DB/data/<name>.list`；
此后再执行 `lb remove --force <name>` 可单独清掉这批数据。

安装事务与崩溃清理：

- 解包先进暂存目录 `$BASE/cache/stage/<name>`，**不直接写前缀**；此时崩溃只留下暂存目录。
- 暂存完成后把完整文件清单写入事务文件 `$DB/transactions/<name>.json`，再整体并入前缀。
- 事务文件在安装成功后才删除；失败/被杀则保留，`lb remove <name>` 以「状态库 ∪ 事务清单」清理。
- `lb remove` 同时清理按包名约定的目录：暂存、升级备份、维护脚本；因此安装任意阶段中断都能被完全卸载。

升级（任意版本 → 任意版本）：

1. 解析新包，取旧状态快照（旧文件清单、旧脚本、旧版本）。
2. 执行旧包 `scripts.prerm upgrade <new-version>`；失败中止，旧包保持原样。
3. 按 `upgrade.keep` 备份旧配置。
4. 落盘新包脚本；执行 `upgrade.scripts.pre`（缺失时 `scripts.preinst`），参数 `<old-version>`。
5. 流式解包覆盖前缀。
6. 恢复 `keep` 路径；删除（旧清单 − 新清单 − keep）与 `upgrade.remove` 中的路径。
7. 执行 `upgrade.scripts.post`（缺失时 `scripts.postinst`）、`hooks.install`。
8. 写状态库并重建 wrapper。

全程不重启。

## 7. 兼容性与版本规则

- **major**（`formatVersion`）：不兼容变更。读取方支持集合 `{1}`；遇到更高版本必须拒绝并提示“请升级 lb”，不得猜测解析。
- **同 major 内**：
  - 新增可选字段：允许，读取方忽略未知字段；
  - 必选字段不可删除、不可改语义、不可改类型；
  - 枚举（如 `compression`）新增值：读取方遇到未知值必须拒绝，不得静默回退；
  - 新增 payload 顶层目录：视为 major 变更。
- **minLbVersion**：语义化版本比较；lb 低于该版本拒绝安装。
- **确定性打包（可选）**：条目按路径排序、mtime 归零、固定 uid/gid，便于复现与差分。
- **保留字段**：`signature`、`relations.recommends/suggests`、`flags` 未用位；不得另作他用。
- **扩展后缀**：新增后缀不影响格式；读取以魔数为准。
- **错误信息**：格式错误必须指出“魔数/manifest/payload/路径”中的具体阶段，便于排查。

## 8. 与 deb 的互操作

| deb 概念 | LBPA 映射 |
|---|---|
| control `Package/Version/Architecture` | `package.*` |
| `Depends/Pre-Depends/Provides/Conflicts/Breaks/Replaces` | `relations.*` |
| `Description/Homepage/Maintainer` | 顶层同名字段 |
| `Installed-Size` | `installedSize` |
| control.tar 的 `preinst/postinst/prerm/postrm` | `scripts.*`（拷入 `control/`） |
| data.tar 的文件树 | `files/` |
| 无对应项 | `env`、`expose`、`hooks`、`gui` 为 LBPA 扩展 |

- `lb pack --from-deb foo.deb -o foo.lbpa` 执行上述映射；未知 control 字段写入 `x-deb-control`（可选）保留。
- 安装 `.deb` 时直接走 deb 分支，行为与 LBPA 归一后一致。
- 仓库索引可写 `Format: deb|lbpa`；缺省按 `Filename` 后缀与内容探测。

## 9. 签名、证书链与完整性

### 9.1 信任模型

采用类似 HTTPS 的证书链：

```
根证书（用户自签，离线保管私钥）
└── 二级证书（由根签发；可继续签发三级证书，也可直接签包）
    └── 包签名（用二级/叶证书私钥签名）
```

- 信任锚：**用户根证书的公钥**，固定在 lb 信任库；只有能链到该根的签名才被接受。
- 二级证书可授权给社区构建者；其签发的包被视为“授权签名包”。
- 根证书本人可直接签包，也可签发二级证书。
- 默认策略 `require_signature=true`：无签名、链不完整、过期、越权、被撤销的包一律拒绝。
- 第二条合法路径：**仓库签名**（apt `Release.gpg`/`InRelease` OpenPGP）——Termux 官方默认受信，包哈希由已签名索引保证，见 9.8。
- 开发者模式可关闭强制签名，但**签名存在却验证失败时仍然拒绝**（不允许“坏签名按未签名处理”）。

### 9.2 算法

| 项 | 取值 |
|---|---|
| 密钥 | ECDSA P-256 |
| 摘要/签名 | SHA-256 with ECDSA |
| 编码 | 公钥：X.509 SubjectPublicKeyInfo（DER）Base64；签名：DER Base64 |
| 证书 | JSON（见 9.3），非 X.509；语义对齐 X.509 的链、有效期、用途、路径长度 |

选择 ECDSA P-256 是为了 Android 全版本可用，不依赖 BouncyCastle 或新 API。

### 9.3 证书 JSON

```json
{
  "version": 1,
  "serial": "01",
  "subject": "LayerBridge Community A",
  "issuerKeyId": "<上级证书 keyId；根证书等于自身 keyId>",
  "keyId": "<SHA-256(公钥 SPKI DER) 小写 hex>",
  "publicKey": "BASE64(SPKI DER)",
  "notBefore": "2026-10-01T00:00:00Z",
  "notAfter": "2028-10-01T00:00:00Z",
  "isCA": true,
  "pathLen": 1,
  "usages": ["cert-signing", "package-signing"],
  "packagePrefixes": ["com.example."],
  "signature": "BASE64(DER)"
}
```

- `usages`：`cert-signing`（可签发下级证书）、`package-signing`（可签包）；根证书两者都有。
- `pathLen`：仅 `isCA=true` 有效，`0` 表示只能签发叶证书；`-1` 表示不限制（不推荐）。
- `packagePrefixes`：限制可签的包名前缀；子证书取交集；空数组表示不限制。
- 有效期：根 ≤ 10 年，二级 ≤ 3 年，叶 ≤ 2 年；安装时校验当前时间。
- `keyId` 必须与 `publicKey` 的 SHA-256 一致。

**证书签名对象**（UTF-8，字段顺序固定）：

```
LBPA-CERT-V1
<serial>
<subject>
<issuerKeyId>
<keyId>
<publicKey>
<notBefore>
<notAfter>
<isCA:true|false>
<pathLen 或 ->
<usages 逗号分隔，排序>
<packagePrefixes 逗号分隔，排序；空为 ->
```

- 证书 JSON 的 `signature` 字段不参与上述串。
- 根证书自签：`issuerKeyId == keyId`，用自身公钥验证。
- 链验证：逐级 `signature`、`keyId`、`issuerKeyId` 匹配，检查 `isCA`、`pathLen`、`usages`、有效期、包名前缀、撤销列表。

### 9.4 包签名（LBPA footer）

已签名包在 payload 之后追加：

```
u32 signatureLength (BE)
signature JSON (UTF-8)
```

```json
{
  "version": 1,
  "algorithm": "ecdsa-p256-sha256",
  "keyId": "<签名证书 keyId>",
  "certChain": [ {叶证书}, {二级证书} ],
  "signature": "BASE64(DER)",
  "signedAt": "2026-10-28T00:00:00Z"
}
```

- `certChain` 从签名证书到二级证书；根证书可省略（信任锚固定），若包含必须与信任根一致。
- 若由根证书直接签包：`certChain` 可为空数组，此时 `keyId` 必须等于信任根 `keyId`。
- **签名对象**（UTF-8）：

```
LBPA-PKG-V1
<sha256(manifest 原始字节) 小写 hex>
<sha256(payload 压缩字节) 小写 hex>
```

- 验证顺序：魔数/header → manifest 解析 → 证书链 → 签名 → payload 哈希 → 才允许解包。
- payload 哈希在流式解压时再次计算并比对；不一致立即中止。
- `flags.bit0` 与实际 footer 必须一致；不一致视为格式错误。

### 9.5 deb 的分离签名

deb 不能内嵌 footer，使用同名分离签名：

```
foo_1.0_aarch64.deb
foo_1.0_aarch64.deb.sig
```

- `signature JSON` 结构与 9.4 相同，`certChain` 照常。
- **签名对象**：

```
LB-DEB-V1
<sha256(整个 deb 文件) 小写 hex>
```

- 仓库索引可选字段 `Signature:` 指向签名文件；缺省约定为 `Filename + ".sig"`。
- 本地 deb 安装：需同时选择 `.deb` 与 `.sig`（多选）；无法配对时按未签名处理。
- 推荐工作流：`lb pack --from-deb foo.deb --sign ...` 转成已签名 LBPA。（工具未实现；当前用 PC 端 `scripts/lb-sign.py` 签 deb）

### 9.6 信任库与策略

| 项 | 说明 |
|---|---|
| 信任根 | `/data/adb/lb/trust/root.json`（0600） |
| 初始化 | PC 端 `scripts/lb-sign.py init` 生成根证书与私钥；把 `certs/lb-root.json` 复制为 `trust/root.json` |
| 私钥 | 只保存在 PC（`~/.config/layerbridge/root-key.pem`，0600）；设备不存私钥 |
| 策略 | 由调用方传入：App 开发者开关，或 CLI 的 `--allow-unsigned`、`--skip-scan`；没有全局配置文件 |
| 受信仓库 | 目前内置 Termux 官方一条（代码常量 + `assets/trust/termux-autobuilds.gpg`）；自定义仓库导入未实现 |
| 撤销 | 未实现 |
| 根轮换 | 不支持；替换 `root.json` 即换根，旧根不再受信 |

安装时的签名判定：

1. 包签名有效（证书链到信任根）→ 通过，`signer=<subject>`。
2. 包无签名，但来源仓库在受信列表且 `Release.gpg`/`InRelease`、`Packages`、deb 三层哈希全部验证通过 → 通过，`signer=repo:<id>`。
3. 包签名存在但链/有效期/用途/前缀/撤销任一失败 → 拒绝（退出码 7），**即使策略关闭也不放行**。
4. 两条路径都不满足且 `require_signature=true` → 拒绝（退出码 6）。
5. 两条路径都不满足且策略关闭 → 允许，UI/日志显著警告。

### 9.7 错误分类

当前 CLI 统一返回 `0`（成功）/`1`（失败）/`2`（用法错误）；本节列出的细分退出码为规划。

| 场景 | 结果 |
|---|---|
| 无签名（策略开） | 拒绝，提示“未授权签名” |
| 证书链断/根不匹配 | 拒绝，提示信任根 |
| 证书过期/未生效 | 拒绝，提示有效期 |
| 用途不符/包名前缀不符 | 拒绝，提示权限约束 |
| 证书被撤销 | 拒绝，提示撤销原因 |
| 签名值错误/哈希不符 | 拒绝，删除缓存包 |
| manifest/payload 被篡改 | 拒绝，删除缓存包 |
| 仓库 `Release.gpg` 无效 | 拒绝该仓库的包（退出码 7） |
| `Packages` 与 `Release` 哈希不符 | 拒绝，删除索引缓存 |
| deb 与 `Packages` 哈希不符 | 拒绝，删除缓存包 |
| `Release` 过期（>90 天） | 告警；超过阈值拒绝 |

### 9.8 仓库签名（apt / OpenPGP）

apt 仓库不对单个 deb 签名，而是对索引签名。lb 把“受信仓库 + 已签名索引”作为第二条授权路径，因此 Termux 官方包默认可以直接安装，无需重签。

- 受信仓库目前内置在代码与 assets 中；下面的 JSON 是规划中的自定义仓库格式（未实现）。

```json
{
  "id": "termux-main",
  "url": "https://packages.termux.dev/apt/termux-main",
  "suite": "stable",
  "component": "main",
  "architecture": "aarch64",
  "keyFingerprint": "CC72CF8BA7DBFA0182877D045A897D96E57CF20C",
  "keyFile": "termux.gpg",
  "enabled": true
}
```

- 验证链：
  1. 获取 `Release` 与 `Release.gpg`（或 `InRelease`），用条目中固定指纹的 OpenPGP 公钥验证签名；
  2. 校验 `Release` 的 `Date` 新鲜度（默认 90 天，可配置；超期告警/拒绝）；
  3. 从 `Release` 的 `SHA256` 段校验下载的 `Packages`；
  4. 从 `Packages` 的 `SHA256` 校验 deb 文件；
  5. 通过后 `signer=repo:<id>`。
- Termux 官方默认条目：`termux-main`，当前签名指纹 `CC72CF8BA7DBFA0182877D045A897D96E57CF20C`。
- 公钥轮换：新指纹必须由用户显式确认后更新条目；不允许从网络静默替换。
- 未授权仓库：默认按未签名处理；`lb trust repo add` 需展示并确认指纹。
- OpenPGP 解析/验签使用 BouncyCastle `bcpg`；不依赖系统 `gpg`。
- 与包签名的关系：包签名优先；包未签名时走仓库路径；两者都没有才按策略拒绝。
- 本地目录仓库没有 `Release.gpg`，默认视为未签名，除非逐包签名。

