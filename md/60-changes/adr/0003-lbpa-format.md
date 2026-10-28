# ADR-0003：引入 LBPA（LAYERBRIDGEPACKONANDROID）包格式

- 状态：Proposed
- 日期：2026-10-28
- 后续修订：签名方案由 [ADR-0004](0004-signing-and-scan.md) 取代，v1 默认强制证书链签名，不再“预留”。
- 关联设计：[lb 设计文档](../../00-design/lb-architecture.md)
- 格式规范：[LBPA v1](../../00-design/lbpa-format.md)
- 补充：[ADR-0002](0002-lb-system-exposure.md)（包格式部分由本 ADR 扩展）

## 背景

- 需要继续兼容通用 `.deb`（Termux、自编译、社区包）。
- 还需要 lb 专属的包格式：只有 lb 能解析安装，带版本化元数据与兼容规则，支持依赖、脚本、钩子、环境、暴露列表。
- 包安装与卸载不允许重启；包不能是 root 管理器模块。

## 决策

1. 新增自有格式 **LAYERBRIDGEPACKONANDROID**，简称 **LBPA**，魔数 `LBPA\r\n\x1a\n`。
2. 后缀 `.lbpa`、`.lpa`、`.lb`、`.lbp` 指向同一格式；解析以魔数为准，后缀仅辅助。
3. 容器：8 字节魔数 + `u16` 格式版本 + `u16` flags + `u32` manifest 长度 + UTF-8 JSON manifest + 压缩 tar payload。
4. payload 顶层固定为 `control/`（脚本、钩子）与 `files/`（安装树）；v1 拒绝未知顶层目录。
5. 兼容规则：`formatVersion` 主版本不兼容即拒绝；同主版本新增可选字段忽略、未知枚举/顶层目录拒绝；`minLbVersion` 拦截旧版 lb。
6. 完整性：payload SHA-256 必填强校验；签名机制后续由 ADR-0004 定义为强制证书链。
7. 与 `.deb` 双向互操作：`lb pack --from-deb` 无损转换（工具未实现）；安装器把两者归一为 `PackageMeta`。
8. 拒绝 zip、`module.prop`/`customize.sh` 模块包；LBPA 与 deb 均走热安装流程，不重启。

## 后果

- 需要维护格式规范、打包工具（`lb pack`）、检查工具（`lb inspect`）与跨版本兼容测试。
- 解析面变大：必须做魔数、长度、路径、链接、压缩与哈希校验，防止解压炸弹与路径穿越。
- 签名：v1 只保证完整性、不保证来源可信；该缺口由 ADR-0004 改为强制证书链解决。
- 仓库索引需支持 `Format: deb|lbpa`，并允许两种格式混合解析依赖。
- 后缀别名可能导致误关联，安装前仍以魔数为准。

## 备选方案（未采用）

| 方案 | 原因 |
|---|---|
| 只扩展 `.deb` 的 control 字段 | 无法可靠区分“lb 专属包”，也难做版本演进 |
| 直接使用 zip/tar 容器 | 缺乏明确魔数与版本边界，易被其他管理器误读 |
| 使用 Magisk 模块格式 | 违反“包不能是 root 管理器模块”和“不重启” |
| 每个包强制签名 | v1 无密钥分发与信任链，先预留；ADR-0004 起采纳 |
