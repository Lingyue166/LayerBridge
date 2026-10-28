package com.lingyue.layerbridge.core

object LbPaths {
    const val BASE = "/data/local/lb"
    const val PREFIX = "$BASE/usr"
    const val SYSTEM_BIN = "$BASE/system/bin"
    const val OVERLAY_UPPER = "$BASE/system/upper"
    const val DB_DIR = "$BASE/db"
    const val CACHE = "$BASE/cache"
    const val ENV_FILE = "$BASE/env.sh"
    const val HOME = "$BASE/home"
    const val TMP = "$PREFIX/tmp"
    const val TRUST_DIR = "$BASE/trust"
    const val MODULE_DIR = "/data/adb/modules/lb"
    const val MODULE_SYSTEM_BIN = "$MODULE_DIR/system/bin"

    val bootstrapDirs = listOf(
        BASE,
        PREFIX,
        "$PREFIX/bin",
        "$PREFIX/sbin",
        "$PREFIX/lib",
        "$PREFIX/share",
        "$PREFIX/etc",
        "$PREFIX/var",
        "$PREFIX/tmp",
        SYSTEM_BIN,
        OVERLAY_UPPER,
        "$BASE/system/work",
        DB_DIR,
        "$DB_DIR/info",
        "$BASE/hooks",
        TRUST_DIR,
        CACHE,
        HOME,
    )
}
