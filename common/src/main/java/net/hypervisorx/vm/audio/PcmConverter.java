package net.hypervisorx.vm.audio;

/**
 * Turns interleaved signed 16-bit little-endian PCM (any rate, any channel count) into mono
 * {@code outRate} samples, since OpenAL only spatializes mono sources. Box filter when downsampling
 * 2x or more, linear interpolation otherwise. Keeps the fractional position between chunks.
 */
public final class PcmConverter {
    private final int inRate;
    private final int channels;
    private final int outRate;
    private double position;
    private short[] out = new short[4096];

    public PcmConverter(int inRate, int channels, int outRate) {
        this.inRate = inRate;
        this.channels = Math.max(1, channels);
        this.outRate = outRate;
    }

    /** @return how many samples went into {@link #buffer()}. */
    public int convert(byte[] pcm, int length) {
        int frames = length / (2 * channels);
        if (frames == 0) return 0;
        double step = (double) inRate / outRate;
        int maxOut = (int) Math.ceil(frames / step) + 2;
        if (out.length < maxOut) out = new short[maxOut];
        int n = 0;
        while (position < frames) {
            int i0 = (int) position;
            int s;
            if (step >= 2.0) {
                int end = Math.min(frames, Math.max(i0 + 1, (int) (position + step)));
                long sum = 0;
                for (int f = i0; f < end; f++) sum += mono(pcm, f);
                s = (int) (sum / (end - i0));
            } else {
                double frac = position - i0;
                int b = mono(pcm, i0);
                int c = i0 + 1 < frames ? mono(pcm, i0 + 1) : b;
                s = (int) (b + (c - b) * frac);
            }
            out[n++] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
            position += step;
        }
        position -= frames;
        return n;
    }

    public short[] buffer() {
        return out;
    }

    private int mono(byte[] pcm, int frame) {
        int base = frame * channels * 2;
        int sum = 0;
        for (int c = 0; c < channels; c++) {
            int o = base + c * 2;
            sum += (short) ((pcm[o] & 0xFF) | (pcm[o + 1] << 8));
        }
        return sum / channels;
    }
}
