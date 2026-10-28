# LayerBridge（lb）

Android 原生的 Linux 包管理器：把 bionic/aarch64 的 Linux 包安装到独立前缀，直接执行；配套 root 模块与 `lb` 命令。

设计文档：

- [架构设计](md/00-design/lb-architecture.md)
- [LBPA 包格式规范](md/00-design/lbpa-format.md)
- [ADR 索引](md/60-changes/README.md)

## 当前状态（MVP）

已实现：

- App（Compose）：软件包搜索/安装/卸载、环境页、开发者开关、实时日志。
- Termux 官方仓库索引：`Release.gpg` OpenPGP 验签 → `Packages` 哈希 → deb 哈希。
- 依赖解析：Depends / Pre-Depends / Provides / Conflicts / Breaks，拓扑排序。
- `.deb` 安装：ar 解析、control 读取、shebang 重定位、流式 tar 写入前缀；维护脚本 preinst/postinst/prerm/postrm 落盘 `db/scripts` 并在安装/卸载期执行。
- 本地 `.deb` 签名校验（`.sig`）与 LBPA 解析/签名校验（未配置信任根时拒绝）。
- 恶意检测：脚本规则扫描，命中阻断安装。
- 前缀 `/data/local/lb/usr`、`env.sh`、`/system/bin` wrapper（写 overlay upper）。
- 状态库（App 私有 + 前缀副本）。
- 模块资产与 `lb` 命令骨架（env/list/doctor/shell）。

尚未完成 / 已知限制：

- CLI `lb install/remove` 尚未实现（App 可安装；CLI 只读命令可用）。
- App 内“安装模块”按钮未实现；目前需手动把 `app/src/main/assets/module` 安装到 `/data/adb/modules/lb` 或用脚本产物。
- 信任根导入 / CA 签发 UI 未实现；本地包签名校验需要预置 `files/trust/root.json`。
- LBPA 打包工具（`lb pack`）与证书签发工具（`lb ca`）未实现。
- 未在真机验证；root、overlay、SELinux 行为以设计文档为准。
- 图形化运行时（X11/Wayland）不在本项目范围内。

## 构建

环境：JDK 17+、Android SDK（platform 36 / build-tools 36）、Gradle 8.11.1。

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"
export ANDROID_HOME="$HOME/Android/Sdk"

./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

模块包：

```bash
bash scripts/build-module.sh
# build/lb-module.zip
```

## 使用

1. 安装 APK，授予 root（KernelSU/SukiSU 在管理器内授权；Magisk 首次弹窗）。
2. 环境页 →“初始化前缀”，创建 `/data/local/lb` 并复制 `lb.apk`。
3. “更新索引”拉取 Termux 官方索引并验签。
4. 搜索并安装；日志在页面底部。
5. 模块安装后重启，`lb env` / 新开 shell 可获得前缀环境。

## 安全

- 默认只信任：你签发的证书链，或已授权仓库（Termux 官方，OpenPGP 指纹 `CC72CF8BA7DBFA0182877D045A897D96E57CF20C`）。
- 开发者模式可分别关闭“强制签名”和“自动检测”；坏签名仍拒绝，结构校验仍执行。
- 静态检测为启发式，存在误报/漏报。

## 技术栈

Kotlin 2.4.10 · Compose BOM 2026.03.01 · AGP 8.10.0 · Gradle 8.11.1 · commons-compress · XZ · BouncyCastle（OpenPGP） · Gson。
