package comp1.chat.server;

import ChatApp.ChatException;
import ChatApp.ChatRoom;
import ChatApp.ClientCallbackPrx;
import ChatApp.NicknameInUseException;
import ChatApp.UserNotFoundException;
import com.zeroc.Ice.ACMClose;
import com.zeroc.Ice.ACMHeartbeat;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Current;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

public class ChatRoomImp implements ChatRoom {

    private static final Pattern NICK_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,20}$");
    private static final Pattern ROOM_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,20}$");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static class Session {
        final String nickname;
        final ClientCallbackPrx callback;
        final Connection connection;

        Session(String nickname, ClientCallbackPrx callback, Connection connection) {
            this.nickname = nickname;
            this.callback = callback;
            this.connection = connection;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> rooms = new ConcurrentHashMap<>();

    private static String now() {
        return LocalTime.now().format(TIME_FORMATTER);
    }

    private static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim();
    }

    private Session requireSession(String nickname, Current current) throws ChatException {
        if (nickname == null) {
            throw new ChatException("Access denied: invalid session");
        }

        String cleanNick = nickname.trim();
        Session session = sessions.get(cleanNick);

        if (session == null) {
            throw new ChatException("Access denied: user not logged in");
        }

        if (!session.connection.equals(current.con)) {
            throw new ChatException("Access denied: unauthorized connection");
        }

        return session;
    }

    private static String requireValidText(String text, String errorMsg) throws ChatException {
        if (text == null) {
            throw new ChatException(errorMsg);
        }

        String clean = text.trim();
        if (clean.isEmpty()) {
            throw new ChatException(errorMsg);
        }
        return clean;
    }

    private static String requireValidName(String value, Pattern pattern, String errorMsg) throws ChatException {
        if (value == null) {
            throw new ChatException(errorMsg);
        }

        String clean = value.trim();
        if (!pattern.matcher(clean).matches()) {
            throw new ChatException(errorMsg);
        }
        return clean;
    }

    private Set<String> getExistingRoom(String roomName) throws ChatException {
        String cleanRoom = requireValidName(roomName, ROOM_PATTERN, "Invalid room name");
        Set<String> members = rooms.get(cleanRoom);

        if (members == null) {
            throw new ChatException("Room '" + cleanRoom + "' does not exist");
        }
        return members;
    }

    private void dispatch(Session target, Function<ClientCallbackPrx, CompletableFuture<Void>> action) {
        try {
            CompletableFuture<Void> future = action.apply(target.callback);
            future.whenComplete((res, ex) -> {
                if (ex != null) {
                    removeSession(target);
                }
            });
        } catch (Exception e) {
            removeSession(target);
        }
    }

    private void broadcastPresence(String nickname, boolean online) {
        for (Session other : sessions.values()) {
            if (!other.nickname.equals(nickname)) {
                dispatch(other, cb -> cb.onPresenceChangedAsync(nickname, online));
            }
        }
    }

    private void removeSession(Session session) {
        boolean removed = sessions.remove(session.nickname, session);
        if (!removed) {
            return; // Ya fue removida previamente
        }

        for (Set<String> members : rooms.values()) {
            members.remove(session.nickname);
        }

        System.out.println("[ICE - SERVER] User disconnected: " + session.nickname);
        broadcastPresence(session.nickname, false);
    }

    //RF1
    @Override
    public String[] login(String nickname, ClientCallbackPrx callback, Current current)
            throws NicknameInUseException, ChatException {

        String cleanNick = requireValidName(nickname, NICK_PATTERN,
                "Invalid nickname (letters, numbers, . - _, max 20 characters)");

        if (callback == null) {
            throw new ChatException("A callback proxy is required");
        }

        for (Session existing : sessions.values()) {
            if (existing.connection.equals(current.con)) {
                throw new ChatException("This connection is already logged in.");
            }
        }

        ClientCallbackPrx fixedCallback = callback.ice_fixed(current.con);
        Session newSession = new Session(cleanNick, fixedCallback, current.con);

        Session previous = sessions.putIfAbsent(cleanNick, newSession);
        if (previous != null) {
            throw new NicknameInUseException("Nickname '" + cleanNick + "' is already in use");
        }

        current.con.setACM(OptionalInt.of(30), Optional.of(ACMClose.CloseOnIdleForceful),
                Optional.of(ACMHeartbeat.HeartbeatOff));
        current.con.setCloseCallback(con -> removeSession(newSession));

        System.out.println("[ICE - SERVER] User connected: " + cleanNick);
        broadcastPresence(cleanNick, true);

        // Recolectar nicknames de los demás usuarios conectados
        java.util.List<String> otherUsers = new java.util.ArrayList<>();
        for (String n : sessions.keySet()) {
            if (!n.equals(cleanNick)) {
                otherUsers.add(n);
            }
        }
        return otherUsers.toArray(new String[0]);
    }

    @Override
    public void logout(String nickname, Current current) {
        try {
            Session session = requireSession(nickname, current);
            removeSession(session);
        } catch (ChatException e) {
            // Ignorar si la sesión no es válida al intentar salir
        }
    }

    @Override
    public String[] getOnlineUsers(Current current) {
        return sessions.keySet().toArray(new String[0]);
    }

    //RF2
    @Override
    public void sendPrivateMessage(String from, String to, String text, Current current)
            throws UserNotFoundException, ChatException {

        Session sender = requireSession(from, current);
        String cleanText = requireValidText(text, "Message cannot be empty");

        if (to == null) {
            throw new ChatException("Recipient cannot be empty");
        }
        String targetNick = to.trim();

        if (targetNick.equals(sender.nickname)) {
            throw new ChatException("You cannot send a private message to yourself");
        }

        Session recipient = sessions.get(targetNick);
        if (recipient == null) {
            throw new UserNotFoundException("User '" + targetNick + "' does not exist or is disconnected");
        }

        String timestamp = now();
        dispatch(recipient, cb -> cb.onPrivateMessageAsync(sender.nickname, cleanText, timestamp));
    }

    //RF3
    public void createRoom(String nickname, String room, Current current) throws ChatException {
        Session sender = requireSession(nickname, current);
        String roomName = requireValidName(room, ROOM_PATTERN, "Invalid room name");

        Set<String> members = ConcurrentHashMap.newKeySet();
        members.add(sender.nickname);

        Set<String> existingMembers = rooms.putIfAbsent(roomName, members);
        if (existingMembers != null) {
            throw new ChatException("Room '" + roomName + "' already exists");
        }
    }

    @Override
    public String[] listRooms(Current current) {
        return rooms.keySet().toArray(new String[0]);
    }

    @Override
    public void joinRoom(String nickname, String room, Current current) throws ChatException {
        Session sender = requireSession(nickname, current);
        Set<String> members = getExistingRoom(room); // Ojo aquí con el tipo si copias, asegúrate que sea Set<String>
        members.add(sender.nickname);
    }

    @Override
    public void leaveRoom(String nickname, String room, Current current) throws ChatException {
        Session sender = requireSession(nickname, current);
        String roomName = requireValidName(room, ROOM_PATTERN, "Invalid room name");
        Set<String> members = getExistingRoom(roomName);

        boolean removed = members.remove(sender.nickname);
        if (!removed) {
            throw new ChatException("You are not in room '" + roomName + "'");
        }
    }

    @Override
    public void sendRoomMessage(String nickname, String room, String text, Current current)
            throws ChatException {

        Session sender = requireSession(nickname, current);
        String cleanText = requireValidText(text, "Message cannot be empty");
        String roomName = requireValidName(room, ROOM_PATTERN, "Invalid room name");

        Set<String> members = getExistingRoom(roomName);

        if (!members.contains(sender.nickname)) {
            throw new ChatException("You are not a member of room '" + roomName + "'");
        }

        String timestamp = now();
        for (String memberNick : members) {
            if (memberNick.equals(sender.nickname)) {
                continue;
            }
            Session target = sessions.get(memberNick);
            if (target != null) {
                dispatch(target, cb -> cb.onRoomMessageAsync(roomName, sender.nickname, cleanText, timestamp));
            }
        }
    }
}