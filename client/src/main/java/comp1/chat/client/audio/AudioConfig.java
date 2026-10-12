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
    public static final int FRAME_MILLIS = 20;

    // Cada datagrama: [numero de secuencia int32 big-endian][FRAME_SIZE bytes PCM]
    public static final int HEADER_SIZE = 4;
    public static final int MAX_PACKET_SIZE = HEADER_SIZE + FRAME_SIZE;

    // Sincronizacion de varias fuentes: cada emisor tiene su propio buffer de jitter y un unico
    // reloj de 20 ms mezcla un frame de cada fuente activa.
    public static final int JITTER_PREFILL_FRAMES = 3;     // 60 ms de colchon antes de empezar a reproducir
    public static final int MAX_BUFFER_FRAMES = 10;        // 200 ms maximo; si se excede se descartan los mas viejos
    public static final long SOURCE_IDLE_TIMEOUT_MS = 1000; // sin paquetes durante este tiempo => la fuente se elimina
    public static final int MAX_SPEAKER_QUEUE_FRAMES = 4;  // no encolar mas de 80 ms en la tarjeta de sonido

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
