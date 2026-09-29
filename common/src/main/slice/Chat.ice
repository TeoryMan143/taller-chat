module Chat {
    struct ChatMessage {
        long id;
        string sender;
        string text;
        string timestamp;
    };

    sequence<ChatMessage> MessageSeq;
    sequence<string> UserSeq;

    exception ChatException {
        string reason;
    };

    sequence<byte> DataSeq;

    struct ChunkMetadata {
        string id;
        string fileName;
        int totalSize;
        int chunkIndex;
        int totalChunks;
    };

    struct FileChunk {
        ChunkMetadata meta;
        DataSeq data;
    };

    exception TransferException {
        string reason;
    };

    interface ChatRoom {
        void login(string nickname) throws ChatException;
        void createGroup(string groupCode, string nickname);
        void joinGroup(string code) throws ChatException;
        void sendDirectMessage(string from, string to, string message) throws ChatException;
        void sendGroupMessage(string nickname, string groupCode, string message) throws ChatException;
        idempotent MessageSeq getPendingMessages(string nickname, string groupCode, long lastMessageId);
        idempotent UserSeq getOnlineUsers();
        idempotent UserSeq getGroupUsers(string groupCode);
        void quitGroup(string nickname, string groupCode);
        void logout(string nickname);

        // files
        void sendDirectFileChunk(string from, string to, FileChunk chunk);
        void sendGroupFileChunk(string nickname, string groupCode, FileChunk chunk);
    };
};