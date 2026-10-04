//Este main salio del otro taller lmao

package comp1.chat.server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

public class ServerMain {
    public static void main(String[] args) {
        int exitCode = 0;

        try (Communicator com = Util.initialize(args)) {
            ObjectAdapter adapter = com.createObjectAdapterWithEndpoints("ChatAdapter", "default -h 127.0.0.1 -p 10000");
            ChatRoomImp serv = new ChatRoomImp();

            adapter.add(serv, Util.stringToIdentity("ChatService"));
            adapter.activate();

            System.out.println(" ================================================= ");
            System.out.println(" SERVIDOR ZEROC ICE INITIALIZED SUCCESSFULLY ");
            System.out.println(" TCP Port : 10000 | Endpoint : default -p 10000 ");
            System.out.println(" Service Identity : ChatService ");
            System.out.println(" ================================================= ");
            System.out.println(" Waiting for connection from the clients... ");

            com.waitForShutdown();
        } catch (Exception e) {
            System.err.println("[SERVER ERROR] Critical error" + e.getMessage());
            e.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}

//LISTA DE COMANDOS!!!1!!!1!!!!111
/** /help = Muestra todos los comandos disponibles
 * /users = Lista de usuarios conectados
 * /msg <usuario> <texto> = Envia un mensaje hacia UNA sola persona
 * /rooms = lista de salas disponibles para charlar
 * /create <sala> = crea una sala (entra de una en ella)
 * /join <sala> = te unes a una sala
 * /leave <sala> = sales de una sala
 * /quit = te vas, logout y cierra cliente
 */
