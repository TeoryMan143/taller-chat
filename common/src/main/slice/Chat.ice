module ChatApp {

    exception ChatException {
        string reason;
    };

    exception NicknameInUseException extends ChatException {
    };

    exception UserNotFoundException extends ChatException {
    };

    exception CallException extends ChatException {
    };

    sequence<string> StringSeq;
    sequence<byte> ByteSeq;

    struct FileMetadata {
        string fileId;
        string fileName;
        long totalSize;
        int totalChunks;
    };

    struct VoiceEndpoint {
        string nickname;
        string ip;
        int port;
    };

    sequence<VoiceEndpoint> VoiceEndpointSeq;

    interface ClientCallback {
        void onPrivateMessage(string from, string text, string timestamp);
        void onRoomMessage(string room, string from, string text, string timestamp);
        void onPresenceChanged(string nickname, bool online);

        void onFileChunk(
            string from,
            string target,
            FileMetadata metadata,
            int chunkIndex,
            ByteSeq data
        );

        // RF-05: Senalizacion de llamadas 1 a 1
        void onIncomingCall(string caller, string callerIp, int callerPort);
        void onCallAccepted(string callee, string calleeIp, int calleePort);
        void onCallRejected(string callee, string reason);
        void onCallEnded(string from, string reason);

        // RF-06: Senalizacion de conferencias de voz grupales
        void onVoiceRoomJoined(string room, string nickname, string ip, int port);
        void onVoiceRoomLeft(string room, string nickname);
    };

    // Aqui salen los RFs
    interface ChatRoom {

        // RF1: sesion y presencia, devuelve los usuarios ya conectados
        StringSeq login(string nickname, ClientCallback* callback)
            throws NicknameInUseException, ChatException;
        void logout(string nickname);
        idempotent StringSeq getOnlineUsers();

        // RF2: Mensajeria privada
        void sendPrivateMessage(string from, string to, string text)
            throws UserNotFoundException, ChatException;

        // RF3: salas
        void createRoom(string nickname, string room) throws ChatException;
        idempotent StringSeq listRooms();
        void joinRoom(string nickname, string room) throws ChatException;
        void leaveRoom(string nickname, string room) throws ChatException;
        void sendRoomMessage(string nickname, string room, string text) throws ChatException;

        // RF4: transferencia de archivos
        void sendFileChunk(
            string from,
            string to,
            FileMetadata metadata,
            int chunkIndex,
            ByteSeq data
        ) throws UserNotFoundException, ChatException;

        void sendRoomFileChunk(
            string from,
            string room,
            FileMetadata metadata,
            int chunkIndex,
            ByteSeq data
        ) throws ChatException;

        // RF5: Senalizacion de llamadas directas 1 a 1
        void startCall(string from, string to, string ip, int port)
            throws UserNotFoundException, CallException, ChatException;

        void acceptCall(string from, string to, string ip, int port)
            throws UserNotFoundException, CallException, ChatException;

        void rejectCall(string from, string to, string reason)
            throws UserNotFoundException, CallException, ChatException;

        void endCall(string from, string to)
            throws ChatException;

        // RF6: Senalizacion de conferencias de voz grupales
        VoiceEndpointSeq joinVoiceRoom(string nickname, string room, string ip, int port)
            throws ChatException;

        void leaveVoiceRoom(string nickname, string room)
            throws ChatException;
    };
};