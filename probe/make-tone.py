#!/usr/bin/env python3
"""生成测试用的提示音：440 Hz，3 秒，16 kHz 单声道 16 位。"""
import math
import struct
open("tone.pcm", "wb").write(b"".join(
    struct.pack("<h", int(9000 * math.sin(2 * math.pi * 440 * i / 16000))) for i in range(48000)))
