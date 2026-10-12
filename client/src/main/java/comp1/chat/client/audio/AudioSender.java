package comp1.chat.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class AudioSender implements Runnable {

    private final DatagramSocket socket;
    private final AudioFormat format;
    private final Set<InetSocketAddress> targets = new CopyOnWriteArraySet<>();

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private TargetDataLine micLine;
    private Thread thread;

    public AudioSender(DatagramSocket socket) {
        this.socket = socket;
        this.format = AudioConfig.getFormat();
    }

    public void addTarget(String ip, int port) {
        targets.add(new InetSocketAddress(ip, port));
    }

    public void removeTarget(String ip, int port) {
        targets.remove(new InetSocketAddress(ip, port));
    }

    public void clearTargets() {
        targets.clear();
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "AudioSender-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        try {
            if (AudioSystem.isLineSupported(info)) {
                micLine = (TargetDataLine) AudioSystem.getLine(info);
                micLine.open(format, AudioConfig.FRAME_SIZE * 8);
                micLine.start();
            } else {
                System.err.println("[AUDIO-TX] Microfono no soportado en este entorno.");
            }
        } catch (LineUnavailableException e) {
            System.err.println("[AUDIO-TX] Microfono no disponible: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[AUDIO-TX] Advertencia al inicializar microfono: " + e.getMessage());
        }

        byte[] buffer = new byte[AudioConfig.FRAME_SIZE];
        byte[] packetBuf = new byte[AudioConfig.MAX_PACKET_SIZE];
        int seq = 0;

        while (running && !socket.isClosed()) {
            if (micLine != null && micLine.isOpen()) {
                int bytesRead = micLine.read(buffer, 0, buffer.length);

                if (bytesRead > 0 && !muted && !targets.isEmpty()) {
                    // Mismo frame (y misma secuencia) para todos los destinos de la malla.
                    // La secuencia solo avanza al enviar, asi mute/unmute no genera huecos.
                    ByteBuffer.wrap(packetBuf).putInt(0, seq++);
                    System.arraycopy(buffer, 0, packetBuf, AudioConfig.HEADER_SIZE, bytesRead);

                    for (InetSocketAddress target : targets) {
                        try {
                            DatagramPacket packet = new DatagramPacket(
                                    packetBuf, AudioConfig.HEADER_SIZE + bytesRead, target);
                            socket.send(packet);
                        } catch (IOException e) {
                            if (running) {
                                // Paquete descartado, continuo por naturaleza de UDP
                            }
                        }
                    }
                }
            } else {
                // Si no hay microfono disponible, dormimos brevemente para no saturar CPU
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        cleanup();
    }

    public synchronized void stop() {
        running = false;
        cleanup();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void cleanup() {
        if (micLine != null) {
            try {
                if (micLine.isOpen()) {
                    micLine.stop();
                    micLine.close();
                }
            } catch (Exception ignored) {
            }
            micLine = null;
        }
        clearTargets();
    }
}
