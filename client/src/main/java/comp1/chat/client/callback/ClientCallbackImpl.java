package comp1.chat.client.callback;

import ChatApp.ClientCallback;
import ChatApp.FileMetadata;
import com.zeroc.Ice.Current;
import comp1.chat.client.audio.VoiceCallManager;
import comp1.chat.client.transfer.FileTransferManager;

public class ClientCallbackImpl implements ClientCallback {

    private final VoiceCallManager callManager;
    private final FileTransferManager fileManager;

    public ClientCallbackImpl(VoiceCallManager callManager, FileTransferManager fileManager) {
        this.callManager = callManager;
        this.fileManager = fileManager;
    }

    @Override
    public void onPrivateMessage(String from, String text, String timestamp, Current current) {
        System.out.printf("%n[PRIVADO][%s] @%s: %s%n> ", timestamp, from, text);
    }

    @Override
    public void onRoomMessage(String room, String from, String text, String timestamp, Current current) {
        System.out.printf("%n[SALA #%s][%s] %s: %s%n> ", room, timestamp, from, text);
    }

    @Override
    public void onPresenceChanged(String nickname, boolean online, Current current) {
        String status = online ? "se ha CONECTADO" : "se ha DESCONECTADO";
        System.out.printf("%n[PRESENCIA] El usuario '%s' %s.%n> ", nickname, status);
    }

    @Override
    public void onFileChunk(String from, String target, FileMetadata metadata, int chunkIndex, byte[] data, Current current) {
        fileManager.onFileChunkReceived(from, target, metadata, chunkIndex, data);
    }

    // ==========================================
    // RF-05: Senalizacion de llamadas 1 a 1
    // ==========================================

    @Override
    public void onIncomingCall(String caller, String callerIp, int callerPort, Current current) {
        callManager.setRinging(caller, callerIp, callerPort);
        System.out.printf("%n[LLAMADA ENTRANTE] *** De: '%s' (IP: %s, Puerto UDP: %d) ***%n",
                caller, callerIp, callerPort);
        System.out.println("[LLAMADA ENTRANTE] Escribe '/accept' para contestar o '/reject' para rechazar.");
        System.out.print("> ");
    }

    @Override
    public void onCallAccepted(String callee, String calleeIp, int calleePort, Current current) {
        System.out.printf("%n[LLAMADA ACEPTADA] '%s' acepto la llamada. Transmision de audio UDP activa.%n", callee);
        callManager.start1to1Audio(callee, calleeIp, calleePort);
        System.out.print("> ");
    }

    @Override
    public void onCallRejected(String callee, String reason, Current current) {
        System.out.printf("%n[LLAMADA RECHAZADA] '%s' rechazo la llamada. Motivo: %s%n", callee, reason);
        callManager.stopAudio();
        System.out.print("> ");
    }

    @Override
    public void onCallEnded(String from, String reason, Current current) {
        System.out.printf("%n[LLAMADA FINALIZADA] La llamada fue finalizada (%s: %s).%n", from, reason);
        callManager.stopAudio();
        System.out.print("> ");
    }

    // ==========================================
    // RF-06: Senalizacion de llamadas grupales
    // ==========================================

    @Override
    public void onVoiceRoomJoined(String room, String nickname, String ip, int port, Current current) {
        System.out.printf("%n[SALA DE VOZ #%s] '%s' se unio a la conferencia de audio (IP: %s:%d).%n> ",
                room, nickname, ip, port);
        callManager.addVoiceRoomPeer(nickname, ip, port);
    }

    @Override
    public void onVoiceRoomLeft(String room, String nickname, Current current) {
        System.out.printf("%n[SALA DE VOZ #%s] '%s' salio de la conferencia de audio.%n> ", room, nickname);
        callManager.removeVoiceRoomPeer(nickname);
    }
}
