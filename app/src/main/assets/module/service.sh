#!/system/bin/sh
# 开机执行已注册钩子；失败只记录。
BASE=/data/local/lb
mkdir -p "$BASE/logs"
for hook in "$BASE"/hooks/*.sh; do
  [ -f "$hook" ] || continue
  echo "[$(date)] run $hook" >> "$BASE/logs/hooks.log"
  sh "$hook" >> "$BASE/logs/hooks.log" 2>&1
done
