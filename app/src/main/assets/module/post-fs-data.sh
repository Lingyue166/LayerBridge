#!/system/bin/sh
# LayerBridge 模块：准备目录。默认隐藏模式，不触碰系统分区。
BASE=/data/adb/lb
# 旧版本数据迁移（一次性）
if [ ! -d "$BASE" ] && [ -d /data/local/lb ]; then mv /data/local/lb "$BASE" 2>/dev/null; fi
MODE=stealth
[ -f "$BASE/config/expose-system" ] && MODE=system

mkdir -p "$BASE/usr/bin" "$BASE/usr/sbin" "$BASE/usr/lib" "$BASE/usr/share" \
  "$BASE/usr/etc" "$BASE/usr/var" "$BASE/usr/tmp" "$BASE/system/bin" \
  "$BASE/system/upper" "$BASE/system/work" "$BASE/db/info" "$BASE/db/transactions" \
  "$BASE/db/scripts" "$BASE/db/data" "$BASE/hooks" "$BASE/cache" "$BASE/home" \
  "$BASE/logs" "$BASE/trust" "$BASE/config"

chmod 755 "$BASE/usr" "$BASE/system/bin" 2>/dev/null
chmod 700 "$BASE/db" "$BASE/trust" "$BASE/config" 2>/dev/null
if [ "$MODE" = "system" ]; then
  chmod 755 "$BASE" 2>/dev/null
else
  # 隐藏模式：整棵前缀只允许 root 进入，普通应用连目录都遍历不了
  chmod 700 "$BASE" 2>/dev/null
fi
log -t lb "layerbridge mode=$MODE"

if [ "$MODE" = "system" ]; then
  # 可选：把 env.sh 注入 bind mount 的副本，避免覆盖系统文件。
  if [ -f /system/etc/mkshrc ]; then
    [ -f "$BASE/mkshrc.orig" ] || cp /system/etc/mkshrc "$BASE/mkshrc.orig" 2>/dev/null
    if [ -f "$BASE/mkshrc.orig" ]; then
      cp "$BASE/mkshrc.orig" "$BASE/mkshrc.new" 2>/dev/null
      grep -q "$BASE/env.sh" "$BASE/mkshrc.new" 2>/dev/null || echo ". $BASE/env.sh" >> "$BASE/mkshrc.new"
      chmod 644 "$BASE/mkshrc.new" 2>/dev/null
      mount --bind "$BASE/mkshrc.new" /system/etc/mkshrc 2>/dev/null
    fi
  fi

  # 可选：overlay 把 /system/bin 的上层指向可写目录（会产生 mount 痕迹）
  if ! grep -q " $BASE/system/upper " /proc/mounts 2>/dev/null; then
    mount -t overlay overlay \
      -o "lowerdir=/system/bin,upperdir=$BASE/system/upper,workdir=$BASE/system/work" \
      /system/bin 2>/dev/null
    if [ $? -eq 0 ]; then
      log -t lb "overlay mounted on /system/bin"
    else
      log -t lb "overlay mount failed"
    fi
  fi
fi
