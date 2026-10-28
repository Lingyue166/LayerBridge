#!/system/bin/sh
# LayerBridge 模块：准备目录与 overlay（尽力而为，失败不阻塞开机）。
BASE=/data/local/lb

mkdir -p "$BASE/usr/bin" "$BASE/usr/sbin" "$BASE/usr/lib" "$BASE/usr/share" \
  "$BASE/usr/etc" "$BASE/usr/var" "$BASE/usr/tmp" "$BASE/system/bin" \
  "$BASE/system/upper" "$BASE/system/work" "$BASE/db/info" "$BASE/hooks" \
  "$BASE/cache" "$BASE/home" "$BASE/logs" "$BASE/trust"
chmod 755 "$BASE" "$BASE/usr" "$BASE/system" "$BASE/system/bin" 2>/dev/null

# 备份原始 mkshrc，并把 env.sh 注入到 bind mount 的副本，避免覆盖系统文件。
if [ -f /system/etc/mkshrc ]; then
  [ -f "$BASE/mkshrc.orig" ] || cp /system/etc/mkshrc "$BASE/mkshrc.orig" 2>/dev/null
  if [ -f "$BASE/mkshrc.orig" ]; then
    cp "$BASE/mkshrc.orig" "$BASE/mkshrc.new" 2>/dev/null
    grep -q "/data/local/lb/env.sh" "$BASE/mkshrc.new" 2>/dev/null ||       echo '. /data/local/lb/env.sh' >> "$BASE/mkshrc.new"
    chmod 644 "$BASE/mkshrc.new" 2>/dev/null
    mount --bind "$BASE/mkshrc.new" /system/etc/mkshrc 2>/dev/null
  fi
fi

# overlay：把 /system/bin 的上层指向可写目录，热装命令即时可见。
# 失败时回退到 mkshrc + lb shell。
if ! grep -q " $BASE/system/upper " /proc/mounts 2>/dev/null; then
  mount -t overlay overlay \
    -o "lowerdir=/system/bin,upperdir=$BASE/system/upper,workdir=$BASE/system/work" \
    /system/bin 2>/dev/null
  if [ $? -eq 0 ]; then
    log -t lb "overlay mounted on /system/bin"
  else
    log -t lb "overlay mount failed; fallback to env.sh"
  fi
fi
