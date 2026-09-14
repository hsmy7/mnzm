#!/usr/bin/env bash
# batch10-rng-watch.sh — Batch-10 B 组：RNG 跨线程警告观察协议执行器
#
# 用法：
#   bash scripts/batch10-rng-watch.sh <serial> <minutes> [outdir]
#   ADB=/path/to/adb bash scripts/batch10-rng-watch.sh ...
#
# 行为：
#   1. 清空 logcat 后开始全量落盘捕获（不丢轮转段）；
#   2. 运行 <minutes> 分钟（观察期内由测试者执行混合操作：存档/读档/重启/
#      战斗/秘境/血炼 + 前台后台循环重启——handover §2.6 观察口径）；
#   3. 结束后过滤判定标记并输出 PASS/FAIL 结论 + 证据文件路径。
#
# 判定标记（handover §2.6 / GameCoreBridge.cpp）：
#   RNG 通道跨线程进入      —— jniWarnRngOffEngineThread（WARN，不 abort）
#   P1-4 线程契约违规        —— jniRequireEngineThread（断言升级后首犯即崩标记）
#   P1-4 owner rebased      —— 紧急重启换线程重锚（合法路径信息项，不算违规）
#   FATAL EXCEPTION/SIGABRT —— 任何崩溃（一并捕获归因）
set -u

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
SERIAL="${1:?usage: $0 <serial> <minutes> [outdir]}"
MINUTES="${2:?usage: $0 <serial> <minutes> [outdir]}"
OUTDIR="${3:-./batch10-rng-evidence}"
mkdir -p "$OUTDIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
RAWLOG="$OUTDIR/logcat-$STAMP.txt"

echo "[rng-watch] device=$SERIAL duration=${MINUTES}min rawlog=$RAWLOG"
"$ADB" -s "$SERIAL" logcat -c

# --vnttime 无必要；-v time 足够归因。落盘全量，事后过滤（轮转不丢段）。
"$ADB" -s "$SERIAL" logcat -v time > "$RAWLOG" 2>&1 &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null' EXIT

sleep "$((MINUTES * 60))"

kill $LOGPID 2>/dev/null
wait $LOGPID 2>/dev/null
trap - EXIT

echo "=================== 判定 ==================="
FAIL=0
for pat in "RNG 通道跨线程进入" "P1-4 线程契约违规" "FATAL EXCEPTION" "SIGABRT"; do
  N=$(grep -c "$pat" "$RAWLOG" || true)
  echo "$pat : $N"
  [ "$N" -gt 0 ] && FAIL=1 && grep "$pat" "$RAWLOG" | head -5 | sed 's/^/    /'
done
REBASE=$(grep -c "P1-4 owner rebased" "$RAWLOG" || true)
echo "P1-4 owner rebased : $REBASE（合法重锚信息项）"

if [ "$FAIL" -eq 0 ]; then
  echo "VERDICT: PASS —— 观察期内零 RNG 跨线程警告 / 零契约违规 / 零崩溃"
else
  echo "VERDICT: FAIL —— 存在违规/崩溃标记，见上方样本与 $RAWLOG（§2.6：不升级断言，登记漏网调用面）"
fi
