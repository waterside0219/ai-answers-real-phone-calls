# 让 AI 接一通真的电话

不是网络电话，不是 App 里的语音房。是有人拨了一个手机号，一部安卓手机响了，没有人碰它，它自己接起来，电话那头说话的是你的 AI：它听得见对方，它的声音走电话线进到对方耳朵里，答话的是带着记忆的它本人。

## 为什么做这个

起因是一个很具体的需要：一个人住的女性，不想让每一个打来电话的陌生人都听到自己的声音。

外卖到了，快递到了，一个没见过的号码。接起来，对方就知道了这是一位女性，多问两句，就知道了她一个人在家。如果接电话的是另一个声音，替她说一句"放门口就行，谢谢"，这些信息就不会从电话里漏出去。

所以这个项目最终要做成的样子是：

- 来电由 AI 接听，用它自己的声音应答，不是机主的声音。
- 接通后先安静地听，对方开口了再回答。
- 送外卖、送快递这类电话，直接用一句预先备好的话回复，要快。
- 其他来电，像一个真人那样对方问什么答什么，不多说。
- 对方一直不说话，就不出声，稍后挂断，事后告诉机主几点有电话打来。
- 机主自己没接到的电话，转给 AI 接，接完告诉机主。
- 每通电话结束后给机主一条小结：时间、号码、对方说了什么、AI 回了什么。
- 有一张"不说"的清单：不透露机主是否独居、是否在家，不透露姓名和其他个人信息。
- 自称家人、单位、医院、警方等要紧来电，回复"她现在不方便，稍后回电"，并立即通知机主。
- 推销和可疑来电礼貌结束；任何索要验证码的请求一律拒绝。
- 对方认真询问身份时，回答"我是替她接电话的"，不冒充机主本人。

现在做到的是地基：手机能自己接、能听见对方、声音能送给对方、接到一个带记忆的 AI。上面这些应答的规矩还没有写进去，见[还没做完的](#还没做完的)。

**状态：进行中。** 2026 年 11 月 25 日夜里，三个底层关口各过了一遍，整条链路跑通了四通电话。离"能天天用"还差一截，差在哪[照实写在下面](#还没做完的)。先把已经过了的部分放出来，因为这一段最难找到资料。

没有 root。靠的是 `adb shell` 这个身份（uid 2000）本来就有的几项权限，和安卓 12 起藏在 `AudioManager` 里的通话音频接口。

## 三个关口

| 关口 | 做法 | 我们撞到的墙 |
|---|---|---|
| 自己接起来 | 轮询 `dumpsys telephony.registry` 的 `mCallState`，变成 1 就 `input keyevent 5` | 没有 |
| 听得见对方 | `AudioManager.getCallDownlinkExtractionAudioRecord()` | 录音一开就停，读到 0 字节。[系统核对"谁在录"时名字对不上](docs/踩坑.md#1-录音一开就停读到-0-字节) |
| 声音送得进电话线 | `AudioManager.getCallUplinkInjectionAudioTrack()` | 程序说送完了，对方什么都没听到，声音从这部手机自己的听筒出来了。[要点名输出设备](docs/踩坑.md#2-程序说送完了对方没听到) |

第三关是最容易以为"成了"的地方：程序不报错，返回码是 0，只有电话那头的人的耳朵能告诉你没成。

## 整条怎么连

```
打电话的人
   │  普通电话
   ▼
安卓手机（shell 身份）
   guard.sh          响铃 → 接听
   CallAudioProbe    对方的声音 → 127.0.0.1:8195 → 要说的声音
   │  adb forward（今晚靠一根线）
   ▼
电脑  bridge.py      按停顿把对方的话切成一句一句
   │
   ▼
turn.py              听写 → 交给 agent → 合成它的嗓子
```

`turn.py` 里"交给 agent"那一步是一个 HTTP 钩子。我们自己那头，它通到一个一直开着的 agent 会话，所以电话里答话的和平时聊天的是同一个它。你可以让它指向任何模型。仓库里带了一个只会复述的 `echo_brain.py`，先拿它把链路跑通。

## 我们的机器

小米 MIX Flip 2，HyperOS 2，Android 15，高通平台，中国电信单卡。**别的机型不保证。** 做过类似事情的开源项目 [BCP](https://github.com/chenxiaolong/BCP) 的作者说过，往通话里送声音"绝大多数手机不支持"，要看厂商的音频底层有没有留那条通道。怎么判断你的手机有没有，见[踩坑第 3 条](docs/踩坑.md#3-怎么知道你的手机有没有这条通道)。

## 跑起来

```sh
# 1. 编译手机上那个小程序（要 JDK 11+ 和 Android SDK）
export JAVA_HOME=<你的 JDK>  ANDROID_HOME=<你的 Android SDK>
./probe/build.sh && (cd probe && python3 make-tone.py)

# 2. 放进手机
adb shell mkdir -p /data/local/tmp/callbot
adb push probe/probe.jar probe/tone.pcm phone/guard.sh /data/local/tmp/callbot/

# 3. 不打电话，先看这台手机认不认
adb shell 'CLASSPATH=/data/local/tmp/callbot/probe.jar app_process / CallAudioProbe check'
#    要看到 isPstnCallAudioInterceptable=true
adb shell 'CLASSPATH=/data/local/tmp/callbot/probe.jar app_process / CallAudioProbe route-test'
#    要看到 setPreferredDevice(telephony ...)=true 和 routed device=... type=18

# 4. 一关一关验（用另一部手机拨过来、接通之后跑）
adb shell 'CLASSPATH=/data/local/tmp/callbot/probe.jar app_process / CallAudioProbe inject /data/local/tmp/callbot/tone.pcm'
#    问电话那头的人：嘟声是从你的手机里出来的吗？
adb shell 'CLASSPATH=/data/local/tmp/callbot/probe.jar app_process / CallAudioProbe listen 10 /data/local/tmp/callbot/down.pcm'
#    让对方说话；日志里 peak 不是 0，拉回来听一听

# 5. 整条
adb shell 'setsid nohup sh /data/local/tmp/callbot/guard.sh >/dev/null 2>&1 < /dev/null &'
cd bridge && python3 echo_brain.py 8799 &
export BRAIN_URL=http://127.0.0.1:8799/ ELEVENLABS_API_KEY=... MINIMAX_API_KEY=... MINIMAX_VOICE_ID=...
./loop.sh
#    现在拨这部手机。接通后你先开口，说完停住等它。
```

## 还没做完的

- **这份代码整理后还没重新验。** `probe/` 是今晚实际跑的源码，用仓库里的脚本重新编译过。`bridge/` 和 `phone/guard.sh` 是今晚用的那版去掉我们自己的路径和依赖后重写的：`turn.py` 配 `echo_brain.py` 单独跑通过（听写、合成都真调了），但整理后的桥和守门脚本还没有再接一通真电话。

- **靠线。** 桥在电脑上，经 `adb forward` 连手机。拔了线、关了电脑就没有了。下一步是把桥搬进手机（Termux），手机自己连服务器。
- **慢。** 从对方说完到 AI 出声，我们量到的是 19 到 28 秒。听写不到 1 秒，合成 3 秒上下，其余是 agent 在想，加上整段合成完才开始播。要改成流式，先出一句短的。
- **不会自己挂断。**
- **应答的规矩还没写。** 上面"为什么做这个"里列的那些都还没有实现。现在是每一通都接起来、直接交给 AI。
- **重启以后要重新起。** shell 身份的进程重启就没了。
- `wait-call` 模式是坏的（`TelephonyServiceManager` 在这个进程里没初始化），用 `guard.sh` 的轮询代替了，代码留着没删。
- 只在一台手机、一家运营商上验过。免提、蓝牙耳机、双卡，都没试。

## 谁做了什么

- **阿年**：提出这件事和它的用处，定下应答的规矩。一晚上拿自己的手机拨了八通，每一通告诉我她那头听到了什么。第三关是她的耳朵验出来的。
- **沈砚（Claude）**：读日志、改程序、连链路、写这份文档。
- 手机上那个小程序的第一版是 **Codex** 照施工单写的。

## 两句提醒

电话那头的人会被录音、转成文字，很多地方这需要让对方知道。`guard.sh` 会接起每一通来电，只在专门给 AI 用的手机上跑它。

## 许可

MIT。FakeContext 和替换 `mInitialApplication` 的做法来自 [scrcpy](https://github.com/Genymobile/scrcpy) 的 Workarounds（Apache-2.0），思路也见于 [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)。

## 同一个家里的其他东西

这些是同一个人和同一个 AI 一起做的，可以连着看：

- [android-phone-as-ai-body](https://github.com/waterside0219/android-phone-as-ai-body)：给 AI 一部手机当身子，脸、眼睛、耳朵、嗓子
- [ai-live2d-from-one-image](https://github.com/waterside0219/ai-live2d-from-one-image)：一张平面图做成会动的 Live2D，装进手机和桌面
- [ai-handwritten-imessage](https://github.com/waterside0219/ai-handwritten-imessage)：AI 用自己的笔迹，在 iMessage 里一笔一笔写字
- [ai-game-companion](https://github.com/waterside0219/ai-game-companion)：AI 陪你打游戏，带路、预警、搭话，和它该什么时候闭嘴
