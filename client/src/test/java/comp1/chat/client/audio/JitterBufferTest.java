package comp1.chat.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JitterBufferTest {

    private static byte[] frame(int id) {
        byte[] f = new byte[AudioConfig.FRAME_SIZE];
        f[0] = (byte) id;
        return f;
    }

    @Test
    void waitsForPrefillBeforePlaying() {
        JitterBuffer jb = new JitterBuffer();
        jb.offer(0, frame(1));
        jb.offer(1, frame(2));
        assertNull(jb.poll());
        jb.offer(2, frame(3));
        assertEquals(1, jb.poll()[0]);
    }

    @Test
    void reordersOutOfOrderPackets() {
        JitterBuffer jb = new JitterBuffer();
        jb.offer(2, frame(3));
        jb.offer(0, frame(1));
        jb.offer(1, frame(2));
        assertEquals(1, jb.poll()[0]);
        assertEquals(2, jb.poll()[0]);
        assertEquals(3, jb.poll()[0]);
    }

    @Test
    void lostFrameBecomesSilenceKeepingTiming() {
        JitterBuffer jb = new JitterBuffer();
        jb.offer(0, frame(1));
        jb.offer(1, frame(2));
        jb.offer(3, frame(4));
        jb.offer(4, frame(5));
        assertEquals(1, jb.poll()[0]);
        assertEquals(2, jb.poll()[0]);
        assertArrayEquals(new byte[AudioConfig.FRAME_SIZE], jb.poll()); // seq 2 perdido
        assertEquals(4, jb.poll()[0]);
    }

    @Test
    void lateFrameIsDropped() {
        JitterBuffer jb = new JitterBuffer();
        for (int i = 1; i <= 3; i++) jb.offer(i, frame(i));
        jb.poll();
        jb.poll();
        jb.offer(1, frame(99)); // ya reproducido
        jb.offer(4, frame(4));
        assertEquals(3, jb.poll()[0]);
        assertEquals(4, jb.poll()[0]);
    }

    @Test
    void underrunReprimesBuffer() {
        JitterBuffer jb = new JitterBuffer();
        for (int i = 0; i < 3; i++) jb.offer(i, frame(i + 1));
        for (int i = 0; i < 3; i++) assertNotNull(jb.poll());
        assertNull(jb.poll());              // agotado
        jb.offer(3, frame(4));
        assertNull(jb.poll());              // vuelve a llenar colchon
    }

    @Test
    void overflowDropsOldestToBoundLatency() {
        JitterBuffer jb = new JitterBuffer();
        int total = AudioConfig.MAX_BUFFER_FRAMES + 5;
        for (int i = 0; i < total; i++) jb.offer(i, frame(i));
        assertEquals(5, jb.poll()[0]);
    }

    @Test
    void senderRestartResetsStream() {
        JitterBuffer jb = new JitterBuffer();
        for (int i = 1000; i < 1003; i++) jb.offer(i, frame(1));
        jb.poll();
        for (int i = 0; i < 3; i++) jb.offer(i, frame(7)); // contador reiniciado
        assertEquals(7, jb.poll()[0]);
    }
}
