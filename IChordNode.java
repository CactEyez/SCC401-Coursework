import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface IChordNode extends Remote{
    IChordNode getSuccessor() throws RemoteException;
    IChordNode getPredecessor() throws RemoteException;
    void join(IChordNode atNode) throws RemoteException;
    List<String> getTaskIds() throws RemoteException;
    void put(String key, byte[] value) throws RemoteException;
    byte[] get(String key) throws RemoteException;
    IChordNode findSuccessor(int key) throws RemoteException;
    int getKey() throws RemoteException;
    void notify(IChordNode potentialPredecessor) throws RemoteException;
    int hash(String key) throws RemoteException;
}