package comp1.chat.client.audio;

import java.util.Map;
import java.util.TreeMap;

/**
 * Buffer de jitter de UNA fuente de audio. Reordena los frames por numero de secuencia,
 * sustituye por silencio los frames perdidos (para no desplazar el audio respecto a las
 * otras fuentes), descarta los que llegan tarde y limita la latencia acumulada.
 */
final class JitterBuffer {

    // Si llega un numero de secuencia mucho menor al esperado, el emisor reinicio su contador
    private static final int RESET_GAP = 50;
    private static final byte[] SILENCE = new byte[AudioConfig.FRAME_SIZE];

    private final TreeMap<Integer, byte[]> frames = new TreeMap<>();
    private int nextSeq;
    private boolean playing;
    private long lastArrivalNanos = System.nanoTime();

    synchronized void offer(int seq, byte[] pcm) {
        lastArrivalNanos = System.nanoTime();

        if (playing && seq < nextSeq) {
            if (nextSeq - seq < RESET_GAP) {
                return; // llego tarde: su turno de reproduccion ya paso
            }
            frames.clear(); // el emisor reinicio la numeracion: volver a llenar el colchon
            playing = false;
        }

        frames.putIfAbsent(seq, pcm);

        while (frames.size() > AudioConfig.MAX_BUFFER_FRAMES) {
            Map.Entry<Integer, byte[]> dropped = frames.pollFirstEntry();
            if (playing && dropped.getKey() >= nextSeq) {
                nextSeq = dropped.getKey() + 1;
            }
        }
    }

    /**
     * Devuelve el siguiente frame a reproducir, un frame de silencio si ese numero de
     * secuencia se perdio, o null si no hay nada que reproducir (llenando colchon o fuente agotada).
     */
    synchronized byte[] poll() {
        if (!playing) {
            if (frames.size() < AudioConfig.JITTER_PREFILL_FRAMES) {
                return null;
            }
            playing = true;
            nextSeq = frames.firstKey();
        }

        if (frames.isEmpty()) {
            playing = false; // underrun: volver a llenar el colchon antes de seguir
            return null;
        }

        byte[] frame = frames.remove(nextSeq++);
        return frame != null ? frame : SILENCE;
    }

    synchronized boolean isIdle() {
        long idleMillis = (System.nanoTime() - lastArrivalNanos) / 1_000_000;
        return frames.isEmpty() && idleMillis > AudioConfig.SOURCE_IDLE_TIMEOUT_MS;
    }
}
