package comp1.chat.client.audio;

import java.util.List;

/** Mezcla varios frames PCM (16 bit, little-endian, mono) sumando muestras con saturacion. */
final class AudioMixer {

    private AudioMixer() {
    }

    static byte[] mix(List<byte[]> frames) {
        int samples = AudioConfig.FRAME_SIZE / 2;
        int[] acc = new int[samples];

        for (byte[] frame : frames) {
            int n = Math.min(samples, frame.length / 2);
            for (int i = 0; i < n; i++) {
                acc[i] += (short) ((frame[2 * i] & 0xFF) | (frame[2 * i + 1] << 8));
            }
        }

        byte[] out = new byte[AudioConfig.FRAME_SIZE];
        for (int i = 0; i < samples; i++) {
            int s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, acc[i]));
            out[2 * i] = (byte) s;
            out[2 * i + 1] = (byte) (s >> 8);
        }
        return out;
    }
}
