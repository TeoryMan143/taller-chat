package comp1.chat.client;

import ChatApp.ChatRoomPrx;
import ChatApp.ClientCallbackPrx;
import ChatApp.VoiceEndpoint;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;
import comp1.chat.client.audio.VoiceCallManager;
import comp1.chat.client.callback.ClientCallbackImpl;
import comp1.chat.client.transfer.FileTransferManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class ClientMain {

    private static String currentNickname = null;
    private static String currentActiveRoom = null;

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 10000;

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        if (args.length >= 1) {
            host = args[0];
        } else {
            System.out.print("IP del servidor Ice (Enter para 127.0.0.1): ");
            try {
                String inputHost = reader.readLine();
                if (inputHost != null && !inputHost.trim().isEmpty()) {
                    host = inputHost.trim();
                }
            } catch (Exception ignored) {
            }
        }
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
            }
        }

        printBanner(host, port);

        try (Communicator communicator = Util.initialize(args)) {
            String proxyStr = String.format("ChatService:default -h %s -p %d", host, port);
            ChatRoomPrx service = ChatRoomPrx.checkedCast(communicator.stringToProxy(proxyStr));

            if (service == null) {
                System.err.println("[ERROR] No se pudo obtener el proxy del servidor en: " + proxyStr);
                return;
            }

            // Adaptador para recibir callbacks asincronos del servidor Ice
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("ClientCallbackAdapter", "default");
            VoiceCallManager callManager = new VoiceCallManager(host);
            FileTransferManager fileManager = new FileTransferManager();
            ClientCallbackImpl callbackImpl = new ClientCallbackImpl(callManager, fileManager);

            ClientCallbackPrx callbackPrx = ClientCallbackPrx.uncheckedCast(adapter.addWithUUID(callbackImpl));
            adapter.activate();
            service.ice_getConnection().setAdapter(adapter);

            System.out.printf("[CLIENTE] Endpoint UDP local para streaming de voz: %s:%d%n",
                    callManager.getLocalIp(), callManager.getLocalPort());
            System.out.println("[CLIENTE] Escribe '/login <nickname>' para iniciar sesion o '/help' para ayuda.");

            // Hook para desconexion limpia al cerrar la consola (Ctrl+C o cierre de ventana)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (currentNickname != null) {
                        service.logout(currentNickname);
                    }
                    callManager.close();
                } catch (Exception ignored) {
                }
            }));

            // Bucle principal desacoplado para la lectura interactiva de comandos
            while (true) {
                System.out.print("> ");
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("/exit")) {
                    handleQuit(service, callManager);
                    break;
                }

                try {
                    handleCommand(line, service, callbackPrx, callManager, fileManager);
                } catch (Exception e) {
                    System.err.println("[ERROR] " + e.getMessage());
                }
            }

            callManager.close();
        } catch (Exception e) {
            System.err.println("[ERROR CRITICO] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleCommand(
            String line,
            ChatRoomPrx service,
            ClientCallbackPrx callbackPrx,
            VoiceCallManager callManager,
            FileTransferManager fileManager) throws Exception {

        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/login": {
                if (parts.length < 2) {
                    System.out.println("Uso: /login <nickname>");
                    return;
                }
                if (currentNickname != null) {
                    System.out.println("Ya has iniciado sesion como @" + currentNickname + ". Usa /logout primero.");
                    return;
                }
                String nick = parts[1].trim();
                String[] onlineUsers = service.login(nick, callbackPrx);
                currentNickname = nick;
                System.out.println("[LOGIN EXITOSO] Bienvenido, @" + currentNickname + "!");
                if (onlineUsers.length > 0) {
                    System.out.println("[USUARIOS CONECTADOS] " + String.join(", ", onlineUsers));
                } else {
                    System.out.println("[USUARIOS CONECTADOS] Eres el unico usuario conectado actualmente.");
                }
                break;
            }

            case "/logout": {
                requireLogin();
                if (callManager.getState() != VoiceCallManager.CallState.IDLE) {
                    callManager.stopAudio();
                }
                service.logout(currentNickname);
                System.out.println("[SESION CERRADA] Has cerrado sesion.");
                currentNickname = null;
                currentActiveRoom = null;
                break;
            }

            case "/msg": {
                requireLogin();
                if (parts.length < 3) {
                    System.out.println("Uso: /msg <usuario> <mensaje>");
                    return;
                }
                String toUser = parts[1].trim();
                String message = parts[2].trim();
                service.sendPrivateMessage(currentNickname, toUser, message);
                System.out.printf("[ENVIADO A @%s]: %s%n", toUser, message);
                break;
            }

            case "/users": {
                requireLogin();
                String[] users = service.getOnlineUsers();
                System.out.println("[USUARIOS ACTIVOS (" + users.length + ")]: " + String.join(", ", users));
                break;
            }

            case "/rooms": {
                requireLogin();
                String[] rooms = service.listRooms();
                if (rooms.length == 0) {
                    System.out.println("[SALAS] No hay salas de chat creadas. Usa /create <sala>.");
                } else {
                    System.out.println("[SALAS DISPONIBLES (" + rooms.length + ")]: " + String.join(", ", rooms));
                }
                break;
            }

            case "/create": {
                requireLogin();
                if (parts.length < 2) {
                    System.out.println("Uso: /create <sala>");
                    return;
                }
                String room = parts[1].trim();
                service.createRoom(currentNickname, room);
                currentActiveRoom = room;
                System.out.printf("[SALA] Sala '%s' creada exitosamente y seleccionada como activa.%n", room);
                break;
            }

            case "/join": {
                requireLogin();
                if (parts.length < 2) {
                    System.out.println("Uso: /join <sala>");
                    return;
                }
                String room = parts[1].trim();
                service.joinRoom(currentNickname, room);
                currentActiveRoom = room;
                System.out.printf("[SALA] Te has unido a la sala '%s'. (Sala activa)%n", room);
                break;
            }

            case "/leave": {
                requireLogin();
                String room = (parts.length >= 2) ? parts[1].trim() : currentActiveRoom;
                if (room == null) {
                    System.out.println("Uso: /leave <sala>");
                    return;
                }
                service.leaveRoom(currentNickname, room);
                if (room.equals(currentActiveRoom)) {
                    currentActiveRoom = null;
                }
                System.out.printf("[SALA] Has salido de la sala '%s'.%n", room);
                break;
            }

            case "/send":
            case "/roommsg": {
                requireLogin();
                String targetRoom;
                String message;

                if (parts.length >= 3 && !parts[1].startsWith("/")) {
                    targetRoom = parts[1].trim();
                    message = parts[2].trim();
                } else if (parts.length >= 2 && currentActiveRoom != null) {
                    targetRoom = currentActiveRoom;
                    message = line.substring(line.indexOf(' ') + 1).trim();
                } else {
                    System.out.println("Uso: /send <sala> <mensaje>  (o unete a una sala con /join y usa /send <mensaje>)");
                    return;
                }

                service.sendRoomMessage(currentNickname, targetRoom, message);
                System.out.printf("[ENVIADO A SALA #%s]: %s%n", targetRoom, message);
                break;
            }

            case "/sendfile":
            case "/file": {
                requireLogin();
                if (parts.length < 3) {
                    System.out.println("Uso: /sendfile <usuario|#sala> <ruta_archivo>");
                    return;
                }
                String dest = parts[1].trim();
                String pathStr = parts[2].trim();
                File file = new File(pathStr);

                if (!file.exists()) {
                    System.err.println("[ARCHIVO] El archivo no existe: " + pathStr);
                    return;
                }

                boolean isRoom = dest.startsWith("#");
                String actualTarget = isRoom ? dest.substring(1) : dest;
                fileManager.sendFile(service, currentNickname, actualTarget, isRoom, file);
                break;
            }

            // ==========================================
            // RF-05: Llamadas Directas 1 a 1 sobre UDP
            // ==========================================

            case "/call": {
                requireLogin();
                if (parts.length < 2) {
                    System.out.println("Uso: /call <usuario>");
                    return;
                }
                String targetUser = parts[1].trim();

                if (callManager.getState() != VoiceCallManager.CallState.IDLE) {
                    System.out.println("[LLAMADA] Ya te encuentras en una llamada o conferencia activa. Usa /hangup o /vleave.");
                    return;
                }

                callManager.setCalling(targetUser);
                service.startCall(currentNickname, targetUser, callManager.getLocalIp(), callManager.getLocalPort());
                System.out.printf("[LLAMADA] Llamando a @%s (mi endpoint UDP: %s:%d)... Esperando respuesta.%n",
                        targetUser, callManager.getLocalIp(), callManager.getLocalPort());
                break;
            }

            case "/accept": {
                requireLogin();
                if (callManager.getState() != VoiceCallManager.CallState.RINGING) {
                    System.out.println("[LLAMADA] No tienes ninguna llamada entrante pendiente por contestar.");
                    return;
                }
                String caller = callManager.getRingingCaller();
                String callerIp = callManager.getRingingCallerIp();
                int callerPort = callManager.getRingingCallerPort();

                service.acceptCall(currentNickname, caller, callManager.getLocalIp(), callManager.getLocalPort());
                callManager.start1to1Audio(caller, callerIp, callerPort);
                System.out.printf("[LLAMADA] Has aceptado la llamada de @%s. Audio UDP en curso.%n", caller);
                break;
            }

            case "/reject": {
                requireLogin();
                if (callManager.getState() != VoiceCallManager.CallState.RINGING) {
                    System.out.println("[LLAMADA] No tienes ninguna llamada entrante pendiente por rechazar.");
                    return;
                }
                String caller = callManager.getRingingCaller();
                String reason = (parts.length >= 2) ? line.substring(line.indexOf(' ') + 1).trim() : "Llamada rechazada por el usuario";

                service.rejectCall(currentNickname, caller, reason);
                callManager.stopAudio();
                System.out.printf("[LLAMADA] Rechazaste la llamada de @%s.%n", caller);
                break;
            }

            case "/hangup": {
                requireLogin();
                if (callManager.getState() == VoiceCallManager.CallState.IN_CALL ||
                        callManager.getState() == VoiceCallManager.CallState.CALLING) {
                    String peer = callManager.getCurrentPeer();
                    service.endCall(currentNickname, peer);
                    callManager.stopAudio();
                    System.out.printf("[LLAMADA] Llamada con @%s finalizada.%n", peer);
                } else if (callManager.getState() == VoiceCallManager.CallState.IN_VOICE_ROOM) {
                    System.out.println("[LLAMADA] Estas en una conferencia grupal. Usa /vleave para salir.");
                } else {
                    System.out.println("[LLAMADA] No estas en ninguna llamada activa.");
                }
                break;
            }

            // ==========================================
            // RF-06: Llamadas Grupales (Malla Mesh sobre UDP)
            // ==========================================

            case "/vjoin": {
                requireLogin();
                String room = (parts.length >= 2) ? parts[1].trim() : currentActiveRoom;
                if (room == null) {
                    System.out.println("Uso: /vjoin <sala>");
                    return;
                }
                if (callManager.getState() != VoiceCallManager.CallState.IDLE) {
                    System.out.println("[SALA DE VOZ] Ya tienes una llamada o conferencia activa. Finalizala primero.");
                    return;
                }

                VoiceEndpoint[] currentPeers = service.joinVoiceRoom(
                        currentNickname,
                        room,
                        callManager.getLocalIp(),
                        callManager.getLocalPort()
                );

                callManager.startVoiceRoomAudio(room, currentPeers);
                System.out.printf("[SALA DE VOZ #%s] Conectado a la conferencia de audio UDP (Malla Mesh).%n", room);
                if (currentPeers.length > 0) {
                    System.out.printf("[SALA DE VOZ #%s] Participantes en linea en la llamada: %d persona(s).%n",
                            room, currentPeers.length);
                } else {
                    System.out.printf("[SALA DE VOZ #%s] Eres el primer participante en la llamada de audio.%n", room);
                }
                break;
            }

            case "/vleave": {
                requireLogin();
                if (callManager.getState() != VoiceCallManager.CallState.IN_VOICE_ROOM) {
                    System.out.println("[SALA DE VOZ] No te encuentras en ninguna conferencia de voz grupal.");
                    return;
                }
                String room = callManager.getCurrentVoiceRoom();
                service.leaveVoiceRoom(currentNickname, room);
                callManager.stopAudio();
                System.out.printf("[SALA DE VOZ #%s] Has abandonado la conferencia de audio.%n", room);
                break;
            }

            case "/mute": {
                requireLogin();
                callManager.setMute(true);
                System.out.println("[AUDIO] Microfono SILENCIADO (Mute). No se transmiten paquetes UDP.");
                break;
            }

            case "/unmute": {
                requireLogin();
                callManager.setMute(false);
                System.out.println("[AUDIO] Microfono ACTIVADO (Unmute). Transmitiendo audio normalmente.");
                break;
            }

            case "/status": {
                System.out.println("----------------------------------------------");
                System.out.println("ESTADO DEL CLIENTE:");
                System.out.println("  Usuario      : " + (currentNickname != null ? "@" + currentNickname : "(No logueado)"));
                System.out.println("  Sala activa  : " + (currentActiveRoom != null ? "#" + currentActiveRoom : "(Ninguna)"));
                System.out.println("  Estado voz   : " + callManager.getState());
                System.out.println("  En llamada con: " + (callManager.getCurrentPeer() != null ? callManager.getCurrentPeer() : "N/A"));
                System.out.println("  Sala de voz  : " + (callManager.getCurrentVoiceRoom() != null ? callManager.getCurrentVoiceRoom() : "N/A"));
                System.out.println("  Microfono    : " + (callManager.isMuted() ? "SILENCIADO (MUTED)" : "ACTIVO"));
                System.out.println("  Endpoint UDP : " + callManager.getLocalIp() + ":" + callManager.getLocalPort());
                System.out.println("----------------------------------------------");
                break;
            }

            case "/help": {
                printHelp();
                break;
            }

            default: {
                System.out.printf("Comando desconocido '%s'. Escribe /help para ver la lista de comandos.%n", cmd);
                break;
            }
        }
    }

    private static void requireLogin() {
        if (currentNickname == null) {
            throw new IllegalStateException("Debes iniciar sesion primero con /login <nickname>");
        }
    }

    private static void handleQuit(ChatRoomPrx service, VoiceCallManager callManager) {
        if (currentNickname != null) {
            try {
                if (callManager.getState() == VoiceCallManager.CallState.IN_VOICE_ROOM) {
                    service.leaveVoiceRoom(currentNickname, callManager.getCurrentVoiceRoom());
                } else if (callManager.getState() == VoiceCallManager.CallState.IN_CALL) {
                    service.endCall(currentNickname, callManager.getCurrentPeer());
                }
                service.logout(currentNickname);
            } catch (Exception ignored) {
            }
        }
        callManager.close();
        System.out.println("[CLIENTE] Desconectado. Hasta pronto!");
    }

    private static void printBanner(String host, int port) {
        System.out.println("===============================================================");
        System.out.println("  CLIENTE DE CHAT MULTIMEDIA Y VOZ UDP (ZeroC Ice + Java)     ");
        System.out.printf("  Servidor objetivo : %s:%d%n", host, port);
        System.out.println("===============================================================");
    }

    private static void printHelp() {
        System.out.println("======================= COMANDOS DISPONIBLES =======================");
        System.out.println("  /login <nick>            - Inicia sesion en la plataforma");
        System.out.println("  /logout                  - Cierra tu sesion actual");
        System.out.println("  /users                   - Lista usuarios conectados en tiempo real");
        System.out.println("  /msg <user> <texto>      - Envia un mensaje privado a un usuario");
        System.out.println("  /rooms                   - Lista las salas de chat disponibles");
        System.out.println("  /create <sala>           - Crea una nueva sala de chat grupal");
        System.out.println("  /join <sala>             - Te une a una sala de chat grupal");
        System.out.println("  /leave [sala]            - Abandona una sala de chat");
        System.out.println("  /send [sala] <texto>     - Envia un mensaje a la sala de chat");
        System.out.println("  /sendfile <target> <ruta>- Envia un archivo (ej: /sendfile juan doc.pdf o /sendfile #general foto.png)");
        System.out.println("  --- COMANDOS DE LLAMADAS DE VOZ UDP (RF-05 y RF-06) ---");
        System.out.println("  /call <usuario>          - Inicia una llamada de voz 1 a 1 sobre UDP");
        System.out.println("  /accept                  - Contesta una llamada entrante");
        System.out.println("  /reject [motivo]         - Rechaza una llamada entrante");
        System.out.println("  /hangup                  - Cuelga / finaliza la llamada 1 a 1");
        System.out.println("  /vjoin [sala]            - Te une a la conferencia de audio grupal UDP");
        System.out.println("  /vleave                  - Abandona la conferencia de audio grupal");
        System.out.println("  /mute                    - Silencia tu microfono");
        System.out.println("  /unmute                  - Reactiva tu microfono");
        System.out.println("  --- UTILIDADES ---");
        System.out.println("  /status                  - Consulta tu estado, sala activa y estado de audio");
        System.out.println("  /help                    - Muestra este menu de ayuda");
        System.out.println("  /quit                    - Cierra sesion y finaliza la aplicacion");
        System.out.println("====================================================================");
    }
}
