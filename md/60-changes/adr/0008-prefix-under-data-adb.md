# 0008 前缀迁至 `/data/adb/lb`：非 root 不可见

- 状态：Accepted
- 日期：2026-10-28
- 关联：[0007](0007-stealth-by-default.md)、[0002](0002-lb-system-exposure.md)

## 背景

ADR 0007 把前缀收成 `700` 并停止系统级暴露，但目录仍位于 `/data/local/lb`。
`/data/local` 是 `771 system:shell`，普通应用可遍历；`stat("/data/local/lb")`
仍能拿到「存在一个 root 拥有的目录」这一信息。对扫描型检测器来说，路径名本身就是特征。

## 决策

- 前缀根从 `/data/local/lb` 迁到 **`/data/adb/lb`**；`/data/adb` 是 `700 root:root`，
  普通应用无法遍历，`stat` 直接返回 `EACCES`——目录名与内容都不可见。
- 首次启动做一次性迁移：目标任务不存在且旧目标存在时 `mv /data/local/lb /data/adb/lb`；
  App 的 `bootstrap()` 与模块 `post-fs-data.sh` 各做一次，幂等。
- 所有路径常量、模块脚本、`lb` 启动脚本、文档同步更新。

## 后果

- 普通应用完全看不到 lb 的数据，符合「只让 root 应用可见」。
- 必须通过 `su` 使用（`lb shell` / `lb exec` / App 内部经 su）。
- 数据与 root 环境绑定：卸载 root 管理器可能带走 `/data/adb`，此时包不可用也属预期。
- `/data/adb` 的 SELinux 标签由 root 管理器维护，root 域写入即可，不新增策略需求。

## 被否方案

- 继续放 `/data/local/lb` 并随机化目录名：路径仍可被已知清单探测，且改名会破坏已有安装。
- 放 `/data/system/lb`：虽然同样不可见，但污染 Android 系统配置目录，语义不合适。
