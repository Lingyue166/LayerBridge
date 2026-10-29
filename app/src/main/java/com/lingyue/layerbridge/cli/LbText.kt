package com.lingyue.layerbridge.cli

import java.util.Locale

/** CLI 文案键。 */
internal enum class T {
    HELP,
    USAGE_INSTALL,
    USAGE_REMOVE,
    USAGE_INFO,
    USAGE_VERIFY,
    USAGE_INSPECT,
    USAGE_TRUST,
    USAGE_EXPOSE,
    USAGE_GENERIC,
    ERR_ROOT,
    ERR_NOT_FOUND,
    ERR_UNKNOWN_TYPE,
    ERR_BAD_NAME,
    ERR_PREFIX_INIT,
    ERR_INSTALL_FAILED,
    ERR_UNINSTALL_FAILED,
    ERR_PARSE,
    ERR_TRUST_INVALID,
    ERR_TRUST_WRITE,
    ERR_TRUST_DELETE,
    ERR_NO_TRUST_ROOT,
    SIGN_VALID,
    SIGN_VALIDITY,
    SIGN_INVALID,
    SIGN_UNSIGNED_DEB,
    SIGN_UNSIGNED_LBPA,
    TRUST_EMPTY,
    TRUST_SUBJECT,
    TRUST_KEYID,
    TRUST_VALID,
    TRUST_CA,
    TRUST_IMPORTED,
    TRUST_DELETED,
    PLAN_HEADER,
    PLAN_ITEM,
    MISSING_HEADER,
    CONFLICT_HEADER,
    CYCLE,
    CONFIRM,
    CANCELLED,
    INSTALLING,
    INSTALL_DONE,
    INSTALL_FAILED_ITEM,
    REMOVED,
    UNKNOWN_COMMAND,
    PENDING,
    EXPOSE_ON,
    EXPOSE_OFF,
    NOT_INSTALLED,
    NO_PACKAGES,
    UPGRADE_SINGLE,
    DIR_EMPTY,
    CONTROL_MISSING,
    OPERATION_FAILED,
    INTERACTIVE_HINT,
    UNKNOWN_ARG,
    OVERLAY_MOUNTED,
    OVERLAY_NONE,
    SIGNED_YES,
    SIGNED_NO,
    USAGE_TRUST_IMPORT,
}

internal object LbText {

    private val zh: Map<T, String> = mapOf(
        T.HELP to """
            lb - LayerBridge 包管理器

            用法：
              lb                          同 lb help
              lb cli                      进入交互模式（子命令与直接调用一致）
              lb env                      打印环境变量
              lb list                     列出已安装包
              lb info <包名>              查看已安装包的元数据与关系
              lb doctor                   自检（root、前缀、数据库、信任根）
              lb shell                    进入带环境的 shell
              lb exec <命令> [参数...]    以 root 用前缀环境执行一条命令
              lb install [选项] <文件|目录> 安装本地 .deb / .lbpa；目录会解析依赖并排序
                  --allow-unsigned        允许无签名包（仍需结构校验）
                  --skip-scan             跳过维护脚本静态检测
                  --yes / -y              目录安装时跳过确认
              lb upgrade [选项] <文件>    升级/降级已安装包到任意版本
              lb remove [--force] <包名>  卸载；默认保留数据/配置
                  --force / -f            连同数据、残留一起清除（同 lb purge）
              lb purge <包名>             强制卸载（= lb remove --force）
              lb expose-root [--remove]   root shell 软链（默认开启，--remove 关闭）
              lb verify <包>              校验签名（deb / LBPA）
              lb inspect <包>             查看格式、元数据、脚本、关系
              lb trust show               显示信任根
              lb trust import <root.json> 导入信任根
              lb trust remove             删除信任根
        """.trimIndent(),
        T.USAGE_INSTALL to "用法：lb install [选项] <文件|目录>",
        T.USAGE_REMOVE to "用法：lb remove [--force] <包名>",
        T.USAGE_INFO to "用法：lb info <包名>",
        T.USAGE_VERIFY to "用法：lb verify <包>",
        T.USAGE_INSPECT to "用法：lb inspect <包>",
        T.USAGE_TRUST to "用法：lb trust show | import <root.json> | remove",
        T.USAGE_EXPOSE to "用法：lb expose-root [--remove]",
        T.USAGE_GENERIC to "用法：%s",
        T.ERR_ROOT to "需要 root：请使用 su 运行 lb",
        T.ERR_NOT_FOUND to "文件不存在：%s",
        T.ERR_UNKNOWN_TYPE to "无法识别包格式：%s",
        T.ERR_BAD_NAME to "非法包名：%s",
        T.ERR_PREFIX_INIT to "初始化前缀失败：%s",
        T.ERR_INSTALL_FAILED to "安装失败：%s",
        T.ERR_UNINSTALL_FAILED to "卸载失败：%s",
        T.ERR_PARSE to "解析失败：%s",
        T.ERR_TRUST_INVALID to "不是可用的信任根：%s",
        T.ERR_TRUST_WRITE to "写入失败：%s",
        T.ERR_TRUST_DELETE to "删除失败：%s",
        T.ERR_NO_TRUST_ROOT to "未配置信任根：%s",
        T.SIGN_VALID to "签名有效：%s",
        T.SIGN_VALIDITY to "有效期：%s → %s",
        T.SIGN_INVALID to "签名无效：%s",
        T.SIGN_UNSIGNED_DEB to "未签名：没有 %s",
        T.SIGN_UNSIGNED_LBPA to "未签名：LBPA 无签名 footer",
        T.TRUST_EMPTY to "未配置信任根：%s",
        T.TRUST_SUBJECT to "Subject: %s",
        T.TRUST_KEYID to "KeyId: %s",
        T.TRUST_VALID to "有效期：%s → %s",
        T.TRUST_CA to "CA: %s  用途: %s",
        T.TRUST_IMPORTED to "已导入信任根：%s",
        T.TRUST_DELETED to "已删除信任根",
        T.PLAN_HEADER to "安装计划（%d 个包）：",
        T.PLAN_ITEM to "  %1\$d. %2\$s %3\$s (%4\$s)",
        T.MISSING_HEADER to "缺失依赖：",
        T.CONFLICT_HEADER to "存在冲突：",
        T.CYCLE to "依赖存在环：%s",
        T.CONFIRM to "继续安装？",
        T.CANCELLED to "已取消",
        T.INSTALLING to "[lb] 安装 %s %s",
        T.INSTALL_DONE to "[lb] 安装完成：%s %s",
        T.INSTALL_FAILED_ITEM to "[lb] 安装失败：%s",
        T.REMOVED to "[lb] 已卸载 %s",
        T.UNKNOWN_COMMAND to "未知命令：%s",
        T.PENDING to "lb %s：暂未实现。",
        T.EXPOSE_ON to "[lb] root shell 软链已启用（默认行为），之后安装/卸载自动维护",
        T.EXPOSE_OFF to "[lb] root shell 软链已关闭，已清理现有链接",
        T.NOT_INSTALLED to "未安装：%s",
        T.NO_PACKAGES to "尚无已安装包",
        T.UPGRADE_SINGLE to "upgrade 只接受单个包文件",
        T.DIR_EMPTY to "目录内没有 .deb / .lbpa：%s",
        T.CONTROL_MISSING to "control 缺少 Package",
        T.OPERATION_FAILED to "操作失败：%s",
        T.INTERACTIVE_HINT to "lb 交互模式：直接输入子命令（install/remove/list/info/doctor/env/shell），exit 退出",
        T.UNKNOWN_ARG to "未知参数：%s",
        T.OVERLAY_MOUNTED to "已挂载（可被检测）",
        T.OVERLAY_NONE to "未挂载",
        T.SIGNED_YES to "有",
        T.SIGNED_NO to "无",
        T.USAGE_TRUST_IMPORT to "用法：lb trust import <root.json>",
    )

    private val en: Map<T, String> = mapOf(
        T.HELP to """
            lb - LayerBridge package manager

            Usage:
              lb                          same as lb help
              lb cli                      interactive mode
              lb env                      print environment
              lb list                     list installed packages
              lb info <name>              show installed package metadata
              lb doctor                   self check (root, prefix, database, trust)
              lb shell                    shell with prefix environment
              lb exec <cmd> [args...]     run one command with prefix environment
              lb install [opts] <file|dir> install local .deb / .lbpa; directory resolves deps
                  --allow-unsigned        allow unsigned packages (structure checks still run)
                  --skip-scan             skip maintainer script scan
                  --yes / -y              skip confirmation for directory install
              lb upgrade [opts] <file>    upgrade or downgrade an installed package
              lb remove [--force] <name>  uninstall; keeps data by default
                  --force / -f            remove data too (same as lb purge)
              lb purge <name>             force uninstall (= lb remove --force)
              lb expose-root [--remove]   root shell symlinks (on by default)
              lb verify <package>         verify signature (deb / LBPA)
              lb inspect <package>        show format, metadata, scripts, relations
              lb trust show               show trust root
              lb trust import <root.json> import trust root
              lb trust remove             remove trust root
        """.trimIndent(),
        T.USAGE_INSTALL to "usage: lb install [options] <file|directory>",
        T.USAGE_REMOVE to "usage: lb remove [--force] <name>",
        T.USAGE_INFO to "usage: lb info <name>",
        T.USAGE_VERIFY to "usage: lb verify <package>",
        T.USAGE_INSPECT to "usage: lb inspect <package>",
        T.USAGE_TRUST to "usage: lb trust show | import <root.json> | remove",
        T.USAGE_EXPOSE to "usage: lb expose-root [--remove]",
        T.USAGE_GENERIC to "usage: %s",
        T.ERR_ROOT to "root required: run lb with su",
        T.ERR_NOT_FOUND to "file not found: %s",
        T.ERR_UNKNOWN_TYPE to "unrecognized package format: %s",
        T.ERR_BAD_NAME to "invalid package name: %s",
        T.ERR_PREFIX_INIT to "prefix init failed: %s",
        T.ERR_INSTALL_FAILED to "install failed: %s",
        T.ERR_UNINSTALL_FAILED to "uninstall failed: %s",
        T.ERR_PARSE to "parse failed: %s",
        T.ERR_TRUST_INVALID to "not a usable trust root: %s",
        T.ERR_TRUST_WRITE to "write failed: %s",
        T.ERR_TRUST_DELETE to "delete failed: %s",
        T.ERR_NO_TRUST_ROOT to "trust root not configured: %s",
        T.SIGN_VALID to "signature valid: %s",
        T.SIGN_VALIDITY to "valid: %s -> %s",
        T.SIGN_INVALID to "signature invalid: %s",
        T.SIGN_UNSIGNED_DEB to "unsigned: missing %s",
        T.SIGN_UNSIGNED_LBPA to "unsigned: LBPA has no signature footer",
        T.TRUST_EMPTY to "trust root not configured: %s",
        T.TRUST_SUBJECT to "Subject: %s",
        T.TRUST_KEYID to "KeyId: %s",
        T.TRUST_VALID to "Valid: %s -> %s",
        T.TRUST_CA to "CA: %s  Usages: %s",
        T.TRUST_IMPORTED to "trust root imported: %s",
        T.TRUST_DELETED to "trust root removed",
        T.PLAN_HEADER to "install plan (%d packages):",
        T.PLAN_ITEM to "  %1\$d. %2\$s %3\$s (%4\$s)",
        T.MISSING_HEADER to "missing dependencies:",
        T.CONFLICT_HEADER to "conflicts:",
        T.CYCLE to "dependency cycle: %s",
        T.CONFIRM to "continue?",
        T.CANCELLED to "cancelled",
        T.INSTALLING to "[lb] installing %s %s",
        T.INSTALL_DONE to "[lb] installed: %s %s",
        T.INSTALL_FAILED_ITEM to "[lb] install failed: %s",
        T.REMOVED to "[lb] removed %s",
        T.UNKNOWN_COMMAND to "unknown command: %s",
        T.PENDING to "lb %s: not implemented yet",
        T.EXPOSE_ON to "[lb] root shell symlinks enabled; maintained on install/remove",
        T.EXPOSE_OFF to "[lb] root shell symlinks disabled; existing links removed",
        T.NOT_INSTALLED to "not installed: %s",
        T.NO_PACKAGES to "no packages installed",
        T.UPGRADE_SINGLE to "upgrade accepts a single package file",
        T.DIR_EMPTY to "no .deb / .lbpa in directory: %s",
        T.CONTROL_MISSING to "control is missing Package",
        T.OPERATION_FAILED to "operation failed: %s",
        T.INTERACTIVE_HINT to "lb interactive mode: type a subcommand (install/remove/list/info/doctor/env/shell), exit to quit",
        T.UNKNOWN_ARG to "unknown argument: %s",
        T.OVERLAY_MOUNTED to "mounted (detectable)",
        T.OVERLAY_NONE to "not mounted",
        T.SIGNED_YES to "yes",
        T.SIGNED_NO to "no",
        T.USAGE_TRUST_IMPORT to "usage: lb trust import <root.json>",
    )

    fun table(language: String): Map<T, String> = if (language.startsWith("zh")) zh else en

    fun get(language: String, key: T, vararg args: Any): String {
        val template = table(language)[key] ?: en[key] ?: key.name
        return if (args.isEmpty()) template else String.format(template, *args)
    }

    fun current(key: T, vararg args: Any): String = get(Locale.getDefault().language, key, *args)
}
