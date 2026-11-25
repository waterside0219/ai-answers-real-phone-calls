#!/usr/bin/env python3
"""电话里的一轮：对方说的一段声音进来，回话的声音出去。

stdin : 对方的一段 PCM（16 kHz、单声道、16 位小端）
stdout: 4 字节大端长度 + JSON 头 {"heard", "reply", "ms"} + 回话的 PCM（同格式）
用法  : turn.py <通话编号> <第几轮>

三件事，各自一个函数，换成你自己的服务就改对应那个：
  transcribe  听写。这里用 ElevenLabs Scribe            （环境变量 ELEVENLABS_API_KEY）
  think       想。把字 POST 给你的 agent，拿回它的话     （BRAIN_URL，可选 BRAIN_TOKEN）
  speak       出声。这里用 MiniMax 语音合成               （MINIMAX_API_KEY、MINIMAX_VOICE_ID）

think 留成一个 HTTP 钩子是有意的：我们自己那头，它通到一个一直开着的 agent 会话，
所以电话里答话的和平时聊天的是同一个它，带着同一份记忆。你也可以让它指向任何模型。
"""
import asyncio
import json
import os
import struct
import sys
import time
import urllib.request


def transcribe(pcm):
    boundary = "turn" + str(time.monotonic_ns())

    def field(name, value):
        return f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()

    body = (field("model_id", "scribe_v2") + field("file_format", "pcm_s16le_16")
            + f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.pcm"\r\n'
              f'Content-Type: application/octet-stream\r\n\r\n'.encode()
            + pcm + f"\r\n--{boundary}--\r\n".encode())
    request = urllib.request.Request(
        "https://api.elevenlabs.io/v1/speech-to-text", data=body,
        headers={"xi-api-key": os.environ["ELEVENLABS_API_KEY"],
                 "Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(request, timeout=8) as response:
        result = json.loads(response.read())
    # 只有呼吸声、杂音的时候 Scribe 也会回一些标记；没有真的"词"就当没人说话
    if not any(w.get("type") == "word" for w in result.get("words", [])):
        return ""
    return result.get("text", "").strip()


def think(call_id, turn, text):
    payload = json.dumps({"call_id": call_id, "turn": turn, "text": text}, ensure_ascii=False).encode()
    headers = {"Content-Type": "application/json"}
    if os.environ.get("BRAIN_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["BRAIN_TOKEN"]
    request = urllib.request.Request(os.environ["BRAIN_URL"], data=payload, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read())["text"].strip()


async def _minimax(text):
    from websockets.asyncio.client import connect   # pip install websockets
    audio = bytearray()
    async with connect("wss://api.minimaxi.com/ws/v1/t2a_v2",
                       additional_headers={"Authorization": "Bearer " + os.environ["MINIMAX_API_KEY"]},
                       max_size=2 * 1024 * 1024) as ws:
        await ws.recv()                                   # connected_success
        await ws.send(json.dumps({
            "event": "task_start", "model": "speech-02-hd", "language_boost": "Chinese",
            "voice_setting": {"voice_id": os.environ["MINIMAX_VOICE_ID"], "speed": 1.0, "vol": 1.0, "pitch": 0},
            "audio_setting": {"sample_rate": 32000, "format": "pcm", "channel": 1}}))
        await ws.recv()                                   # task_started
        await ws.send(json.dumps({"event": "task_continue", "text": text}, ensure_ascii=False))
        while True:
            message = json.loads(await ws.recv())
            chunk = (message.get("data") or {}).get("audio") or ""
            if chunk:
                audio.extend(bytes.fromhex(chunk))
            if message.get("is_final"):
                break
        await ws.send(json.dumps({"event": "task_finish"}))
    return bytes(audio)


def speak(text):
    pcm32 = asyncio.run(_minimax(text))
    # 32 kHz → 16 kHz：两个采样取平均。电话线本来就窄，够用
    n = len(pcm32) // 4
    samples = struct.unpack("<%dh" % (n * 2), pcm32[:n * 4])
    return struct.pack("<%dh" % n, *[(samples[2 * i] + samples[2 * i + 1]) // 2 for i in range(n)])


def main():
    call_id, turn = sys.argv[1], int(sys.argv[2])
    started = time.monotonic()
    elapsed = lambda: round((time.monotonic() - started) * 1000)
    head, pcm = {"heard": "", "reply": "", "ms": {}}, b""
    try:
        head["heard"] = transcribe(sys.stdin.buffer.read())
        head["ms"]["stt"] = elapsed()
        if head["heard"]:
            head["reply"] = think(call_id, turn, head["heard"])
            head["ms"]["brain"] = elapsed()
        if head["reply"]:
            pcm = speak(head["reply"])
            head["ms"]["tts"] = elapsed()
    except Exception as error:               # 出了错也要回一个头，桥那头才不会干等
        head["error"] = type(error).__name__
    encoded = json.dumps(head, ensure_ascii=False).encode()
    sys.stdout.buffer.write(struct.pack(">I", len(encoded)) + encoded + pcm)


if __name__ == "__main__":
    main()
