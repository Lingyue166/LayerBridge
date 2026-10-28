package com.lingyue.layerbridge.expose

import com.lingyue.layerbridge.core.LbPaths

/**
 * 暴露策略。默认 STEALTH：命令只出现在前缀内（root-only），
 * 不写 /system/bin、不挂 overlay，普通应用无法发现或执行。
 */
enum class ExposeMode {
    STEALTH,
    SYSTEM,
}

object ExposeTargets {

    /** wrapper 的落点：前缀内的 bin 永远写（供 `lb shell` 用），系统级落点仅 SYSTEM 模式启用。 */
    fun of(mode: ExposeMode, command: String, moduleReady: Boolean): List<String> = buildList {
        if (mode == ExposeMode.SYSTEM) {
            add("${LbPaths.OVERLAY_UPPER}/$command")
            if (moduleReady) add("${LbPaths.MODULE_SYSTEM_BIN}/$command")
        }
        add("${LbPaths.SYSTEM_BIN}/$command")
    }
}
