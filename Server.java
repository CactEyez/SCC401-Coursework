import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class Server extends UnicastRemoteObject implements IServer {

    public Server() throws RemoteException {
        super();
    }

    @Override
    public void storeTask(String taskId, String result) throws RemoteException
    {

    }

    @Override
    public String getTaskResult(String taskData) throws RemoteException
    {
        return null;
    }

    public static void main(String[] args) {
        try {
            Server server = new Server();
            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("Server", server);
            System.out.println("Server started and bound to RMI registry");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}