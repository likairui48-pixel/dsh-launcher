#!/data/data/com.termux/files/usr/bin/sh
# =============================================================================
#  DSH 文件桥 —— 「DSH 启动器」App 通过 Termux RUN_COMMAND 调用
#
#  调用形式： sh -c <本脚本> dsh-fs <base64(payload)>
#  参数用 base64 整体打包，避免引号 / 空格 / 换行在传参链路上被吃掉。
#
#  payload 行格式（KEY=VALUE，一行一个）：
#     OP    操作名
#     P     主路径
#     P2    次路径（重命名目标 / 导出目标 …）
#     Q     搜索关键词
#     B     base64 数据（写入用）
#     A     1 = 追加写，0 = 覆盖写
#     OFF   读取偏移（字节）
#     MAX   单次读取上限
#
#  回传格式（App 逐行解析）：
#     RC=0|1         是否成功
#     MSG=...        失败原因
#     B64=...        返回数据（base64 单行）
#     SIZE=...       文件字节数
#     BIN=1|0        是否二进制
#     DEST=...       导出/同步后的绝对路径
# =============================================================================

LC_ALL=C
export LC_ALL

H="${HOME:-/data/data/com.termux/files/home}"
HS="$H/"
SHARED_DEFAULT="/sdcard/Download"
WS_DEFAULT="$H/usr/DSH"
MAX_LINES=800

# ---------------------------------------------------------------- 参数解码
ENC="$1"
PAYLOAD="$(printf '%s' "$ENC" | base64 -d 2>/dev/null)"

OP=""; P=""; P2=""; Q=""; B=""; A="0"; OFF="0"; MAX="262144"

while IFS= read -r __line; do
  case "$__line" in
    OP=*)  OP="${__line#OP=}" ;;
    P=*)   P="${__line#P=}" ;;
    P2=*)  P2="${__line#P2=}" ;;
    Q=*)   Q="${__line#Q=}" ;;
    B=*)   B="${__line#B=}" ;;
    A=*)   A="${__line#A=}" ;;
    OFF=*) OFF="${__line#OFF=}" ;;
    MAX=*) MAX="${__line#MAX=}" ;;
  esac
done <<__DSH_PAYLOAD_END__
$PAYLOAD
__DSH_PAYLOAD_END__

# 数字字段兜底，防止 App 传空导致算术报错
case "$OFF" in ''|*[!0-9]*) OFF=0 ;; esac
case "$MAX" in ''|*[!0-9]*) MAX=262144 ;; esac

# ---------------------------------------------------------------- 输出助手
fail() {
  printf 'RC=1\n'
  printf 'MSG=%s\n' "$(printf '%s' "$1" | tr '\n\r\t' '   ')"
  exit 0
}

done_ok() {
  printf 'RC=0\n'
  exit 0
}

# ---------------------------------------------------------------- 安全护栏
is_protected() {
  case "$1" in
    ""|"/"|"/data"|"/data/"|"/data/data"|"/data/data/"|"/sdcard"|"/sdcard/" \
    |"/storage"|"/storage/"|"/storage/emulated"|"/storage/emulated/" \
    |"/storage/emulated/0"|"/storage/emulated/0/"|"$H"|"$HS")
      return 0 ;;
  esac
  return 1
}

is_writable_area() {
  case "$1" in
    "$H"/*|/sdcard/*|/storage/emulated/0/*) return 0 ;;
  esac
  return 1
}

is_shared_area() {
  case "$1" in
    /sdcard/*|/storage/emulated/0/*) return 0 ;;
  esac
  return 1
}

# ---------------------------------------------------------------- 操作分发
case "$OP" in

  probe)
    printf 'RC=0\n'
    printf 'HOME=%s\n' "$H"
    printf 'PREFIX=%s\n' "${PREFIX:-/data/data/com.termux/files/usr}"
    printf 'SHARED_ROOT=%s\n' "$SHARED_DEFAULT"
    if [ -d "$SHARED_DEFAULT" ] && [ -w "$SHARED_DEFAULT" ]; then
      printf 'SHARED_OK=1\n'
    else
      printf 'SHARED_OK=0\n'
    fi
    if [ -d "$WS_DEFAULT" ]; then
      printf 'WS_EXISTS=1\n'
    else
      printf 'WS_EXISTS=0\n'
    fi
    printf 'WS_DEFAULT=%s\n' "$WS_DEFAULT"
    if command -v base64 >/dev/null 2>&1 && command -v find >/dev/null 2>&1; then
      printf 'TOOLS=1\n'
    else
      printf 'TOOLS=0\n'
    fi
    exit 0
    ;;

  list)
    [ -n "$P" ] || fail "缺少路径"
    [ -d "$P" ] || fail "目录不存在：$P"
    DATA="$(find "$P" -mindepth 1 -maxdepth 1 \
        -printf '%y\t%Y\t%s\t%T@\t%f\t%l\n' 2>/dev/null | head -n "$MAX_LINES")"
    printf 'RC=0\n'
    printf 'B64='
    printf '%s\n' "$DATA" | base64 | tr -d '\n'
    printf '\n'
    exit 0
    ;;

  search)
    [ -n "$Q" ] || fail "缺少关键词"
    [ -d "$P" ] || fail "目录不存在：$P"
    DATA="$(find "$P" -iname "*$Q*" \
        -printf '%y\t%Y\t%s\t%T@\t%f\t%l\t%p\n' 2>/dev/null | head -n 300)"
    printf 'RC=0\n'
    printf 'B64='
    printf '%s\n' "$DATA" | base64 | tr -d '\n'
    printf '\n'
    exit 0
    ;;

  read)
    [ -n "$P" ] || fail "缺少路径"
    [ -f "$P" ] || fail "文件不存在：$P"
    TOT="$(wc -c < "$P" 2>/dev/null | tr -d ' ')"
    case "$TOT" in ''|*[!0-9]*) TOT=0 ;; esac
    HEAD_BYTES="$(head -c 8192 "$P" 2>/dev/null | wc -c | tr -d ' ')"
    NO_NUL="$(head -c 8192 "$P" 2>/dev/null | tr -d '\000' | wc -c | tr -d ' ')"
    BIN=0
    [ "$HEAD_BYTES" != "$NO_NUL" ] && BIN=1
    START=$((OFF + 1))
    printf 'RC=0\n'
    printf 'SIZE=%s\n' "$TOT"
    printf 'BIN=%s\n' "$BIN"
    printf 'OFF=%s\n' "$OFF"
    printf 'B64='
    tail -c "+$START" "$P" 2>/dev/null | head -c "$MAX" | base64 | tr -d '\n'
    printf '\n'
    exit 0
    ;;

  write)
    [ -n "$P" ] || fail "缺少路径"
    is_protected "$P" && fail "受保护路径，拒绝写入"
    is_writable_area "$P" || fail "不在允许写入的范围：$P"
    DIR="$(dirname "$P")"
    [ -d "$DIR" ] || fail "上级目录不存在：$DIR"
    TMP="$P.dsh-tmp-$$"
    if ! printf '%s' "$B" | base64 -d > "$TMP" 2>/dev/null; then
      rm -f "$TMP"
      fail "数据解码失败"
    fi
    if [ "$A" = "1" ]; then
      cat "$TMP" >> "$P" 2>/dev/null
    else
      cat "$TMP" > "$P" 2>/dev/null
    fi
    RC=$?
    rm -f "$TMP"
    [ "$RC" = "0" ] || fail "写入失败：$P"
    SZ="$(wc -c < "$P" 2>/dev/null | tr -d ' ')"
    printf 'RC=0\n'
    printf 'SIZE=%s\n' "${SZ:-0}"
    exit 0
    ;;

  mkdir)
    [ -n "$P" ] || fail "缺少路径"
    is_protected "$P" && fail "受保护路径"
    is_writable_area "$P" || fail "不在允许范围"
    [ -e "$P" ] && fail "已经存在同名文件"
    mkdir -p -- "$P" 2>/dev/null || fail "创建目录失败"
    printf 'RC=0\nDEST=%s\n' "$P"
    exit 0
    ;;

  touch)
    [ -n "$P" ] || fail "缺少路径"
    is_protected "$P" && fail "受保护路径"
    is_writable_area "$P" || fail "不在允许范围"
    [ -e "$P" ] && fail "已经存在同名文件"
    ( umask 077; : > "$P" ) 2>/dev/null || fail "创建文件失败"
    printf 'RC=0\nDEST=%s\n' "$P"
    exit 0
    ;;

  rename)
    [ -n "$P" ] && [ -n "$P2" ] || fail "缺少路径"
    is_protected "$P" && fail "受保护路径，拒绝重命名"
    is_writable_area "$P" || fail "不在允许范围"
    is_writable_area "$P2" || fail "目标不在允许范围"
    [ -e "$P" ] || fail "源不存在：$P"
    [ -e "$P2" ] && fail "目标已存在：$P2"
    mv -- "$P" "$P2" 2>/dev/null || fail "重命名失败"
    printf 'RC=0\nDEST=%s\n' "$P2"
    exit 0
    ;;

  delete)
    [ -n "$P" ] || fail "缺少路径"
    is_protected "$P" && fail "受保护路径，拒绝删除"
    is_writable_area "$P" || fail "不在允许删除的范围"
    [ -e "$P" ] || fail "路径不存在：$P"
    rm -rf -- "$P" 2>/dev/null || fail "删除失败"
    printf 'RC=0\n'
    exit 0
    ;;

  copy)
    [ -n "$P" ] && [ -n "$P2" ] || fail "缺少路径"
    is_writable_area "$P2" || fail "目标不在允许范围"
    [ -e "$P" ] || fail "源不存在：$P"
    [ -e "$P2" ] && fail "目标已存在：$P2"
    cp -R -- "$P" "$P2" 2>/dev/null || fail "复制失败"
    printf 'RC=0\nDEST=%s\n' "$P2"
    exit 0
    ;;

  copyout)
    [ -n "$P" ] && [ -n "$P2" ] || fail "缺少路径"
    [ -e "$P" ] || fail "源不存在：$P"
    is_shared_area "$P2" || fail "导出目标必须在共享存储"
    rm -rf -- "$P2" 2>/dev/null
    mkdir -p -- "$(dirname "$P2")" 2>/dev/null || fail "无法创建导出目录"
    cp -R -- "$P" "$P2" 2>/dev/null || fail "导出失败（可能是共享存储未授权）"
    printf 'RC=0\nDEST=%s\n' "$P2"
    exit 0
    ;;

  copyin)
    [ -n "$P" ] && [ -n "$P2" ] || fail "缺少路径"
    [ -e "$P" ] || fail "共享存储里没有：$P"
    is_protected "$P2" && fail "受保护路径"
    is_writable_area "$P2" || fail "写入目标不在允许范围"
    mkdir -p -- "$(dirname "$P2")" 2>/dev/null || fail "无法创建上级目录"
    rm -rf -- "$P2" 2>/dev/null
    cp -R -- "$P" "$P2" 2>/dev/null || fail "同步失败"
    printf 'RC=0\nDEST=%s\n' "$P2"
    exit 0
    ;;

  du)
    [ -n "$P" ] || fail "缺少路径"
    [ -e "$P" ] || fail "路径不存在"
    SZ="$(du -sk -- "$P" 2>/dev/null | awk '{print $1}')"
    printf 'RC=0\nSIZE=%s\n' "$((${SZ:-0} * 1024))"
    exit 0
    ;;

  *)
    fail "未知操作：${OP:-空}"
    ;;
esac
