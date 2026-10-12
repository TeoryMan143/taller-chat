package comp1.chat.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class AudioReceiver implements Runnable {

    private final DatagramSocket socket;
    private final AudioFormat format;
    private volatile boolean running = true;
    private SourceDataLine speakerLine;
    private Thread thread;

    public AudioReceiver(DatagramSocket socket) {
        this.socket = socket;
        this.format = AudioConfig.getFormat();
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        running = true;
        thread = new Thread(this, "AudioReceiver-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        try {
            if (AudioSystem.isLineSupported(info)) {
                speakerLine = (SourceDataLine) AudioSystem.getLine(info);
                speakerLine.open(format, AudioConfig.FRAME_SIZE * 8);
                speakerLine.start();
            } else {
                System.err.println("[AUDIO-RX] Salida de audio no soportada en este entorno.");
            }
        } catch (LineUnavailableException e) {
            System.err.println("[AUDIO-RX] Dispositivo de altavoces no disponible: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[AUDIO-RX] Advertencia al inicializar altavoces: " + e.getMessage());
        }

        byte[] buffer = new byte[AudioConfig.FRAME_SIZE * 2];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && !socket.isClosed()) {
            try {
                socket.receive(packet);

                if (speakerLine != null && speakerLine.isOpen()) {
                    speakerLine.write(packet.getData(), packet.getOffset(), packet.getLength());
                }
            } catch (SocketException se) {
                // Socket cerrado intencionalmente
                break;
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
        if (speakerLine != null) {
            try {
                if (speakerLine.isOpen()) {
                    speakerLine.drain();
                    speakerLine.stop();
                    speakerLine.close();
                }
            } catch (Exception ignored) {
            }
            speakerLine = null;
        }
    }
}
