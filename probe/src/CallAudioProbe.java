import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class CallAudioProbe {
    private static final int RATE = 16000;
    private static final String SHELL = "com.android.shell";
    private static Context shellContext;

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
    private static void log(String message) { System.out.println(timestamp() + " " + message); }
    private static void logError(String message) { System.err.println(timestamp() + " " + message); }

    private static final class FakeContext extends ContextWrapper {
        FakeContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL; }
        @Override public String getOpPackageName() { return SHELL; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName(SHELL).build();
        }
    }

    private static void error(String step, Throwable t) {
        logError(step + ": " + t.getClass().getName() + ": " + t.getMessage());
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            error(step + " cause", t.getCause());
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        try {
            Method m = type.getDeclaredMethod(name, parameters);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            if (type == AudioManager.class) {
                logError("AudioManager methods containing Call or Intercept:");
                for (Method m : AudioManager.class.getDeclaredMethods()) {
                    if (m.getName().contains("Call") || m.getName().contains("Intercept")) {
                        logError(m.toString());
                    }
                }
            }
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    private static AudioManager audioManager() throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = method(activityThread, "systemMain").invoke(null);
        Context systemContext = (Context) method(activityThread, "getSystemContext").invoke(thread);
        Context fake = new FakeContext(systemContext);
        shellContext = fake;
        // 11-25: AudioRecord 建的时候取 ActivityThread 的全局 AttributionSource；systemMain 下是 "android"，
        // 与 uid 2000 对不上，AppOps 拒 RECORD_INCOMING_PHONE_AUDIO。换成以 FakeContext 为底的 Application（scrcpy Workarounds 同法）。
        try {
            android.app.Application app = new android.app.Application();
            Field base = ContextWrapper.class.getDeclaredField("mBase");
            base.setAccessible(true);
            base.set(app, fake);
            Field initial = activityThread.getDeclaredField("mInitialApplication");
            initial.setAccessible(true);
            initial.set(thread, app);
            log("ActivityThread.mInitialApplication set to shell Application; attribution="
                    + AttributionSource.myAttributionSource().getPackageName());
        } catch (Throwable t) {
            error("mInitialApplication swap", t);
        }
        log("FakeContext: package=" + fake.getPackageName() + ", uid=2000");
        AudioManager audio = (AudioManager) fake.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("AUDIO_SERVICE returned null");
        for (String name : new String[] {"mOriginalContext", "mContext"}) {
            try {
                Field field = AudioManager.class.getDeclaredField(name);
                field.setAccessible(true);
                Object previous = field.get(audio);
                log("AudioManager." + name + " was " + (previous == null ? "null" : previous.getClass().getName()));
                if (previous != fake) {
                    field.set(audio, fake);
                    if (field.get(audio) != fake) throw new IllegalStateException(name + " did not retain FakeContext");
                    log("AudioManager." + name + " set to FakeContext");
                }
            } catch (NoSuchFieldException e) {
                error("AudioManager." + name + " not present", e);
            }
        }
        return audio;
    }

    private static AudioFormat format(int rate, int channels) {
        return new AudioFormat.Builder().setSampleRate(rate).setChannelMask(channels)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
    }

    private static void check(AudioManager audio) throws Exception {
        log("isPstnCallAudioInterceptable="
                + method(AudioManager.class, "isPstnCallAudioInterceptable").invoke(audio));
    }

    private static void inject(AudioManager audio, String path) throws Exception {
        File file = new File(path);
        if (!file.isFile() || file.length() == 0 || (file.length() & 1) != 0) {
            throw new IllegalArgumentException("PCM file must exist, be nonempty, and contain complete 16-bit samples: " + path);
        }
        Method getTrack = method(AudioManager.class, "getCallUplinkInjectionAudioTrack", AudioFormat.class);
        AudioTrack track;
        int rate = RATE;
        try {
            log("Requesting uplink AudioTrack: 16000 Hz, mono PCM16");
            track = (AudioTrack) getTrack.invoke(audio, format(rate, AudioFormat.CHANNEL_OUT_MONO));
        } catch (Exception e) {
            error("16000 Hz request failed; retrying at 8000 Hz", e);
            rate = 8000;
            log("Requesting uplink AudioTrack: 8000 Hz, mono PCM16 (input is not resampled)");
            track = (AudioTrack) getTrack.invoke(audio, format(rate, AudioFormat.CHANNEL_OUT_MONO));
        }
        if (track == null) throw new IllegalStateException("Uplink AudioTrack is null");
        preferTelephony(audio, track);
        long written = 0;
        boolean playing = false;
        try (FileInputStream input = new FileInputStream(file)) {
            log("AudioTrack state=" + track.getState() + ", sampleRate=" + track.getSampleRate());
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack is not initialized");
            track.play();
            playing = true;
            log("AudioTrack play state=" + track.getPlayState());
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) {
                int offset = 0;
                while (offset < n) {
                    int count = track.write(buffer, offset, n - offset, AudioTrack.WRITE_BLOCKING);
                    if (count <= 0) throw new IllegalStateException("AudioTrack.write returned " + count);
                    offset += count;
                    written += count;
                }
            }
            log("AudioTrack wrote " + written + " bytes; waiting for playback");
            rate = track.getSampleRate();
            long frames = written / 2;
            long deadline = System.currentTimeMillis() + (frames * 1000 / rate) + 5000;
            while (Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < frames) {
                if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("Timed out waiting for playback head: " + Integer.toUnsignedLong(track.getPlaybackHeadPosition()) + "/" + frames + " frames");
                Thread.sleep(20);
            }
            log("Playback complete: " + frames + " frames");
        } finally {
            if (playing) {
                try { track.stop(); } catch (Exception e) { error("AudioTrack.stop", e); }
            }
            track.release();
            log("AudioTrack released; total bytes written=" + written);
        }
    }

    // 11-25: 本机策略把 USAGE_CALL_ASSISTANT 当普通媒体派去听筒（logcat getOutputForAttr -> EARPIECE），
    // 所以明着点名 TELEPHONY 输出设备。
    private static void preferTelephony(AudioManager audio, AudioTrack track) {
        try {
            android.media.AudioDeviceInfo found = null;
            for (android.media.AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                log("output device id=" + d.getId() + " type=" + d.getType() + " name=" + d.getProductName());
                if (d.getType() == android.media.AudioDeviceInfo.TYPE_TELEPHONY) found = d;
            }
            if (found == null) { log("No TYPE_TELEPHONY output device visible"); return; }
            log("setPreferredDevice(telephony id=" + found.getId() + ")=" + track.setPreferredDevice(found));
        } catch (Throwable t) {
            error("preferTelephony", t);
        }
    }

    private static void routeTest(AudioManager audio) throws Exception {
        AudioTrack track = new AudioTrack.Builder()
                .setAudioFormat(format(RATE, AudioFormat.CHANNEL_OUT_MONO))
                .setBufferSizeInBytes(RATE)
                .build();
        preferTelephony(audio, track);
        track.play();
        byte[] silence = new byte[RATE];
        track.write(silence, 0, silence.length, AudioTrack.WRITE_BLOCKING);
        Thread.sleep(600);
        android.media.AudioDeviceInfo routed = track.getRoutedDevice();
        log("routed device=" + (routed == null ? "null" : ("id=" + routed.getId() + " type=" + routed.getType())));
        track.stop();
        track.release();
    }

    // 11-25: 通话中双向直通。本机 127.0.0.1:<port> 等一个客户端：下行 PCM16/16k 一路写给它，它写来的 PCM16/16k 送上行。
    // 客户端断开、通话结束（mode 不再是 IN_CALL）或 maxSeconds 到了就退。
    private static void stream(AudioManager audio, int port, int maxSeconds) throws Exception {
        final AudioRecord record = (AudioRecord) method(AudioManager.class, "getCallDownlinkExtractionAudioRecord", AudioFormat.class)
                .invoke(audio, format(RATE, AudioFormat.CHANNEL_IN_MONO));
        final AudioTrack track = (AudioTrack) method(AudioManager.class, "getCallUplinkInjectionAudioTrack", AudioFormat.class)
                .invoke(audio, format(RATE, AudioFormat.CHANNEL_OUT_MONO));
        if (record == null || track == null) throw new IllegalStateException("record/track null");
        preferTelephony(audio, track);
        final java.net.ServerSocket server = new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(20000);
        log("stream: listening on 127.0.0.1:" + port);
        final java.net.Socket client = server.accept();
        client.setTcpNoDelay(true);
        log("stream: client connected");
        final boolean[] running = {true};
        final long[] counts = {0, 0};
        record.startRecording();
        log("stream: AudioRecord recording state=" + record.getRecordingState());
        track.play();
        log("stream: AudioTrack play state=" + track.getPlayState());
        Thread down = new Thread(() -> {
            byte[] buffer = new byte[640];
            try {
                java.io.OutputStream out = client.getOutputStream();
                while (running[0]) {
                    int n = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) { logError("stream: record.read=" + n); break; }
                    out.write(buffer, 0, n);
                    counts[0] += n;
                }
            } catch (Throwable t) { error("stream down", t); }
            running[0] = false;
        });
        Thread up = new Thread(() -> {
            byte[] buffer = new byte[3200];
            try {
                java.io.InputStream in = client.getInputStream();
                while (running[0]) {
                    int n = in.read(buffer);
                    if (n < 0) break;
                    int offset = 0;
                    while (offset < n) {
                        int c = track.write(buffer, offset, n - offset, AudioTrack.WRITE_BLOCKING);
                        if (c <= 0) throw new IllegalStateException("track.write=" + c);
                        offset += c;
                    }
                    counts[1] += n;
                }
            } catch (Throwable t) { error("stream up", t); }
            running[0] = false;
        });
        down.start();
        up.start();
        long deadline = SystemClock.elapsedRealtime() + maxSeconds * 1000L;
        while (running[0] && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500);
            if (audio.getMode() != AudioManager.MODE_IN_CALL) { log("stream: call ended (mode=" + audio.getMode() + ")"); break; }
        }
        running[0] = false;
        try { client.close(); } catch (Exception e) { }
        try { server.close(); } catch (Exception e) { }
        try { record.stop(); } catch (Exception e) { }
        try { track.stop(); } catch (Exception e) { }
        record.release();
        track.release();
        log("stream: done, downlink bytes=" + counts[0] + ", uplink bytes=" + counts[1]);
    }

    private static void listen(AudioManager audio, int seconds, String path) throws Exception {
        if (seconds <= 0) throw new IllegalArgumentException("seconds must be positive");
        long target = (long) seconds * RATE * 2;
        log("Requesting downlink AudioRecord: 16000 Hz, mono PCM16, target=" + target + " bytes");
        AudioRecord record = (AudioRecord) method(AudioManager.class, "getCallDownlinkExtractionAudioRecord", AudioFormat.class)
                .invoke(audio, format(RATE, AudioFormat.CHANNEL_IN_MONO));
        if (record == null) throw new IllegalStateException("Downlink AudioRecord is null");
        long total = 0;
        long saved = 0;
        ProbeLogic.PcmStats stats = new ProbeLogic.PcmStats();
        boolean recording = false;
        try {
            log("AudioRecord state=" + record.getState() + ", sampleRate=" + record.getSampleRate());
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord is not initialized");
            try (FileOutputStream output = new FileOutputStream(path)) {
                record.startRecording();
                recording = true;
                log("AudioRecord recording state=" + record.getRecordingState());
                byte[] buffer = new byte[4096];
                while (total < target) {
                    int count = record.read(buffer, 0, (int) Math.min(buffer.length, target - total), AudioRecord.READ_BLOCKING);
                    if (count <= 0) throw new IllegalStateException("AudioRecord.read returned " + count);
                    total += count;
                    stats.add(buffer, count);
                    output.write(buffer, 0, count);
                    saved += count;
                }
            }
        } finally {
            if (recording) {
                try { record.stop(); } catch (Exception e) { error("AudioRecord.stop", e); }
            }
            record.release();
            log("AudioRecord released; actual bytes read=" + total + ", bytes saved=" + saved + ", output=" + path
                    + ", allZero=" + stats.allZero() + ", peak=" + stats.peak());
        }
    }

    private static void useShellContext(Object service, Class<?> type) throws Exception {
        Field field = type.getDeclaredField("mContext");
        field.setAccessible(true);
        field.set(service, shellContext);
        if (field.get(service) != shellContext) throw new IllegalStateException(type.getSimpleName() + " did not retain FakeContext");

    }

    private static void waitCall(AudioManager audio, int waitSeconds, int listenSeconds, String tone, String output) throws Exception {
        if (waitSeconds <= 0 || listenSeconds <= 0) throw new IllegalArgumentException("wait and listen seconds must be positive");
        if (new File(tone).getCanonicalPath().equals(new File(output).getCanonicalPath()))
            throw new IllegalArgumentException("tone and output paths must differ");
        SubscriptionManager subscriptions = (SubscriptionManager) shellContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        TelephonyManager telephony = (TelephonyManager) shellContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (subscriptions == null || telephony == null) throw new IllegalStateException("Telephony services unavailable");
        useShellContext(subscriptions, SubscriptionManager.class);
        useShellContext(telephony, TelephonyManager.class);
        Map<Integer, Integer> lastStates = new HashMap<>();
        long deadline = SystemClock.elapsedRealtime() + waitSeconds * 1000L;
        log("Waiting up to " + waitSeconds + " seconds for OFFHOOK across active subscriptions");
        boolean reportedEmpty = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            List<SubscriptionInfo> active = subscriptions.getActiveSubscriptionInfoList();
            if (active == null || active.isEmpty()) {
                if (!reportedEmpty) log("No active subscriptions visible");
                reportedEmpty = true;
            } else {
                reportedEmpty = false;
                int[] states = new int[active.size()];
                for (int i = 0; i < active.size(); i++) {
                    SubscriptionInfo sub = active.get(i);
                    int id = sub.getSubscriptionId();
                    TelephonyManager forSub = telephony.createForSubscriptionId(id);
                    useShellContext(forSub, TelephonyManager.class);
                    int state = forSub.getCallStateForSubscription();
                    states[i] = state;
                    Integer previous = lastStates.put(id, state);
                    if (previous == null || previous != state)
                        log("SIM slot=" + sub.getSimSlotIndex() + ", subscription=" + id + ", callState=" + state
                                + (state == TelephonyManager.CALL_STATE_OFFHOOK ? " OFFHOOK" : ""));
                }
                int selected = ProbeLogic.firstOffhook(states, TelephonyManager.CALL_STATE_OFFHOOK);
                if (selected >= 0) {
                    SubscriptionInfo sub = active.get(selected);
                    log("OFFHOOK detected: SIM slot=" + sub.getSimSlotIndex() + ", subscription=" + sub.getSubscriptionId() + "; waiting 1 second");
                    Thread.sleep(1000);
                    inject(audio, tone);
                    listen(audio, listenSeconds, output);
                    return;
                }
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0) Thread.sleep(Math.min(250, remaining));
        }
        throw new IllegalStateException("Timed out waiting for OFFHOOK");
    }

    public static void main(String[] args) {
        log("Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        try {
            if (!(args.length == 1 && args[0].equals("check"))
                    && !(args.length == 1 && args[0].equals("route-test"))
                    && !(args.length == 3 && args[0].equals("stream"))
                    && !(args.length == 2 && args[0].equals("inject"))
                    && !(args.length == 3 && args[0].equals("listen"))
                    && !(args.length == 5 && args[0].equals("wait-call"))) {
                throw new IllegalArgumentException("Usage: check | inject <pcm-file> | listen <seconds> <output-pcm> | wait-call <wait-seconds> <listen-seconds> <tone-pcm> <output-pcm>");
            }
            AudioManager audio = audioManager();
            switch (args[0]) {
                case "check": check(audio); break;
                case "route-test": routeTest(audio); break;
                case "stream": stream(audio, Integer.parseInt(args[1]), Integer.parseInt(args[2])); break;
                case "inject": inject(audio, args[1]); break;
                case "listen": listen(audio, Integer.parseInt(args[1]), args[2]); break;
                case "wait-call": waitCall(audio, Integer.parseInt(args[1]), Integer.parseInt(args[2]), args[3], args[4]); break;
                default: throw new AssertionError(args[0]);
            }
            System.out.flush();
            // systemMain() leaves non-daemon threads alive; exit explicitly so the probe never lingers on the phone.
            System.exit(0);
        } catch (Throwable t) {
            error("CallAudioProbe failed", t);
            System.exit(1);
        }
    }
}
