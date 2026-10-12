package comp1.chat;

import ChatApp.ChatRoomPrx;
import ChatApp.ClientCallback;
import ChatApp.ClientCallbackPrx;
import ChatApp.FileMetadata;
import ChatApp.VoiceEndpoint;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;
import comp1.chat.server.ChatRoomImp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class VoiceCallIntegrationTest {

    private static Communicator serverCommunicator;
    private static ObjectAdapter serverAdapter;
    private static final int SERVER_PORT = 10099;

    @BeforeAll
    public static void startServer() {
        serverCommunicator = Util.initialize();
        serverAdapter = serverCommunicator.createObjectAdapterWithEndpoints(
                "TestChatAdapter",
                "default -h 127.0.0.1 -p " + SERVER_PORT
        );
        ChatRoomImp servant = new ChatRoomImp();
        serverAdapter.add(servant, Util.stringToIdentity("ChatService"));
        serverAdapter.activate();
    }

    @AfterAll
    public static void stopServer() {
        if (serverAdapter != null) {
            serverAdapter.destroy();
        }
        if (serverCommunicator != null) {
            serverCommunicator.destroy();
        }
    }

    static class MockCallback implements ClientCallback {
        CompletableFuture<String> incomingCallFuture = new CompletableFuture<>();
        CompletableFuture<VoiceEndpoint> callAcceptedFuture = new CompletableFuture<>();
        CompletableFuture<String> callEndedFuture = new CompletableFuture<>();
        CompletableFuture<VoiceEndpoint> voiceRoomJoinedFuture = new CompletableFuture<>();
        CompletableFuture<String> voiceRoomLeftFuture = new CompletableFuture<>();

        int incomingPort;
        String incomingIp;

        @Override
        public void onPrivateMessage(String from, String text, String timestamp, Current current) {}

        @Override
        public void onRoomMessage(String room, String from, String text, String timestamp, Current current) {}

        @Override
        public void onPresenceChanged(String nickname, boolean online, Current current) {}

        @Override
        public void onFileChunk(String from, String target, FileMetadata metadata, int chunkIndex, byte[] data, Current current) {}

        @Override
        public void onIncomingCall(String caller, String callerIp, int callerPort, Current current) {
            this.incomingIp = callerIp;
            this.incomingPort = callerPort;
            incomingCallFuture.complete(caller);
        }

        @Override
        public void onCallAccepted(String callee, String calleeIp, int calleePort, Current current) {
            callAcceptedFuture.complete(new VoiceEndpoint(callee, calleeIp, calleePort));
        }

        @Override
        public void onCallRejected(String callee, String reason, Current current) {}

        @Override
        public void onCallEnded(String from, String reason, Current current) {
            callEndedFuture.complete(from);
        }

        @Override
        public void onVoiceRoomJoined(String room, String nickname, String ip, int port, Current current) {
            voiceRoomJoinedFuture.complete(new VoiceEndpoint(nickname, ip, port));
        }

        @Override
        public void onVoiceRoomLeft(String room, String nickname, Current current) {
            voiceRoomLeftFuture.complete(nickname);
        }
    }

    @Test
    public void testDirectCallAndUdpAudioStreaming() throws Exception {
        try (Communicator clientCom1 = Util.initialize();
             Communicator clientCom2 = Util.initialize();
             DatagramSocket udpSocketAlice = new DatagramSocket(0);
             DatagramSocket udpSocketBob = new DatagramSocket(0)) {

            String proxyStr = "ChatService:default -h 127.0.0.1 -p " + SERVER_PORT;
            ChatRoomPrx serviceAlice = ChatRoomPrx.checkedCast(clientCom1.stringToProxy(proxyStr));
            ChatRoomPrx serviceBob = ChatRoomPrx.checkedCast(clientCom2.stringToProxy(proxyStr));

            assertNotNull(serviceAlice);
            assertNotNull(serviceBob);

            ObjectAdapter adapter1 = clientCom1.createObjectAdapterWithEndpoints("AliceAdapter", "default");
            MockCallback callbackAlice = new MockCallback();
            ClientCallbackPrx alicePrx = ClientCallbackPrx.uncheckedCast(adapter1.addWithUUID(callbackAlice));
            adapter1.activate();
            serviceAlice.ice_getConnection().setAdapter(adapter1);

            ObjectAdapter adapter2 = clientCom2.createObjectAdapterWithEndpoints("BobAdapter", "default");
            MockCallback callbackBob = new MockCallback();
            ClientCallbackPrx bobPrx = ClientCallbackPrx.uncheckedCast(adapter2.addWithUUID(callbackBob));
            adapter2.activate();
            serviceBob.ice_getConnection().setAdapter(adapter2);

            serviceAlice.login("alice", alicePrx);
            serviceBob.login("bob", bobPrx);

            int aliceUdpPort = udpSocketAlice.getLocalPort();
            int bobUdpPort = udpSocketBob.getLocalPort();

            // 1. Alice llama a Bob via Ice (RF-05)
            serviceAlice.startCall("alice", "bob", "127.0.0.1", aliceUdpPort);

            // Bob recibe onIncomingCall
            String caller = callbackBob.incomingCallFuture.get(5, TimeUnit.SECONDS);
            assertEquals("alice", caller);
            assertEquals(aliceUdpPort, callbackBob.incomingPort);

            // 2. Bob acepta la llamada reportando su socket UDP
            serviceBob.acceptCall("bob", "alice", "127.0.0.1", bobUdpPort);

            // Alice recibe onCallAccepted
            VoiceEndpoint bobEndpoint = callbackAlice.callAcceptedFuture.get(5, TimeUnit.SECONDS);
            assertEquals("bob", bobEndpoint.nickname);
            assertEquals(bobUdpPort, bobEndpoint.port);

            // 3. Flujo de Audio UDP en tiempo real entre Alice y Bob (DatagramPacket)
            byte[] audioPayload = "UDP_PCM_AUDIO_SAMPLE_DATA".getBytes();
            DatagramPacket sendPacket = new DatagramPacket(
                    audioPayload,
                    audioPayload.length,
                    InetAddress.getByName("127.0.0.1"),
                    bobUdpPort
            );
            udpSocketAlice.send(sendPacket);

            byte[] rxBuffer = new byte[256];
            DatagramPacket rxPacket = new DatagramPacket(rxBuffer, rxBuffer.length);
            udpSocketBob.setSoTimeout(3000);
            udpSocketBob.receive(rxPacket);

            byte[] receivedBytes = new byte[rxPacket.getLength()];
            System.arraycopy(rxPacket.getData(), 0, receivedBytes, 0, rxPacket.getLength());
            assertArrayEquals(audioPayload, receivedBytes);

            // 4. Finalizar llamada
            serviceAlice.endCall("alice", "bob");
            String endCaller = callbackBob.callEndedFuture.get(5, TimeUnit.SECONDS);
            assertEquals("alice", endCaller);

            // Cleanup
            serviceAlice.logout("alice");
            serviceBob.logout("bob");
        }
    }

    @Test
    public void testGroupVoiceConferenceMesh() throws Exception {
        try (Communicator clientCom1 = Util.initialize();
             Communicator clientCom2 = Util.initialize()) {

            String proxyStr = "ChatService:default -h 127.0.0.1 -p " + SERVER_PORT;
            ChatRoomPrx serviceAlice = ChatRoomPrx.checkedCast(clientCom1.stringToProxy(proxyStr));
            ChatRoomPrx serviceBob = ChatRoomPrx.checkedCast(clientCom2.stringToProxy(proxyStr));

            ObjectAdapter adapter1 = clientCom1.createObjectAdapterWithEndpoints("AliceAdapter2", "default");
            MockCallback callbackAlice = new MockCallback();
            ClientCallbackPrx alicePrx = ClientCallbackPrx.uncheckedCast(adapter1.addWithUUID(callbackAlice));
            adapter1.activate();
            serviceAlice.ice_getConnection().setAdapter(adapter1);

            ObjectAdapter adapter2 = clientCom2.createObjectAdapterWithEndpoints("BobAdapter2", "default");
            MockCallback callbackBob = new MockCallback();
            ClientCallbackPrx bobPrx = ClientCallbackPrx.uncheckedCast(adapter2.addWithUUID(callbackBob));
            adapter2.activate();
            serviceBob.ice_getConnection().setAdapter(adapter2);

            serviceAlice.login("alice2", alicePrx);
            serviceBob.login("bob2", bobPrx);

            // Crear y unirse a sala
            serviceAlice.createRoom("alice2", "salaMusica");
            serviceBob.joinRoom("bob2", "salaMusica");

            // Alice entra a la llamada de voz de la sala
            VoiceEndpoint[] initialPeers = serviceAlice.joinVoiceRoom("alice2", "salaMusica", "127.0.0.1", 45001);
            assertEquals(0, initialPeers.length, "Alice debe ser la primera en la sala de audio");

            // Bob entra a la llamada de voz
            VoiceEndpoint[] bobPeers = serviceBob.joinVoiceRoom("bob2", "salaMusica", "127.0.0.1", 45002);
            assertEquals(1, bobPeers.length, "Bob debe recibir el endpoint de Alice");
            assertEquals("alice2", bobPeers[0].nickname);
            assertEquals(45001, bobPeers[0].port);

            // Alice recibe notificacion de que Bob se unio
            VoiceEndpoint bobJoined = callbackAlice.voiceRoomJoinedFuture.get(5, TimeUnit.SECONDS);
            assertEquals("bob2", bobJoined.nickname);
            assertEquals(45002, bobJoined.port);

            // Bob sale de la conferencia de voz
            serviceBob.leaveVoiceRoom("bob2", "salaMusica");

            // Alice recibe notificacion de que Bob salio
            String bobLeft = callbackAlice.voiceRoomLeftFuture.get(5, TimeUnit.SECONDS);
            assertEquals("bob2", bobLeft);

            serviceAlice.leaveVoiceRoom("alice2", "salaMusica");
            serviceAlice.logout("alice2");
            serviceBob.logout("bob2");
        }
    }
}
