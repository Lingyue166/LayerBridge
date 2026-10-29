# 自建包计划

| 项目 | 内容 |
|---|---|
| 状态 | 阶段 0、1、5 已构建；阶段 2/3 部分完成；阶段 4 未开始 |
| 更新日期 | 2026-10-29 |
| 关联决策 | 自建源替代 Termux 源 |

## 原则

- 只用上游官方源码 / 官方发布物，不搬 Termux 预编译包。
- 编译目标：aarch64 Android bionic，NDK r29，API 29，prefix 固定 `/data/adb/lb/usr`。
- 包格式：deb（`data/` 相对前缀根），签名用 lb 根证书；仓库索引由 CI 生成并 OpenPGP 签名。
- App 默认源指向自建仓库；Termux 只保留信任材料作参考，不再作为默认源。

## 构建方式

| 方式 | 适用 | 说明 |
|---|---|---|
| autotools | 传统 C 项目 | `--host=aarch64-linux-android` + NDK 工具链 |
| CMake | 现代 C/C++ | `android.toolchain.cmake`，`ANDROID_ABI=arm64-v8a` |
| cargo | Rust 项目 | target `aarch64-linux-android`，需配置 linker |
| 上游脚本 | 自带 Makefile | 覆盖 `CC/AR/RANLIB/CFLAGS` |
| termux-packages 配方 | 依赖复杂的包 | fork 后把 prefix 改成 `/data/adb/lb/usr`，只借用配方 |

## 阶段 0：已构建（13 个）

| 包 | 用途 | 备注 |
|---|---|---|
| sqlite3 | 数据库 CLI | amalgamation 单文件 |
| lua | 解释器 | 不含 luac |
| jq | JSON 处理 | 内置 oniguruma |
| ripgrep | 搜索 | `rg` |
| fd | 文件查找 | |
| bat | 带高亮 cat | 依赖系统 libz |
| bzip2 | 压缩 | 含 bunzip2/bzcat |
| xz | 压缩 | 含 unxz/xzcat |
| zstd | 压缩 | |
| lz4 | 压缩 | |
| make | 构建 | 已修 bionic `confstr(_CS_PATH)` |
| file | 文件类型 | 自带 magic.mgc |
| fish | shell | ncurses/pcre2 静态，捆绑 libc++ |

产物在 `lb-oss/`（项目外），未上传。

## 阶段 1：基础库（已构建 15 个）

产物在 `lb-libs/`（项目外，未上传）：zlib 1.3.1、pcre2 10.45、expat 2.6.4、libffi 3.4.7、libiconv 1.17、c-ares 1.34.5、ca-certificates 20240618、openssl 3.5.4、libssh2 1.11.1、nghttp2 1.66.0、nghttp3 1.9.0、ngtcp2 1.13.0、ncurses 6.5、readline 8.2、libicu 79.1。合计 29.5 MiB，全部已签名。


| 包 | 用途 | 关键依赖 |
|---|---|---|
| zlib | 压缩库 | — |
| openssl | TLS/证书 | — |
| pcre2 | 正则 | — |
| ncurses | 终端 | — |
| readline | 行编辑 | ncurses |
| libffi | FFI | — |
| expat | XML | — |
| libiconv | 编码 | — |
| libicu | Unicode | — |
| libssh2 | SSH 客户端库 | openssl |
| libnghttp2 / libnghttp3 / libngtcp2 | HTTP/2、HTTP/3 | openssl |
| c-ares | 异步 DNS | — |
| ca-certificates | 根证书 | openssl |
| sqlite (lib) | 嵌入式数据库 | — |
| zlib-ng? | 可选 | 暂缓 |

## 阶段 2：工具链与解释器

| 包 | 用途 | 依赖 | 说明 |
|---|---|---|---|
| clang / llvm | 编译器 | libc++、zlib | 体积大，优先 lld |
| lld | 链接器 | llvm | |
| cmake | 构建 | — | 自举较慢 |
| ninja | 构建 | — | |
| pkg-config | 构建辅助 | glib? | |
| git | 版本控制 | curl、expat、pcre2、zlib | 需处理 libexec 路径 |
| python3 | 解释器 | openssl、sqlite、libffi、ncurses、readline | 交叉编译要补 `_sysconfigdata` |
| nodejs | 运行时 | openssl、libicu、zlib | 交叉编译需 host 工具 |
| npm | 包管理 | nodejs | 随 nodejs 发布 |
| pnpm | 包管理 | nodejs | 独立的 JS 包，无需编译 |
| rust / cargo | 工具链 | — | target aarch64-linux-android |
| zig | 交叉编译 | — | 体积大，可后置 |
| gdb | 调试 | python? | 可选 |

交叉编译环境本身（NDK sysroot、clang wrapper、cmake toolchain 文件）随构建镜像提供，不再单独打包。

## 阶段 3：常用 CLI 与系统工具

已构建（`lb-pkgs/`）：tar 1.35、gzip 1.13、bzip2 1.0.8、unzip 6.0、zip 3.0、7zip 26.05、less 668、tree 2.2.1、fzf 0.74.5、htop 3.3.0、tmux 3.5a、ncdu 1.21、libevent 2.1.12、curl 8.23.0、git 2.56.0、tcpdump 4.99.5、nftables 1.1.1、iptables 1.8.11、libpcap 1.10.5、libmnl 1.0.5、libnftnl 1.2.8。

剩余：wget、yq、lsof、strace、ufw（需 python3）。


| 包 | 用途 | 依赖 |
|---|---|---|
| curl | HTTP 客户端 | openssl、nghttp2、zlib |
| wget | 下载 | openssl、zlib |
| tar | 归档 | — |
| gzip | 压缩 | zlib |
| unzip / zip | zip 工具 | zlib、bzip2 |
| 7zip | 7z | libc++ |
| less | 分页 | ncurses |
| tree | 目录树 | — |
| fzf | 模糊查找 | — |
| htop | 进程查看 | ncurses |
| tmux | 终端复用 | ncurses、libevent |
| ncdu | 磁盘占用 | ncurses |
| yq | YAML 处理 | — |
| eza / zoxide / dust / procs | 常用替代工具 | — |
| lsof / strace | 诊断 | — |
| gdb | 调试 | — |
| tcpdump | 抓包 | libpcap |
| nftables | 防火墙 | libmnl、libnftnl |
| iptables | 防火墙（兼容） | — |
| ufw | 防火墙前端 | iptables、python3 |

Android 上防火墙受 SELinux 和 netfilter 配置限制，先打包，能否实际生效另测。

## 阶段 4：音频与媒体

| 包 | 用途 | 依赖 | 说明 |
|---|---|---|---|
| ffmpeg | 音视频处理 | zlib、openssl、x264/x265、lame、opus | 依赖最多，放后面 |
| sox | 音频处理 | — | |
| flac / lame / opus-tools | 编解码 | — | |
| cava | 音频可视化 | ncurses、fftw、pulseaudio? | 需要音频输入，Android 上可能受限 |
| mpv | 播放器 | ffmpeg、SDL | 依赖重，可选 |

## 阶段 5：趣味小工具（已构建）

figlet 2.2.5、sl 5.05、fortune 3.26.1、cmatrix 2.0、cowsay 3.04（脚本，需 perl）、lolcat 1.5（C 实现）。


| 包 | 用途 |
|---|---|
| cowsay | 牛说话 |
| lolcat | 彩虹输出 |
| figlet / toilet | 字符画 |
| sl | 小火车 |
| fortune | 随机句子 |
| cmatrix | 数字雨 |
| yes / oneko 等 | 视情况 |

## 仓库格式

- 索引：`dists/stable/Release`、`Release.gpg`、`Packages`、`Packages.gz`
- 目录：`pool/main/<首字母>/<包名>/<包名>_<版本>_aarch64.deb`
- 签名：仓库 OpenPGP 子密钥（与包签名根分开），公钥打进 App 的 `assets/trust/`
- 客户端：`lb` 已有 Release → Packages → deb 三层校验，签名域已经实现

## 暂不做

- 直接安装 Termux 预编译包（路径写死，已放弃）
- 图形界面应用、X11/Wayland、浏览器
- 需要内核模块或系统服务常驻的工具
- 32 位 / x86 目标

## 下一步

1. 搭构建脚本（LBPA/`lb-pack` 或 deb 打包）与仓库索引生成器
2. 从阶段 1 开始按依赖顺序编，阶段 0 的包先进仓库
3. App 配置自建源地址，联调索引与一键安装
