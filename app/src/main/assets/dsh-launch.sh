#!/data/data/com.termux/files/usr/bin/sh
# =============================================================================
#  DSH 启动脚本  —— 由「DSH 启动器」App 通过 Termux RUN_COMMAND 调用
#
#  输出协议（App 会解析下面这些行，其它行忽略）：
#    DSH_STATE=check      开始检查
#    DSH_STATE=starting   正在启动 DSH
#    DSH_STATE=running    DSH 已就绪
#    DSH_STATE=down       启动失败
#    DSH_OPENED=1         本脚本已自行打开浏览器，App 不要再打开
#    DSH_URL=<url>        最终要打开的地址（带 token）
# =============================================================================

H="$HOME"
LOG_FILE="$H/dsh-web.log"
PORT="${DSH_PORT:-3080}"
BASE="http://127.0.0.1:$PORT"

is_up() {
  if command -v curl >/dev/null 2>&1; then
    curl -s -o /dev/null --max-time 2 "$BASE/" 2>/dev/null
  else
    # 没有 curl 时退化为探测端口（wget 对 401 返回非 0，所以只看连接）
    wget -q -S -O /dev/null --timeout=2 "$BASE/" 2>&1 | grep -q "HTTP/"
  fi
}

echo "DSH_STATE=check"

if ! is_up; then
  echo "DSH_STATE=starting"

  if [ -f "$H/start-dsh.sh" ]; then
    # 冷启动走现成的 start-dsh.sh（它内部会用 termux-open-url 打开浏览器）
    nohup sh "$H/start-dsh.sh" >/dev/null 2>&1 &
    echo "DSH_OPENED=1"
  elif [ -f "$H/dsh-supervisor.sh" ]; then
    termux-wake-lock >/dev/null 2>&1
    : > "$LOG_FILE"
    nohup sh "$H/dsh-supervisor.sh" >> "$LOG_FILE" 2>&1 &
  fi

  n=0
  while [ "$n" -lt 30 ]; do
    is_up && break
    n=$((n + 1))
    sleep 1
  done
fi

if is_up; then
  echo "DSH_STATE=running"
else
  echo "DSH_STATE=down"
fi

# token 每次 DSH 进程重启都会变，日志里最后一条就是当前有效的
TOKEN="$(grep -o 'token=[A-Za-z0-9_-]*' "$LOG_FILE" 2>/dev/null | tail -1 | cut -d= -f2)"

if [ -n "$TOKEN" ]; then
  echo "DSH_URL=$BASE/?token=$TOKEN"
else
  echo "DSH_URL=$BASE/"
fi
