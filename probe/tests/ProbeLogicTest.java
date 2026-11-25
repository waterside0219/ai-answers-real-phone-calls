public class ProbeLogicTest {
    public static void main(String[] args) {
        if (ProbeLogic.firstOffhook(new int[]{0, 2}, 2) != 1) throw new AssertionError("second SIM OFFHOOK must be detected");
        if (ProbeLogic.firstOffhook(new int[]{1, 0}, 2) != -1) throw new AssertionError("ringing is not OFFHOOK");
        ProbeLogic.PcmStats stats = new ProbeLogic.PcmStats();
        stats.add(new byte[]{0, 0, 0}, 3);
        stats.add(new byte[]{(byte) 0x80, (byte) 0xff, 0x7f}, 3);
        if (stats.peak() != 32768) throw new AssertionError("PCM16 peak across odd-sized reads");
        if (stats.allZero()) throw new AssertionError("nonzero PCM bytes must not be reported all-zero");
        ProbeLogic.PcmStats odd = new ProbeLogic.PcmStats();
        odd.add(new byte[]{1}, 1);
        if (odd.allZero()) throw new AssertionError("unpaired nonzero byte must not be reported all-zero");
        System.out.println("ProbeLogicTest passed");
    }
}
