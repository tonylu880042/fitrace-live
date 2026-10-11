#!/bin/sh
# 以 release 版啟動伺服器，當掉或被 kill 就自動重啟；房間與報名由 fitrace.db 還原。
# 換新版：cargo build --release 後 kill 伺服器程序即可。要真正停止請結束這支腳本。
# ponytail: 迴圈重啟，不會開機自啟；需要時再改成 launchd
cd "$(dirname "$0")" || exit 1
cargo build --release --bin fitrace_live || exit 1
BIN="$(cargo metadata --format-version 1 --no-deps | sed -n 's/.*"target_directory":"\([^"]*\)".*/\1/p')/release/fitrace_live"
while :; do
  "$BIN"
  echo "fitrace_live 結束（代碼 $?），1 秒後重啟" >&2
  sleep 1
done
