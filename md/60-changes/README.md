# 变更记录

本目录记录设计决策与实现变更。

## 决策记录（ADR）

| 编号 | 标题 | 状态 | 日期 |
|---|---|---|---|
| [0001](adr/0001-lb-architecture.md) | lb 采用原生执行 + 模块热环境 | Proposed | 2026-10-28 |
| [0002](adr/0002-lb-system-exposure.md) | 系统级暴露与包格式 | Proposed | 2026-10-28 |
| [0003](adr/0003-lbpa-format.md) | 引入 LBPA（LAYERBRIDGEPACKONANDROID）包格式 | Proposed | 2026-10-28 |
| [0004](adr/0004-signing-and-scan.md) | 强制授权签名与恶意代码检测 | Proposed | 2026-10-28 |
| [0005](adr/0005-termux-repo-signature.md) | 允许 Termux 仓库签名（OpenPGP） | Proposed | 2026-10-28 |
| [0006](adr/0006-single-state-source.md) | 状态单一来源：前缀数据库 | Accepted | 2026-10-28 |
| [0007](adr/0007-stealth-by-default.md) | 默认隐藏：安装包只对 root 可见 | Accepted | 2026-10-28 |
| [0008](adr/0008-prefix-under-data-adb.md) | 前缀迁至 /data/adb/lb，非 root 不可见 | Accepted | 2026-10-28 |

> 0002 补充 0001：包操作不重启、系统级 wrapper + overlay、GUI 运行时不在范围内。
> 0003 扩展 0002：在 `.deb` 之外新增 LBPA 自有格式，两种格式并存，包安装/卸载仍不重启。
> 0004 补充 0003：LBPA footer / deb `.sig` 证书链签名，默认强制；静态恶意检测与开发者开关。
> 0005 补充 0004：增加仓库签名路径，Termux 官方默认受信，包签名仍然优先。
> 0006 补充 0001：状态只保留前缀一份，App 经 su 访问，CLI 直接访问；删除双写。
> 0007 修改 0002：默认不写 /system/bin、不挂 overlay，包只对 root 可见；系统暴露改为显式开启。
> 0008 补充 0007：前缀迁到 /data/adb/lb，普通应用连目录都无法 stat；含一次性迁移。

## 设计文档

- [lb 架构设计 v0.5](../00-design/lb-architecture.md)
- [LBPA 格式规范 v1](../00-design/lbpa-format.md)
