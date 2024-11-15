import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.dom.DOMSource;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.Registry;

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

	Vector<Store> queuedStore = new Vector<Store>();

	Vector<Store> completedStore = new Vector<Store>();

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
		try {
			int keyHash = hash(key);
			IChordNode keyNode = findSuccessor(keyHash);
			if (keyNode.getKey() == this.getKey()) {
				Store newStore = new Store();
				newStore.key = key;
				newStore.value = value;
				queuedStore.add(newStore);
				System.out.println("/----------/\nStored: " + key);
			} else {
				this.successor.put(key, value);
				System.out.println("/----------/\nKey sent to successor node: " + this.successor.getKey());
			}
		} catch (Exception e) {
		}
	}

	@Override
	public byte[] get(String key) {
		// find the node that should hold this key, request the corresponding value from
		// that node's local store, and return it
		System.out.println("At node: " + this.getKey());
		try {
			int keyHash = hash(key);
			IChordNode keyNode = findSuccessor(keyHash);
			if (keyNode.getKey() == this.getKey()) {
				for(Store store: completedStore) {
					if(store.key.equals(key)) {
						System.out.println("/----------/\nRetrieved: " + key);
						return store.value;
					}
				}
			} else {
				System.out.println("/----------/\nKey sent to successor node: " + this.successor.getKey());
				return this.successor.get(key);
			}
		} catch (Exception e) {
		}
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
		try {
			predecessor = null;
			predecessorKey = 0;
			successor = atNode.findSuccessor(this.getKey());
			successorKey = successor.getKey();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// -- utility functions --
	@Override
	public IChordNode findSuccessor(int key) {
		try {
			if (isInHalfOpenRangeR(key, this.getKey(), successorKey) && isAlive(successorKey)) {
				return successor;
			} else {
				if (closestPrecedingNode(key) == this) {
					return this;
				} else {
					return closestPrecedingNode(key).findSuccessor(key);
				}
			}
		} catch (Exception e) {
			return null;
		}
	}

	IChordNode closestPrecedingNode(int key) {
		for (int i = KEY_BITS - 1; i >= 0; i--) {
			if (finger[i].node != null && isInOpenRange(finger[i].key, this.getKey(), key) && isAlive(finger[i].key)) {
				return finger[i].node;
			}
		}
		return this;
	}

	boolean isAlive(int key) {
		boolean chordAlive = false;
		try {
			Registry registry = LocateRegistry.getRegistry("localhost");
			String[] names = registry.list();

			for (String name : names) {
				if (name.equals("IChordNode_" + key)) {
					try {
						IChordNode foundNode = (IChordNode) registry.lookup(name);
						int test = foundNode.getKey();
						chordAlive = true;
						break;
					} catch (Exception e) {
						chordAlive = false;
						break;
					}
				}
			}
		} catch (Exception e) {
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
	@Override
	public int hash(String s) {
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
		try {
			if (predecessor == null || isInOpenRange(potentialPredecessor.getKey(), predecessorKey, this.getKey())) {
				predecessor = potentialPredecessor;
				predecessorKey = predecessor.getKey();
			}
		} catch (Exception e) {
		}
	}

	void stabilise() {
		try {
			IChordNode x = successor.getPredecessor();
			if (x != null) {
				if (isInOpenRange(x.getKey(), this.getKey(), successorKey)) {
					successor = x;
					successorKey = successor.getKey();
				}
			}
		} catch (Exception e) {
		}
		try {
			successor.notify(this);
		} catch (Exception e) {
		}
	}

	void fixFingers() {
		try {
			for (int i = 0; i < KEY_BITS; i++) {
				if (isAlive(finger[i].key) || finger[i].key == 0) {
					IChordNode fingerSucessor = findSuccessor(
							this.getKey() + (int) Math.pow(2, i) % (int) Math.pow(2, KEY_BITS));
					finger[i].node = fingerSucessor;
					finger[i].key = finger[i].node.getKey();
				} else {
					finger[i].node = null;
					finger[i].key = 0;
				}
			}
		} catch (Exception e) {
		}
	}

	void checkPredecessor() {
		try {
			if (!isAlive(predecessorKey)) {
				Registry registry = LocateRegistry.getRegistry("localhost");
				registry.unbind("IChordNode_" + predecessorKey);
				predecessor = null;
				predecessorKey = 0;
				System.out.println("/----------/\nChord: " + predecessorKey + " has failed.");
			}
		} catch (Exception e) {
		}
	}

	void checkSuccessor() {
		try {
			if (!isAlive(successorKey)) {
				System.out.println("/----------/\nChord: " + successorKey + " has failed.");
				findNewSuccessor();
			}
		} catch (Exception e) {
		}
	}

	void findNewSuccessor() {
		try {
			Registry registry = LocateRegistry.getRegistry("localhost");
			String[] names = registry.list();
			if (names.length == 2) {
				successor = this;
				successorKey = successor.getKey();
				predecessor = this;
				predecessorKey = predecessor.getKey();
			}
			for (String name : names) {
				if (name.contains("IChordNode_") && !name.equals("IChordNode_" + this.getKey())) {
					this.successor = (IChordNode) registry.lookup(name);
					this.successorKey = this.successor.getKey();
				}
			}
		} catch (Exception e) {
		}
	}

	void checkDataMoveDown() {
		// if I'm storing data that my current predecessor should be holding, move it
	}

	public void run() {
		int i = 0;
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

			try {
				checkQueue();
			} catch (Exception e) {
				e.printStackTrace();
			}
			i++;
			if(i % 8 == 0){
				printDetails();
			}
		}
	}

	public void checkQueue() {
		if(queuedStore.size() != 0) {
			processTask(queuedStore.get(0));
		}
	}

	public void processTask(Store taskStore) {
		String taskType = taskStore.key.split("[-]")[0];
		Store completedTask = null;
		switch (taskType) {
			case "task_1":
				completedTask = completeTask1(taskStore);
				break;
			case "task_2":
				completedTask = completeTask2(taskStore);
				break;
			case "task_3":
				completedTask = completeTask3(taskStore);
				break;
		}
		if(completedTask != null) {
			completedStore.add(completedTask);
			queuedStore.remove(0);
		}
	}

	public Store completeTask1(Store taskStore) {
		String fileName = taskStore.key.split("[-]")[1];
		String fileType = fileName.split("[.]")[1];
		if(fileType.equals("txt")) {
			String textFile = new String(taskStore.value, StandardCharsets.UTF_8);
			String[] lines = textFile.split("\n");

			int wordTotal = 0;
			int totalLength = 0;
			ArrayList<String> words = new ArrayList<>();
			ArrayList<Integer> wordCount = new ArrayList<>();
			for(String line: lines) {
				wordTotal += line.split(" ").length;
				for(String word: line.split(" ")) {
					word = word.toLowerCase();
					if(words.contains(word)) {
						wordCount.set(words.indexOf(word), wordCount.get(words.indexOf(word)) + 1);
					}
					else {
						words.add(word);
						wordCount.add(1);
					}
					totalLength += word.length();
				}
			}
			int highest = 0;
			int highestId = 0;
			for(int wordAmount: wordCount) {
				if(wordAmount > highest) {
					highest = wordAmount;
					highestId = wordCount.indexOf(wordAmount);
				}
			}
			String mostCommonWord = words.get(highestId);
			int averageWordLength = totalLength/wordTotal;

			try{
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				DocumentBuilder builder = factory.newDocumentBuilder();

				Document document = builder.newDocument();
				
				Element root = document.createElement("Task1");
				document.appendChild(root);

				Element total = document.createElement("Total");
				total.appendChild(document.createTextNode(Integer.toString(wordTotal)));

				Element common = document.createElement("Common");
				common.appendChild(document.createTextNode(mostCommonWord));

				Element length = document.createElement("Average");
				length.appendChild(document.createTextNode(Integer.toString(averageWordLength)));

				root.appendChild(total);
				root.appendChild(common);
				root.appendChild(length);

				TransformerFactory transformerFactory = TransformerFactory.newInstance();
				Transformer transformer = transformerFactory.newTransformer();
				DOMSource source = new DOMSource(document);
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				StreamResult result = new StreamResult(outputStream);
				transformer.transform(source, result);

				Store completedTask = new Store();
				System.out.println("Filename: " + fileName);
				completedTask.key = fileName;
				completedTask.value = outputStream.toByteArray();

				return completedTask;
			}catch(Exception e) {
				System.out.println("Failed to make XML file");
				e.printStackTrace();
				return null;
			}
		}
		else {
			System.out.println("Wrong file type, should be .txt");
		}
		return null;
	}

	public Store completeTask2(Store taskStore) {
		String fileName = taskStore.key.split("[-]")[1];
		String fileType = fileName.split("[.]")[1];
		if(fileType.equals("txt")) {

		}
		else {
			System.out.println("Wrong file type, should be .txt");
		}
		return null;
	}

	public Store completeTask3(Store taskStore) {
		String fileName = taskStore.key.split("[-]")[1];
		String fileType = fileName.split("[.]")[1];
		if(fileType.equals("txt")) {

		}
		else {
			System.out.println("Wrong file type, should be .txt");
		}
		return null;
	}



	public void printDetails() {
		System.out.println("/----------/");
		System.out.println("This key: " + this.getKey());
		System.out.println("This successor: " + successorKey);
		System.out.println("This predecessor: " + predecessorKey);
		for (int i = 0; i < KEY_BITS; i++) {
			System.out.println("Finger " + i + ": " + finger[i].key);
		}
	}

	@Override
	public List<String> getCompletedTaskIds() {
		List<String> taskIds = new ArrayList<>();
		if (completedStore.size() != 0) {
			System.out.println("/----------/");
		}
		for (Store store : completedStore) {
			System.out.println("Adding completed task: " + store.key);
			taskIds.add(store.key); // Add the key (task ID) for each stored task
		}
		return taskIds;
	}

	@Override
	public List<String> getQueuedTaskIds() {
		List<String> taskIds = new ArrayList<>();
		if (queuedStore.size() != 0) {
			System.out.println("/----------/");
		}
		for (Store store : queuedStore) {
			System.out.println("Adding queued task: " + store.key);
			taskIds.add(store.key);
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
			System.out.println("/----------/");
			System.out.println("Node " + node.getKey() + " bound to registry as: " + "IChordNode_" + node.getKey());
			String[] names = registry.list();
			for (String name : names) {
				if (name.contains("IChordNode_") && !name.equals("IChordNode_" + node.hash(nodename))) {
					IChordNode foundNode = (IChordNode) registry.lookup(name);
					node.join(foundNode);
					System.out.println("/----------/");
					System.out.println("Node " + node.getKey() + " joined the ring via " + name);
					return;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}