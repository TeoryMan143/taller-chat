package comp1.chat.client.audio;

import ChatApp.VoiceEndpoint;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceCallManager {

    public enum CallState {
        IDLE,
        CALLING,       // Llamada saliente esperando respuesta
        RINGING,       // Llamada entrante timbrando
        IN_CALL,       // En llamada directa 1 a 1
        IN_VOICE_ROOM  // En conferencia de audio grupal
    }

    private final DatagramSocket udpSocket;
    private final String localIp;
    private final int localPort;

    private final AudioReceiver receiver;
    private final AudioSender sender;

    private volatile CallState state = CallState.IDLE;
    private volatile String currentPeer = null;
    private volatile String currentVoiceRoom = null;

    // Mapeo para salas de voz grupales: nickname -> VoiceEndpoint
    private final Map<String, VoiceEndpoint> groupPeers = new ConcurrentHashMap<>();

    // Informacion temporal para llamadas entrantes
    private volatile String ringingCaller = null;
    private volatile String ringingCallerIp = null;
    private volatile int ringingCallerPort = 0;

    public VoiceCallManager() throws SocketException {
        this("127.0.0.1");
    }

    public VoiceCallManager(String serverHost) throws SocketException {
        // Enlaza a un puerto UDP efimero disponible en el sistema operativo
        this.udpSocket = new DatagramSocket(0);
        this.localPort = udpSocket.getLocalPort();
        this.localIp = detectLocalIp(serverHost);

        this.receiver = new AudioReceiver(udpSocket);
        this.sender = new AudioSender(udpSocket);

        // El receptor escucha continuamente en segundo plano
        this.receiver.start();
    }

    public static String detectLocalIp() {
        return detectLocalIp("127.0.0.1");
    }

    public static String detectLocalIp(String serverHost) {
        try {
            if (serverHost != null && !serverHost.trim().isEmpty() &&
                    !serverHost.equals("127.0.0.1") && !serverHost.equalsIgnoreCase("localhost")) {
                try (DatagramSocket testSocket = new DatagramSocket()) {
                    testSocket.connect(InetAddress.getByName(serverHost), 10000);
                    String ip = testSocket.getLocalAddress().getHostAddress();
                    if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0")) {
                        return ip;
                    }
                }
            } else {
                try (DatagramSocket testSocket = new DatagramSocket()) {
                    testSocket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                    String ip = testSocket.getLocalAddress().getHostAddress();
                    if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0")) {
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
    }

    public String getLocalIp() {
        return localIp;
    }

    public int getLocalPort() {
        return localPort;
    }

    public CallState getState() {
        return state;
    }

    public String getCurrentPeer() {
        return currentPeer;
    }

    public String getCurrentVoiceRoom() {
        return currentVoiceRoom;
    }

    public String getRingingCaller() {
        return ringingCaller;
    }

    public String getRingingCallerIp() {
        return ringingCallerIp;
    }

    public int getRingingCallerPort() {
        return ringingCallerPort;
    }

    public int getActiveSourceCount() {
        return receiver.getActiveSourceCount();
    }

    public boolean isMuted() {
        return sender.isMuted();
    }

    public boolean toggleMute() {
        boolean newMute = !sender.isMuted();
        sender.setMuted(newMute);
        return newMute;
    }

    public void setMute(boolean mute) {
        sender.setMuted(mute);
    }

    // --- Manejo de estados de llamada 1 a 1 ---

    public synchronized void setCalling(String target) {
        this.state = CallState.CALLING;
        this.currentPeer = target;
    }

    public synchronized void setRinging(String caller, String callerIp, int callerPort) {
        this.state = CallState.RINGING;
        this.ringingCaller = caller;
        this.ringingCallerIp = callerIp;
        this.ringingCallerPort = callerPort;
    }

    public synchronized void start1to1Audio(String peerNick, String peerIp, int peerPort) {
        this.state = CallState.IN_CALL;
        this.currentPeer = peerNick;
        this.ringingCaller = null;

        sender.clearTargets();
        sender.addTarget(peerIp, peerPort);
        sender.start();
    }

    // --- Manejo de conferencia grupal (RF-06 Malla Mesh) ---

    public synchronized void startVoiceRoomAudio(String room, VoiceEndpoint[] initialPeers) {
        this.state = CallState.IN_VOICE_ROOM;
        this.currentVoiceRoom = room;
        this.groupPeers.clear();

        sender.clearTargets();
        if (initialPeers != null) {
            for (VoiceEndpoint ep : initialPeers) {
                groupPeers.put(ep.nickname, ep);
                sender.addTarget(ep.ip, ep.port);
            }
        }
        sender.start();
    }

    public synchronized void addVoiceRoomPeer(String nick, String ip, int port) {
        if (state == CallState.IN_VOICE_ROOM) {
            groupPeers.put(nick, new VoiceEndpoint(nick, ip, port));
            sender.addTarget(ip, port);
        }
    }

    public synchronized void removeVoiceRoomPeer(String nick) {
        if (state == CallState.IN_VOICE_ROOM) {
            VoiceEndpoint ep = groupPeers.remove(nick);
            if (ep != null) {
                sender.removeTarget(ep.ip, ep.port);
            }
        }
    }

    public synchronized void stopAudio() {
        sender.stop();
        this.state = CallState.IDLE;
        this.currentPeer = null;
        this.currentVoiceRoom = null;
        this.ringingCaller = null;
        this.groupPeers.clear();
    }

    public void close() {
        stopAudio();
        receiver.stop();
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }
}
