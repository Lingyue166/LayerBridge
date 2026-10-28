# LayerBridge 代码规则

总则：**简单优先，能读 > 能跑 > 能扩展**。下面每条都要落到可检查的行为上，不停留在口号。

## 规则与落地判据

| 规则 | 在本项目的判据 |
|---|---|
| KISS | 默认实现最小可用；新增参数/抽象必须对应一个真实调用点，不写"预留"开关。 |
| DRY | 常量只有一处来源：路径走 `LbPaths`，命令名/包装逻辑走 `Wrapper`，包名字符集走 `PathGuard`。同一逻辑第三次复制前必须抽取。 |
| YAGNI | 未确认的需求不实现：LBPA 未使用的字段不提前支持，CLI 只做已验证的命令。 |
| Fail fast | 非法包名、路径穿越、签名无效、架构不支持——立即 `error()` 中止，不降级、不"尽力继续"；开发者开关也不放宽结构校验。 |
| 最小惊讶 | `lb` 包装命令行为与系统命令一致；卸载只影响本包；错误信息说明"哪一步失败"。 |
| 无副作用 | 纯函数（`Wrapper.providers`、`PathGuard`、`DependencyResolver`）不碰 IO/Shell；IO 只出现在 `Shell`、`LinuxInstaller`、`StatusDb`、`Lbpa`/`DebFile` 解析层。 |
| 单一抽象层 | `LinuxInstaller` 不解析 tar 头；`DebFile`/`LbpaFile` 不执行 shell；`ui` 不直接拼 shell 命令。 |
| Demeter | 不做 `a.b.c.d`；跨层数据由所属对象提供方法（如 `statusDb.readFileList(name)`）。 |
| 童子军 | 改到的文件顺手修同类小问题；但不与主改动混在一个提交里。 |
| SOLID | 重点是 SRP 与 DIP：`LinuxInstaller` 按"签名/解包/脚本/状态"拆分；依赖通过构造器注入（`StatusDb`、`TrustStore` 已如此）。 |

## 提交与验证

- 一个提交一个主题；提交信息写"为什么"。
- 改动前后跑 `:app:testDebugUnitTest --rerun`，纯逻辑必须有测试；UI/Shell 行为靠真机或脚本补证。
- 新增状态字段必须向后兼容旧 TSV（缺列按默认值解析）。
