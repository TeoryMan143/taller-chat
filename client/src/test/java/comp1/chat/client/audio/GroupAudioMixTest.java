package comp1.chat.client.audio;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Tres "participantes" envian por UDP real a un receptor: debe sonar la suma, no frames intercalados. */
class GroupAudioMixTest {

    private static void send(DatagramSocket from, int port, int seq, int value) throws Exception {
        byte[] pcm = AudioMixerTest.constant(value);
        byte[] data = new byte[AudioConfig.HEADER_SIZE + pcm.length];
        ByteBuffer.wrap(data).putInt(0, seq);
        System.arraycopy(pcm, 0, data, AudioConfig.HEADER_SIZE, pcm.length);
        from.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
    }

    @Test
    void mixesSimultaneousSpeakersIntoSingleStream() throws Exception {
        List<byte[]> played = new CopyOnWriteArrayList<>();
        int[] values = {1000, 2000, 4000};

        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket a = new DatagramSocket();
             DatagramSocket b = new DatagramSocket();
             DatagramSocket c = new DatagramSocket()) {

            AudioReceiver receiver = new AudioReceiver(rx, played::add);
            receiver.start();

            DatagramSocket[] speakers = {a, b, c};
            for (int seq = 0; seq < 40; seq++) {
                for (int i = 0; i < speakers.length; i++) {
                    send(speakers[i], rx.getLocalPort(), seq, values[i]);
                }
                Thread.sleep(AudioConfig.FRAME_MILLIS);
            }
            Thread.sleep(300);
            assertEquals(3, receiver.getActiveSourceCount());
            receiver.stop();
        }

        Set<Integer> subsetSums = Set.of(0, 1000, 2000, 3000, 4000, 5000, 6000, 7000);
        long fullMix = played.stream().filter(f -> AudioMixerTest.sample(f, 0) == 7000).count();
        assertTrue(fullMix >= 20, "se esperaban >=20 frames con las 3 voces mezcladas, hubo " + fullMix);

        for (byte[] frame : played) {
            int first = AudioMixerTest.sample(frame, 0);
            assertTrue(subsetSums.contains(first), "muestra inesperada " + first);
            for (int i = 1; i < frame.length / 2; i++) {
                assertEquals(first, AudioMixerTest.sample(frame, i), "frame con muestras de fuentes distintas intercaladas");
            }
        }
    }
}
