# 0006 状态单一来源：前缀数据库

- 状态：Accepted
- 日期：2026-10-28
- 关联：[0001](0001-lb-architecture.md)、[0002](0002-lb-system-exposure.md)

## 背景

App 把状态放在私有目录 `filesDir/state`，每次变更后再通过 `su` 复制一份到
`/data/local/lb/db`。CLI（`lb`，app_process，以 root 运行）只能读前缀副本。
两份状态没有合并机制：CLI 一旦能写，App 就读到旧数据；反之亦然。

## 决策

- `/data/local/lb/db` 是唯一状态源：`installed.tsv`、`info/*.list`、`scripts/*`。
- 新增 `StateStorage` 抽象，`StatusDb` 只负责编码/解码与组合：
  - `RootStateStorage`：App 使用，经 `Shell`（`su`）读写；
  - `LocalStateStorage`：CLI 与单测使用，直接 `File` 读写。
- `StatusDb` 的读写方法改为 `suspend`，`syncPrefixDb` 删除。
- 状态文件格式不变（9 列 + 可选第 10 列 JSON），见 [APIs](../../../app/src/main/java/com/lingyue/layerbridge/state/StatusDb.kt)。

## 后果

- 每次状态操作增加 1–3 次 `su` 调用；安装/卸载本身远慢于该开销。
- 旧版本的 App 私有状态不再读取。当前未真机发布，不做迁移：重新安装或重新初始化前缀即可。
- App 与 CLI 天然看到同一份状态，`lb install/remove` 才有意义。

## 被否方案

- 保留双写 + 时间戳/版本号合并：冲突无法判定，规则只会越加越多。
- App 直接 `File` 访问前缀库：跨 uid 且受 SELinux 限制，写会失败。
- 用 ContentProvider/Socket 做状态服务：为一个包管理器引入 IPC 服务，违背 KISS。
