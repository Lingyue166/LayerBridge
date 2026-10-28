# ADR-0002：系统级暴露与包格式

- 状态：Proposed
- 日期：2026-10-28
- 关联设计：[lb 设计文档](../../00-design/lb-architecture.md)
- 补充：[ADR-0001](0001-lb-architecture.md)

## 背景

新增需求：

- 安装对象包含 CLI 工具与图形化工具（图形运行时由使用者解决）。
- `lb` 与已安装命令必须在任意终端可用，且所有 root 应用可用并拿到正确环境。
- 包不允许是 Magisk/KernelSU 模块，只能是 lb 自有格式或通用格式。
- 包的安装/卸载不允许重启。

## 决策

1. **包格式只收 `.deb`**：Termux/自编译/社区包统一；可选 lb 元数据（`lb-hooks/`、`lb-env`、`lb-expose`）放在 `control.tar` 内。显式拒绝 zip、`module.prop`、`customize.sh`。
2. **包操作永不重启**：安装/卸载/升级全部走热路径，只改 `/data/local/lb` 与 overlay upper。
3. **系统级暴露用 wrapper + overlay**：每个可执行文件生成 `/system/bin` wrapper，wrapper 注入 `PREFIX`、`PATH`、`LD_LIBRARY_PATH` 后 exec 真实路径；启动时在 zygote 前把 `/system/bin` 叠加为 overlay（`upper=/data/local/lb/system/upper`），热写入即时可见。
4. **元模块为备选**：若已装第三方元模块，则 wrapper 写入 `/data/adb/modules/lb/system/bin/`，由元模块热合并；与自有 overlay 二选一。
5. **“所有 root 应用获得环境”定义为命令级**：root 应用执行 `su -c 命令` 时经 `/system/bin` 命中 wrapper，环境随进程注入；不修改已运行进程的环境。
6. **GUI 包不做特殊管理**：按 `.deb` 安装，依赖照常解析；wrapper 保留 `DISPLAY`/`XDG_RUNTIME_DIR`/`WAYLAND_DISPLAY`，显示服务由使用者解决。
7. **模块只装一次**：仅 lb 模块首次安装需要一次重启；此后包操作不重启。

## 后果

- 需要内核 overlayfs 或元模块；否则只能退化为交互 shell 环境（`env.sh` + `mkshrc`）。
- 系统级 wrapper 需跳过与系统同名的命令，覆盖需显式 `--force`。
- 已运行进程的环境不会变化；新进程按 wrapper 获得环境。
- overlay 与 Magisk/其他模块的挂载顺序敏感，需启动时检测与让位。
- 仍需真机验证 SELinux 上下文、overlay 挂载与 root 应用调用。

## 备选方案（未采用）

| 方案 | 原因 |
|---|---|
| 安装 Magisk/KSU 模块 | 必须重启，违反 R10；且污染 `/system` |
| 只做 `mkshrc` 环境注入 | 覆盖不到 root 应用与非交互进程 |
| 修改 `su`/替换系统命令 | 风险过高，影响整个系统 |
| 安装到 `/system` 普通目录 | 需重挂载/重启，不可热卸载 |
