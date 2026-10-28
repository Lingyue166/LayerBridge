# ADR-0005：允许 Termux 仓库签名（OpenPGP）作为授权路径

- 状态：Proposed
- 日期：2026-10-28
- 关联设计：[lb 设计文档](../../00-design/lb-architecture.md)
- 格式规范：[LBPA v1](../../00-design/lbpa-format.md)
- 补充：[ADR-0004](0004-signing-and-scan.md)

## 背景

- ADR-0004 要求默认只安装“授权签名包”，但 Termux 官方仓库不对单个 deb 签名，只对 apt 索引签名。
- 用户明确要求“允许 TERMUX 包的签名”，使 Termux 官方包无需重签即可安装。
- 需要保持“来源可验证”而非无条件信任。

## 决策

1. 增加第二条授权路径：**仓库签名**（apt `Release.gpg` / `InRelease`，OpenPGP）。
2. 默认受信 Termux 官方仓库 `termux-main`，固定签名指纹 `CC72CF8BA7DBFA0182877D045A897D96E57CF20C`；其他仓库必须显式 `lb trust repo add` 并确认指纹。
3. 验证链：OpenPGP 验 `Release.gpg` → `Release` 的 `SHA256` 验 `Packages` → `Packages` 的 `SHA256` 验 deb；检查 `Date` 新鲜度（默认 90 天）。
4. 授权判定顺序：包签名有效 → 通过；否则仓库受信且三层哈希通过 → 通过（`signer=repo:<id>`）；两者都没有才按策略拒绝。
5. 公钥轮换必须人工确认新指纹，禁止静默更新；`Release`/`Packages` 缓存可删除重取。
6. OpenPGP 验签使用 BouncyCastle `bcpg`/`bcprov`，不依赖系统 gpg。
7. 包签名（CA 证书链）优先级高于仓库签名；仓库签名只保护索引与哈希链，不证明单包构建者身份。

## 后果

- Termux 官方 deb 默认可以安装，不再需要重签或开发者模式；其他未签名仓库仍需授权或逐包签名。
- 增加 BouncyCastle 依赖与 OpenPGP 解析面；需测试签名、过期、指纹不匹配、索引篡改。
- 信任边界：Termux 仓库密钥被攻破会影响所有该仓库包；用户可移除该受信仓库。
- 时间校验依赖设备时钟；时钟严重错误时可能误判 `Release` 过期。
- 需要在信任页展示仓库指纹与启停状态，并提供撤销。

## 备选方案（未采用）

| 方案 | 原因 |
|---|---|
| 要求 Termux 包逐个重签 | 用户明确要允许 Termux 签名，且重签无法覆盖官方更新 |
| 只信任 HTTPS，不验 OpenPGP | HTTPS 只保护传输，无法防仓库/镜像篡改 |
| TOFU 首次索引哈希固定 | 索引每日更新，无法维持 |
| 无条件信任 Termux 仓库 URL | 无法防镜像劫持与密钥轮换攻击 |
