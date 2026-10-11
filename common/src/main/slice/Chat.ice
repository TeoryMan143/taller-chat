module ChatApp {

    exception ChatException {
        string reason;
    };

    exception NicknameInUseException extends ChatException {
    };

    exception UserNotFoundException extends ChatException {
    };

sequence<string> StringSeq;
sequence<byte> ByteSeq;

struct FileMetadata {
    string fileId;
    string fileName;
    long totalSize;
    int totalChunks;
};

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
};

    //Aqui salen los RFs
    interface ChatRoom {

        //RF1: sesion y presencia, devuelve los usuarios ya conectados
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

        // RF4
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
    };
};