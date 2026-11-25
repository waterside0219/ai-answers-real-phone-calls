#!/bin/bash
# 电脑这头一直守着：手机每接起一通，就起一次桥。停：Ctrl-C
cd "$(dirname "$0")"
adb forward tcp:8195 tcp:8195 >/dev/null
seen=$(adb shell grep -c answered /data/local/tmp/callbot/guard.log 2>/dev/null | tr -dc 0-9)
seen=${seen:-0}
while true; do
  now=$(adb shell grep -c answered /data/local/tmp/callbot/guard.log 2>/dev/null | tr -dc 0-9)
  if [ -n "$now" ] && [ "$now" -gt "$seen" ]; then seen=$now; python3 -u bridge.py; fi
  sleep 1
done
