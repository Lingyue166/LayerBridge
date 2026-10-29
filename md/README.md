# 文档索引

| 项目 | 内容 |
|---|---|
| 状态 | 现行 |
| 更新日期 | 2026-10-29 |
| 关联决策 | [ADR 索引](60-changes/README.md) |

## 设计

| 文档 | 结论 |
|---|---|
| [架构设计总览](00-design/lb-architecture.md) | 三部分：App、root 模块、CLI；§1–§6 定位、需求、术语、架构、目录、热路径 |
| [运行时设计](00-design/lb-runtime.md) | §7–§13 模块、暴露、包管理、Termux、钩子、overlay、CLI |
| [App、安全与验收](00-design/lb-app-security.md) | §14–§20 App、信任、验收、风险、附录 |
| [LBPA 格式规范](00-design/lbpa-format.md) | §1–§4 容器、后缀、布局、manifest |
| [LBPA 载荷、安装与签名](00-design/lbpa-payload-signing.md) | §5–§9 payload、安装语义、兼容、deb 互操作、签名 |
| [LBPA 安全约束与工具链](00-design/lbpa-tools.md) | §10–§13 安全约束、工具链、示例、待定项 |

## 构建

| 文档 | 结论 |
|---|---|
| [自建包计划](40-build/package-plan.md) | 官方源码 + NDK 编译，按基础库、工具链、CLI、音频、趣味分批；当前只整理不构建 |

## 决策

| 文档 | 结论 |
|---|---|
| [ADR 索引](60-changes/README.md) | 0001–0008 决策记录 |
