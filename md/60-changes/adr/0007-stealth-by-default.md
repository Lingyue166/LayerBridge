# 0007 默认隐藏：安装包只对 root 可见

- 状态：Accepted
- 日期：2026-10-28
- 关联：[0002](0002-lb-system-exposure.md)、[0006](0006-single-state-source.md)

## 背景

原设计默认把命令写进 `/system/bin`（overlay upper + 模块 bin），并向前缀
`chmod 755`。这带来三个普通应用可观测的信号：

1. `/proc/mounts` 里能看到 `lowerdir=/system/bin,upperdir=/data/local/lb/...` 的 overlay；
2. `/system/bin` 目录里出现非系统镜像的命令文件；
3. 前缀目录 `755`，只要知道路径就能 `stat`/遍历。

此外 mkshrc bind mount 也会在挂载表留下痕迹。

## 决策

- 默认 **STEALTH** 模式：前缀 `chmod 700`（只有 root 能进入），wrapper 只写
  `$BASE/system/bin`（root-only），不写 `/system/bin`、不挂 overlay、不改 mkshrc。
- 系统级暴露改为显式开启：存在 `$BASE/config/expose-system` 时进入 **SYSTEM** 模式，
  恢复 overlay + 模块 bin + mkshrc 注入，并把前缀放宽到 `755`。
- 新增 `lb exec <命令>`：在 root 下以完整前缀环境执行一条命令，替代「命令必须在 PATH 里」。
- `/system/bin/lb` 脚本自身要求 uid=0，普通用户即使看到路径也无法使用。

## 后果

- 普通应用看不到、也执行不了已安装的包；root 应用经 `su` 正常使用。
- 少了「直接敲命令」的便利：需要 `su -c 'lb shell'` 或 `su -c 'lb exec hello'`。
- 开启 SYSTEM 模式会重新引入 mount/目录可检测点，由用户显式承担。
- 残留信号：模块目录 `/data/adb/modules/lb` 与 `/system/bin/lb` 文件本身（root-only 可用），
  root 检测仍能发现 root 环境本身——这部分不在本 ADR 解决范围。

## 被否方案

- 全局 permissive / 改内核：解决不了「root 本身可检测」，还扩大攻击面。
- 随机化前缀路径：仍需固定入口，收益低、维护成本高。
- 隐藏 `/system/bin/lb`：可以删模块里的 `system/bin/lb` 来达成，但会牺牲发现性，作为用户可选。
