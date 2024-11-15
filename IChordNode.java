import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Vector;

// This is an interface for ChordNode to all it to connect to rmi properly
public interface IChordNode extends Remote{
    IChordNode getSuccessor() throws RemoteException;
    IChordNode getPredecessor() throws RemoteException;
    void join(IChordNode atNode) throws RemoteException;
    List<String> getCompletedTaskIds() throws RemoteException;
    List<String> getQueuedTaskIds() throws RemoteException;
    void put(String key, byte[] value, boolean queue) throws RemoteException;
    byte[] get(String key) throws RemoteException;
    IChordNode findSuccessor(int key) throws RemoteException;
    int getKey() throws RemoteException;
    void notify(IChordNode potentialPredecessor) throws RemoteException;
    int hash(String key) throws RemoteException;
    void fixStores(Vector<Store> qStore, Vector<Store> cStore) throws RemoteException;
}