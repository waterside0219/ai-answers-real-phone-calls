final class ProbeLogic {
    static int firstOffhook(int[] states, int offhook) {
        for (int i = 0; i < states.length; i++) if (states[i] == offhook) return i;
        return -1;
    }

    static final class PcmStats {
        private int pending = -1;
        private int peak;
        private boolean allZero = true;
        void add(byte[] bytes, int count) {
            for (int i = 0; i < count; i++) {
                int value = bytes[i] & 0xff;
                if (value != 0) allZero = false;
                if (pending < 0) pending = value;
                else {
                    int sample = (short) (pending | (value << 8));
                    peak = Math.max(peak, Math.abs(sample));
                    pending = -1;
                }
            }
        }
        int peak() { return peak; }
        boolean allZero() { return allZero; }
    }
}
