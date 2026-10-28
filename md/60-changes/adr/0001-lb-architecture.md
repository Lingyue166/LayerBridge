# ADR-0001：lb 采用原生执行 + 模块热环境

- 状态：Proposed
- 日期：2026-10-28
- 关联设计：[lb 设计文档](../../00-design/lb-architecture.md)

## 背景

目标是在 Android 上安装并运行 Linux 软件包，要求热安装/卸载、模块安装一次重启后环境自动生效、钩子与依赖自动处理，并提供 `lb` 命令；包源包括 Termux 官方仓库、自编译包与社区包。

## 决策

1. **原生执行**：只安装 bionic/aarch64 的 `.deb`，由 Android 内核与 `/system/bin/linker64` 直接加载；不使用 chroot、proot、虚拟机或 Termux 沙箱。
2. **前缀**：包安装到 `/data/local/lb/usr`（普通 shell 可执行），不走 `/data/adb`。
3. **三件套**：Compose App + Magisk 格式模块 `lb` + CLI `lb`；App 与 CLI 共用纯 Kotlin 核心。
4. **模块职责**：只提供 `/system/bin/lb`、`mkshrc` 环境注入、开机钩子；包管理全部在热路径完成。
5. **环境热生效**：每次装/卸重写 `env.sh`；模块注入的 `mkshrc` 在新开交互 shell 中自动 source，无需重启。
6. **钩子与依赖**：依赖拓扑安装；`preinst/postinst` 立即执行；开机钩子由模块 `service.sh` 重放。
7. **Termux 兼容**：安装时重写 shebang，运行时注入 `PREFIX`/`LD_LIBRARY_PATH`；绝对路径场景可选前缀桥（实验）。
8. **元模块**：system shim 模式依赖第三方 overlay/元模块实现 `/system/bin` 热合并，列为后续阶段。

## 后果

- 需要 root（KSU/SukiSU 管理器授权后无弹窗；Magisk 首次弹窗）。
- 环境自动注入只覆盖新开的交互 shell；其他场景使用 `lb shell` / `lb run` / 元模块。
- 不支持 glibc 包；自编译包必须以目标前缀构建或接受重写。
- 模块 system 文件更新需重启，除非使用元模块。
- 无真机的运行时行为（SELinux、挂载、app_process）需在目标设备验证。

## 备选方案（未采用）

| 方案 | 原因 |
|---|---|
| chroot + Ubuntu/Debian rootfs | 用户明确要求原生执行；需 root 挂载，路径/发行版不匹配 |
| proot | 性能差，非原生，且需要额外打包 proot |
| 直接使用 apt/dpkg 引擎 | Termux 二进制前缀写死，定制前缀需大量参数覆盖；自研安装器更可控 |
| 安装到 `/system` | 改动系统分区，风险高，违背热路径 |
