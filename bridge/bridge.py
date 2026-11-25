#!/usr/bin/env python3
"""电话桥：手机上的 stream 模式 ⇄ 这里按停顿切句 ⇄ turn.py。

手机那头 `CallAudioProbe stream 8195 <秒>` 在 127.0.0.1:8195 上吐对方的声音、收要送给对方的声音。
用 `adb forward tcp:8195 tcp:8195` 把它接到电脑上，再跑本脚本。

半双工：AI 说话的时候不听（免得把自己的声音又听回去）。

环境变量：
  TURN_CMD   怎么调用 turn.py，默认 "python3 turn.py"。turn.py 在另一台机器上就写成
             "ssh myserver python3 /path/turn.py"（钥匙放在那台机器上，不放手机也不放这里）
  PORT       默认 8195
"""
import json
import math
import os
import shlex
import socket
import struct
import subprocess
import time
import uuid
from pathlib import Path

TURN_CMD = shlex.split(os.environ.get("TURN_CMD", "python3 turn.py"))
PORT = int(os.environ.get("PORT", "8195"))
TURNS = Path(__file__).with_name("turns")
FRAME = 640                      # 20 毫秒
THRESHOLD = 120                  # 音量过这个数算"在说话"。静的时候我们量到的是 1 上下，人声几百
START_MS, END_MS, MAX_SECONDS = 120, 700, 15


def log(message):
    print(time.strftime("%T"), message, flush=True)


def loudness(frame):
    samples = struct.unpack("<%dh" % (len(frame) // 2), frame)
    return math.sqrt(sum(s * s for s in samples) / len(samples))


def ask(call_id, turn, pcm):
    result = subprocess.run(TURN_CMD + [call_id, str(turn)], input=pcm, capture_output=True, timeout=90)
    data = result.stdout
    if len(data) < 4:
        log(f"turn {turn}: nothing came back: {result.stderr[-200:]!r}")
        return {}, b""
    size = struct.unpack(">I", data[:4])[0]
    return json.loads(data[4:4 + size]), data[4 + size:]


def main():
    TURNS.mkdir(exist_ok=True)
    call_id = "call-" + uuid.uuid4().hex[:12]
    sock = None
    for _ in range(40):
        try:
            sock = socket.create_connection(("127.0.0.1", PORT), timeout=2)
            break
        except OSError:
            time.sleep(0.5)
    if not sock:
        log("phone stream is not up")
        return
    sock.settimeout(5)
    log("connected " + call_id)
    turn, mute_until = 0, 0.0
    pending, segment = b"", bytearray()
    loud = quiet = 0
    speaking = False
    while True:
        try:
            data = sock.recv(3200)
        except socket.timeout:
            log("no audio for 5s")
            break
        if not data:
            log("call ended")
            break
        pending += data
        while len(pending) >= FRAME:
            frame, pending = pending[:FRAME], pending[FRAME:]
            if time.time() < mute_until:
                segment.clear()
                loud = quiet = 0
                speaking = False
                continue
            if loudness(frame) > THRESHOLD:
                loud += 20
                quiet = 0
            else:
                quiet += 20
                if not speaking:
                    loud = max(0, loud - 20)
            segment.extend(frame)
            if not speaking:
                if len(segment) > 16000:                 # 没开口时只留半秒前导
                    del segment[:FRAME]
                if loud >= START_MS:
                    speaking = True
                    log("speech start")
            elif quiet >= END_MS or len(segment) > 32000 * MAX_SECONDS:
                turn += 1
                heard = bytes(segment)
                segment.clear()
                speaking = False
                loud = quiet = 0
                (TURNS / f"{call_id}-{turn:02d}-caller.pcm").write_bytes(heard)
                started = time.time()
                head, reply = ask(call_id, turn, heard)
                log(f"turn {turn}: {time.time() - started:.1f}s {json.dumps(head, ensure_ascii=False)}")
                if reply:
                    (TURNS / f"{call_id}-{turn:02d}-me.pcm").write_bytes(reply)
                    sock.sendall(reply)
                    mute_until = time.time() + len(reply) / 32000 + 0.4
                pending = b""
                sock.settimeout(0.01)                    # 等回话期间攒下的声音不要了
                try:
                    while sock.recv(65536):
                        pass
                except (socket.timeout, BlockingIOError):
                    pass
                sock.settimeout(5)
    log(f"end, {turn} turns")


if __name__ == "__main__":
    main()
