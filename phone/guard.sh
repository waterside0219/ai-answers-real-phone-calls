#!/system/bin/sh
# 守着电话：响铃 → 自己接 → 开 stream 直通 → 通话完回来接着等。
# 以 shell 身份跑（adb shell，或 Shizuku 的 rish）。停：kill $(cat /data/local/tmp/callbot/guard.pid)
#
# 注意：它会接起打进来的每一通电话。只在你自己的、专门给 AI 用的手机上跑。
B=/data/local/tmp/callbot
L=$B/guard.log
echo $$ > $B/guard.pid
echo "$(date +%T) guard start pid $$" >> $L
while true; do
  # 0 空闲 / 1 响铃 / 2 通话中。双卡手机这里会有两行，-m1 只看第一行，要改成看全部
  s=$(dumpsys telephony.registry 2>/dev/null | grep -m1 mCallState= | tr -dc 0-9)
  if [ "$s" = "1" ]; then
    echo "$(date +%T) ringing -> answer" >> $L
    sleep 2
    input keyevent 5                 # KEYCODE_CALL：接听
    sleep 1.5
    echo "$(date +%T) answered, stream up" >> $L
    CLASSPATH=$B/probe.jar app_process / CallAudioProbe stream 8195 600 > $B/stream.log 2>&1
    echo "$(date +%T) stream exit $?" >> $L
    sleep 2
  fi
  sleep 0.5
done
