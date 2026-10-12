package comp1.chat.client.audio;

import javax.sound.sampled.AudioFormat;

public class AudioConfig {
    // 16 kHz, 16 bits, Mono, Signed, Little-Endian
    public static final float SAMPLE_RATE = 16000.0f;
    public static final int SAMPLE_SIZE_IN_BITS = 16;
    public static final int CHANNELS = 1;
    public static final boolean SIGNED = true;
    public static final boolean BIG_ENDIAN = false;

    // 20 ms por frame de audio: 16000 muestras/seg * 2 bytes/muestra * 0.02 seg = 640 bytes
    public static final int FRAME_SIZE = 640;

    public static AudioFormat getFormat() {
        return new AudioFormat(
                SAMPLE_RATE,
                SAMPLE_SIZE_IN_BITS,
                CHANNELS,
                SIGNED,
                BIG_ENDIAN
        );
    }
}
