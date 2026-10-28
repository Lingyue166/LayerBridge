# LBPA 安全约束与工具链

| 项目 | 内容 |
|---|---|
| 状态 | 草案 |
| 更新日期 | 2026-10-29 |
| 所属 | [LBPA 格式规范](lbpa-format.md) |
| 范围 | §10 安全约束 · §11 工具链 · §12 示例 manifest · §13 待定项 |

> 上篇：[LBPA 载荷、安装与签名](lbpa-payload-signing.md)

## 10. 安全约束

- 先验魔数，再解析 header；header 越界立即拒绝。
- manifest ≤ 1 MiB；JSON 解析失败 → 拒绝。
- 路径校验（绝对路径、`..`、反斜杠、重复、长度）。
- 解压到暂存目录，校验链接与路径后再合并；禁止通过符号链接写到前缀外。
- 清除 setuid/setgid/sticky。
- 拒绝含 `module.prop`/`customize.sh` 的“伪 LBPA”内容（模块包伪装）。
- 安装脚本继承受限环境，不继承调用方的敏感变量（仅白名单透传）。
- 下载后先校验哈希与签名再解析；校验失败不落盘到前缀，删除缓存副本。
- 默认只安装授权签名包；开发者模式关闭签名要求时仍执行结构校验与（若开启的）恶意检测。
- 仓库签名：固定指纹、拒绝静默轮换；`Release`/`Packages`/deb 三层哈希缺一不可；`Date` 超期处理。
- 静态恶意检测命中“明确恶意”规则时，阻止安装并删除 lb 缓存中的包；本地导入文件默认隔离，经确认后删除。

## 11. 工具链

PC 端已提供 `scripts/lb-pack.py`（from-deb / from-dir）与 `scripts/lb-sign.py`（init / sign-deb）。
命令行 `lb pack` 未实现。

```sh
# 从目录打包
lb pack ./rootfs \
  --name hello --version 1.0-1 --arch aarch64 \
  --depends "libc++" --expose bin/hello \
  -o hello_1.0-1_aarch64.lbpa

# 从 deb 转换
lb pack --from-deb hello.deb -o hello_1.0-1_aarch64.lbpa

# 检查
lb inspect hello.lbpa
# 输出：格式、版本、包元数据、payload 哈希、脚本、hooks、expose

# 安装
lb install hello.lbpa

# 证书与签名
lb ca init --subject "My LayerBridge Root"
lb ca issue --ca root --subject "Community A" --is-ca --path-len 0 \
  --usage package-signing --prefix "com.example."
lb sign hello.lbpa --key community.key --cert community.json
lb verify hello.lbpa
lb trust import my-root.json
lb trust repo list
lb trust repo add termux --url https://packages.termux.dev/apt/termux-main \
  --fingerprint CC72CF8BA7DBFA0182877D045A897D96E57CF20C --key termux.gpg
lb config set require_signature true
lb config set malware_scan true
```

## 12. 示例 manifest

```json
{
  "format": "LAYERBRIDGEPACKONANDROID",
  "formatVersion": 1,
  "minLbVersion": "0.1.0",
  "createdBy": "lb-pack/0.1.0",
  "createdAt": "2026-10-28T00:00:00Z",
  "package": {
    "name": "hello",
    "version": "2.12.1-1",
    "architecture": "aarch64",
    "description": "GNU hello"
  },
  "relations": {
    "depends": ["libc++"]
  },
  "payload": {
    "compression": "xz",
    "size": 123456,
    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  },
  "scripts": {
    "postinst": "control/postinst.sh"
  },
  "hooks": {
    "install": ["control/hooks/10-setup.sh"]
  },
  "env": {
    "HELLO_HOME": "/data/adb/lb/usr/share/hello"
  },
  "expose": ["bin/hello"],
  "gui": false,
  "installedSize": 42
}
```

## 13. 待定项

- 根证书轮换/多根并存的迁移流程（v1 只支持单根导入）。
- `x-deb-control` 是否保留全部未知 deb 字段（当前可选）。
- 是否支持 zstd 压缩（需引入依赖；当前仅 xz/gzip）。
- 是否支持增量包/差分更新（不在 v1）。
- 恶意检测规则集的维护与更新通道（随 lb 版本发布，暂不支持在线热更新）。
