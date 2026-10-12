package comp1.chat.client.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioMixerTest {

    static byte[] constant(int value) {
        byte[] f = new byte[AudioConfig.FRAME_SIZE];
        for (int i = 0; i < f.length; i += 2) {
            f[i] = (byte) value;
            f[i + 1] = (byte) (value >> 8);
        }
        return f;
    }

    static int sample(byte[] f, int i) {
        return (short) ((f[2 * i] & 0xFF) | (f[2 * i + 1] << 8));
    }

    @Test
    void sumsSamplesFromAllSources() {
        byte[] out = AudioMixer.mix(List.of(constant(1000), constant(-300), constant(50)));
        assertEquals(750, sample(out, 0));
        assertEquals(750, sample(out, AudioConfig.FRAME_SIZE / 2 - 1));
    }

    @Test
    void saturatesInsteadOfWrapping() {
        assertEquals(Short.MAX_VALUE, sample(AudioMixer.mix(List.of(constant(30000), constant(30000))), 0));
        assertEquals(Short.MIN_VALUE, sample(AudioMixer.mix(List.of(constant(-30000), constant(-30000))), 0));
    }
}
