package comp1.chat.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Recibe el audio de todos los participantes por un unico socket UDP y lo reproduce mezclado.
 *
 * Dos hilos:
 *  - RX: lee datagramas, identifica la fuente por su direccion y los guarda en el JitterBuffer de esa fuente.
 *  - Mixer: cada 20 ms toma un frame de cada fuente activa, los suma y los entrega al altavoz.
 *    Ese reloj unico es lo que mantiene sincronizadas las distintas fuentes.
 */
public class AudioReceiver {

    private final DatagramSocket socket;
    private final AudioFormat format;
    private final Consumer<byte[]> sinkOverride;
    private final Map<SocketAddress, JitterBuffer> sources = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private Thread rxThread;
    private Thread mixThread;

    public AudioReceiver(DatagramSocket socket) {
        this(socket, null);
    }

    /** Si sink no es null los frames mezclados se entregan alli en lugar de al altavoz (pruebas). */
    AudioReceiver(DatagramSocket socket, Consumer<byte[]> sink) {
        this.socket = socket;
        this.format = AudioConfig.getFormat();
        this.sinkOverride = sink;
    }

    public int getActiveSourceCount() {
        return sources.size();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        rxThread = new Thread(this::receiveLoop, "AudioReceiver-RX");
        rxThread.setDaemon(true);
        rxThread.start();

        mixThread = new Thread(this::mixLoop, "AudioReceiver-Mixer");
        mixThread.setDaemon(true);
        mixThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (rxThread != null) {
            rxThread.interrupt();
            rxThread = null;
        }
        if (mixThread != null) {
            mixThread.interrupt();
            mixThread = null;
        }
        sources.clear();
    }

    // ---------------------------------------------------------------- RX

    private void receiveLoop() {
        byte[] buffer = new byte[AudioConfig.MAX_PACKET_SIZE + 64];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && !socket.isClosed()) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);

                int payload = packet.getLength() - AudioConfig.HEADER_SIZE;
                if (payload <= 0 || payload > AudioConfig.FRAME_SIZE || payload % 2 != 0) {
                    continue; // no es un frame de audio valido
                }

                int seq = ByteBuffer.wrap(packet.getData(), packet.getOffset(), AudioConfig.HEADER_SIZE).getInt();
                byte[] pcm = Arrays.copyOfRange(packet.getData(),
                        packet.getOffset() + AudioConfig.HEADER_SIZE, packet.getOffset() + packet.getLength());

                sources.computeIfAbsent(packet.getSocketAddress(), a -> new JitterBuffer()).offer(seq, pcm);
            } catch (SocketException se) {
                break; // socket cerrado intencionalmente
            } catch (IOException ioe) {
                if (running) {
                    System.err.println("[AUDIO-RX] Error de lectura UDP: " + ioe.getMessage());
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("[AUDIO-RX] Excepcion inesperada: " + e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------- Mixer

    private void mixLoop() {
        SourceDataLine speaker = sinkOverride == null ? openSpeaker() : null;
        long periodNanos = AudioConfig.FRAME_MILLIS * 1_000_000L;
        long next = System.nanoTime() + periodNanos;

        try {
            while (running) {
                long wait;
                while (running && (wait = next - System.nanoTime()) > 0) {
                    LockSupport.parkNanos(wait);
                }
                next += periodNanos;
                if (System.nanoTime() - next > 5 * periodNanos) {
                    next = System.nanoTime() + periodNanos; // el hilo se atraso demasiado: resincronizar
                }

                if (speaker != null && speakerQueuedBytes(speaker) > AudioConfig.MAX_SPEAKER_QUEUE_FRAMES * AudioConfig.FRAME_SIZE) {
                    continue; // la tarjeta de sonido va mas lenta que el reloj: dejar que los buffers absorban
                }

                byte[] mixed = mixOneFrame();
                if (mixed == null) {
                    continue;
                }
                if (sinkOverride != null) {
                    sinkOverride.accept(mixed);
                } else if (speaker != null) {
                    speaker.write(mixed, 0, mixed.length);
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("[AUDIO-MIX] Error en la mezcla: " + e.getMessage());
            }
        } finally {
            closeSpeaker(speaker);
        }
    }

    private byte[] mixOneFrame() {
        List<byte[]> frames = new ArrayList<>(sources.size());

        for (Iterator<JitterBuffer> it = sources.values().iterator(); it.hasNext(); ) {
            JitterBuffer source = it.next();
            byte[] frame = source.poll();
            if (frame != null) {
                frames.add(frame);
            } else if (source.isIdle()) {
                it.remove();
            }
        }

        return frames.isEmpty() ? null : AudioMixer.mix(frames);
    }

    // ----------------------------------------------------------- Speaker

    private SourceDataLine openSpeaker() {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try {
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("[AUDIO-RX] Salida de audio no soportada en este entorno.");
                return null;
            }
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, AudioConfig.FRAME_SIZE * 8);
            line.start();
            return line;
        } catch (Exception e) {
            System.err.println("[AUDIO-RX] Dispositivo de altavoces no disponible: " + e.getMessage());
            return null;
        }
    }

    private static int speakerQueuedBytes(SourceDataLine line) {
        return line.getBufferSize() - line.available();
    }

    private static void closeSpeaker(SourceDataLine line) {
        if (line == null) {
            return;
        }
        try {
            line.stop();
            line.close();
        } catch (Exception ignored) {
        }
    }
}
