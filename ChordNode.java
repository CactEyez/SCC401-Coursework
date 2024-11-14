import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;

class Finger {
	public int key;
	public IChordNode node;
}

class Store {
	String key;
	byte[] value;
}

public class ChordNode extends UnicastRemoteObject implements IChordNode, Runnable {

	static final int KEY_BITS = 8;

	// for each peer link that we have, we store a reference to the peer node plus a
	// "cached" copy of that node's key; this means that whenever we change e.g. our
	// successor reference we also set successorKey by doing successorKey =
	// successor.getKey()
	IChordNode successor;
	int successorKey;

	IChordNode predecessor;
	int predecessorKey;

	// my finger table; note that all "node" entries will initially be "null"; your
	// code should handle this
	int fingerTableLength;
	Finger finger[];
	int nextFingerFix;

	Vector<Store> dataStore = new Vector<Store>();

	// note: you should always use getKey() to get a node's key; this will make the
	// transition to RMI easier
	private int myKey;

	ChordNode(String myKeyString) throws RemoteException {
		super();
		myKey = hash(myKeyString);

		successor = this;
		successorKey = myKey;

		// initialise finger table (note all "node" links will be null!)
		finger = new Finger[KEY_BITS];
		for (int i = 0; i < KEY_BITS; i++)
			finger[i] = new Finger();
		fingerTableLength = KEY_BITS;

		this.printDetails();

		// start up the periodic maintenance thread
		new Thread(this).start();
	}

	// -- API functions --
	@Override
	public void put(String key, byte[] value) {
		// find the node that should hold this key and add the key and value to that
		// node's local store
	}

	@Override
	public byte[] get(String key) {
		// find the node that should hold this key, request the corresponding value from
		// that node's local store, and return it

		return null;
	}

	// -- state utilities --

	@Override
	public int getKey() {
		return myKey;
	}

	@Override
	public IChordNode getSuccessor() {
		return successor;
	}

	@Override
	public IChordNode getPredecessor() {
		return predecessor;
	}

	// -- topology management functions --
	@Override
	public void join(IChordNode atNode) {
		try{
			predecessor = null;
			predecessorKey = 0;
			successor = atNode.findSuccessor(this.getKey());
			successorKey = successor.getKey();
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}

	// -- utility functions --
	@Override
	public IChordNode findSuccessor(int key) {
		System.out.println("Finding successor of key: " + key);
		try{
			if (isInHalfOpenRangeR(key, this.getKey(), successorKey) && isAlive(successorKey)) {
				System.out.println("successor is: " + successorKey);
				return successor;
			} else {
				if (closestPrecedingNode(key) == this) {
					return this;
				} else {
					return closestPrecedingNode(key).findSuccessor(key);
				}
				//System.out.println("successor is: " + closestPrecedingNode(key).findSuccessor(key).getKey());
				//return closestPrecedingNode(key).findSuccessor(key);
			}
		}catch(Exception e)
		{
			System.out.println("Find successor failed for node: " + key);
			//e.printStackTrace();
			return null;
		}
	}

	IChordNode closestPrecedingNode(int key) {
		System.out.println("Finding closest preceding node to: " + key);
		for (int i = KEY_BITS - 1; i >= 0; i--) {
			if (finger[i].node != null && isInOpenRange(finger[i].key, this.getKey(), key) && isAlive(finger[i].key)) {
				System.out.println("Closest node: " + finger[i].key);
				return finger[i].node;
			}
		}
		System.out.println("Closest node is this");
		return this;
	}

	boolean isAlive(int key)
	{
		boolean chordAlive = false;
		try{
			Registry registry = LocateRegistry.getRegistry("localhost");
			String[] names = registry.list();

			System.out.println("Key to check is: " + key);
			System.out.println("Length of names: " + names.length);
			for (String name: names) {
				System.out.println("Checking " + name + "vs IChordNode_" + key);
				if(name.equals("IChordNode_" + key)) {
					try {
						IChordNode foundNode = (IChordNode) registry.lookup(name);
						int test = foundNode.getKey();
						chordAlive = true;
						break;
					} catch (Exception e)
					{
						System.out.println("Chord " + key + " has failed");
						chordAlive = false;
						break;
					}
				}
			}
		}catch(Exception e)
		{
			System.out.println("Alive check has failed");
		}
		return chordAlive;
	}

	// -- range check functions; they deal with the added complexity of range wraps
	// --
	// x is in [a,b] ?
	boolean isInClosedRange(int key, int a, int b) {
		if (b > a)
			return key >= a && key <= b;
		else
			return key >= a || key <= b;
	}

	// x is in (a,b) ?
	boolean isInOpenRange(int key, int a, int b) {
		if (b > a)
			return key > a && key < b;
		else
			return key > a || key < b;
	}

	// x is in [a,b) ?
	boolean isInHalfOpenRangeL(int key, int a, int b) {
		if (b > a)
			return key >= a && key < b;
		else
			return key >= a || key < b;
	}

	// x is in (a,b] ?
	boolean isInHalfOpenRangeR(int key, int a, int b) {
		if (b > a)
			return key > a && key <= b;
		else
			return key > a || key <= b;
	}

	// -- hash functions --
	// this function converts a string "s" to a key that can be used with the DHT's
	// API functions
	int hash(String s) {
		int hash = 0;

		for (int i = 0; i < s.length(); i++)
			hash = hash * 31 + (int) s.charAt(i);

		if (hash < 0)
			hash = hash * -1;

		return hash % ((int) Math.pow(2, KEY_BITS));
	}

	// -- maintenance --
	@Override
	public void notify(IChordNode potentialPredecessor) {
		System.out.println("Starting notify");
		try{
			System.out.println("Potential Predecessor is: " + potentialPredecessor.getKey());
			if (predecessor == null || isInOpenRange(potentialPredecessor.getKey(), predecessorKey, this.getKey())) {
				predecessor = potentialPredecessor;
				predecessorKey = predecessor.getKey();
				System.out.println("predecessor set to: " + predecessorKey);
			}
		}catch(Exception e)
		{
			System.out.println("Notify failed");
			//e.printStackTrace();
		}
	}

	void stabilise() {
		try{
			System.out.println("Starting stabilise - successor key: " + successorKey);
			System.out.println("Successor predecessor is: " + successor.getPredecessor().getKey());
			IChordNode x = successor.getPredecessor();
			if (x != null) {
				if (isInOpenRange(x.getKey(), this.getKey(), successorKey)) {
					successor = x;
					successorKey = successor.getKey();
					System.out.println("Successor set to: " + successorKey);
				}
			}
			// System.out.println("Successor node: " + successorKey + " notifies node: " +
			// this.getKey());
		}
		catch(Exception e)
		{
			//e.printStackTrace();
			System.out.println("Stabilise failed");
		}
		System.out.println("Trying to notify");
		try {
			successor.notify(this);
		} catch (Exception e) {
			System.out.println("Failed to start notify");
		}
	}

	void fixFingers() {
		/*
		try{
			nextFingerFix = nextFingerFix + 1;
			if (nextFingerFix > KEY_BITS - 1) {
				nextFingerFix = 0;
			}
			IChordNode fingerSucessor = findSuccessor(
					this.getKey() + (int) Math.pow(2, nextFingerFix) % (int) Math.pow(2, KEY_BITS));
			finger[nextFingerFix].key = fingerSucessor.getKey();
			finger[nextFingerFix].node = fingerSucessor;
		}
		catch(Exception e)
		{
			//e.printStackTrace();
			System.out.println("Fixing fingers failed");
		}
		*/
		try{
			for(int i = 0; i < KEY_BITS; i++)
			{
				if(isAlive(finger[i].key) || finger[i].key == 0)
				{		
					System.out.println("Checking finger " + i);
					IChordNode fingerSucessor = findSuccessor(this.getKey() + (int)Math.pow(2, i) % (int) Math.pow(2, KEY_BITS));
					System.out.println("finger sucessor " + i + ": " + fingerSucessor.getKey());
					finger[i].node = fingerSucessor;
					finger[i].key = finger[i].node.getKey();
				}
				else{
					finger[i].node = null;
					finger[i].key = 0;
				}
			}
		} catch(Exception e)
		{
			System.out.println(("Fixing all fingers failed"));
		}
	}

	void checkPredecessor() {
		try{
			if(isAlive(predecessorKey))
			{
				System.out.println("Predecessor is alive");
			}
			else{
				Registry registry = LocateRegistry.getRegistry("localhost");
				registry.unbind("IChordNode_" + predecessorKey);
				predecessor = null;
				predecessorKey = 0;
				System.out.println("Predecessor failed and set to null");
			}
		}catch(Exception e)
		{
			System.out.println("Predecessor check failed");
		}
	}

	void checkSuccessor(){
		try{
			if(isAlive(successorKey))
			{
				System.out.println("Successor is alive");
			}
			else{
				findNewSuccessor();
			}
		} catch(Exception e)
		{
			System.out.println("failed successor check");
		}
	}

	void findNewSuccessor()
	{
		try{
			Registry registry = LocateRegistry.getRegistry("localhost");
			String[] names = registry.list();

			for(String name: names) {
				if(name.contains("IChordNode_") && !name.equals("IChordNode_" + this.getKey())) {
					this.successor = (IChordNode) registry.lookup(name);
					this.successorKey = this.successor.getKey();
				}
			}
		}catch(Exception e)
		{
			System.out.println("Could not get a fresh successor");
		}
	}

	void checkDataMoveDown() {
		// if I'm storing data that my current predecessor should be holding, move it
	}

	public void run() {
		while (true) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				System.out.println("Interrupted");
			}

			try {
				stabilise();
			} catch (Exception e) {
				e.printStackTrace();
			}

			try {
				fixFingers();
			} catch (Exception e) {
				e.printStackTrace();
			}

			try {
				checkPredecessor();
			} catch (Exception e) {
				e.printStackTrace();
			}

			try {
				checkSuccessor();
			} catch (Exception e) {
				e.printStackTrace();
			}

			try {
				checkDataMoveDown();
			} catch (Exception e) {
				e.printStackTrace();
			}
			printDetails();
		}
	}

	public void printDetails() {
		System.out.println("This key: " + this.getKey());
		System.out.println("This successor: " + successorKey);
		System.out.println("This predecessor: " + predecessorKey);
		for (int i = 0; i < KEY_BITS; i++) {
			System.out.println("Finger " + i + ": " + finger[i].key);
		}
	}

	@Override
	public List<String> getTaskIds() {
		List<String> taskIds = new ArrayList<>();
		for (Store store : dataStore) {
			taskIds.add(store.key); // Add the key (task ID) for each stored task
		}
		return taskIds;
	}

	public static void main(String args[]) {
		if (args.length != 1) {
			System.out.println("Usage: java IChordNode nodename");
			return;
		}

		String nodename = args[0];

		try {
			ChordNode node = new ChordNode(nodename);
			Registry registry = LocateRegistry.getRegistry("localhost");
			registry.rebind("IChordNode_" + node.getKey(), node);
			System.out.println("Node " + node.getKey() + " bound to registry as: " + "IChordNode_" + node.getKey());

			String[] names = registry.list();
			for (String name : names) {
				if (name.contains("IChordNode_") && !name.equals("IChordNode_" + node.hash(nodename))) {
					IChordNode foundNode = (IChordNode) registry.lookup(name);
					node.join(foundNode);
					System.out.println("Node " + node.getKey() + " joined the ring via " + name);
					node.printDetails();
					return;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}