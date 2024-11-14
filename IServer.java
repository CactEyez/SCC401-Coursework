import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IServer extends Remote {
    void storeTask(String taskId, String result) throws RemoteException;
    String getTaskResult(String taskId) throws RemoteException;
}