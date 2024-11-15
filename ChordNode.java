import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.dom.DOMSource;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.Registry;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

class Finger {
	public int key;
	public IChordNode node;
}

//Store class needs to implement Serializable so that it can be transmitted across the registry
class Store implements Serializable {
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

	// I create 4 Vector<Store>s:
	// - one to track the queue of uncompleted tasks
	Vector<Store> queuedStore = new Vector<Store>();

	// - one to track all of the completed tasks
	Vector<Store> completedStore = new Vector<Store>();

	// - one to store the uncompleted tasks of its predecessor
	Vector<Store> queuedStorePred = new Vector<Store>();

	// - one to store the completed tasks of its predecessor
	// These last two are for fault tolerance for task computation and data storage
	Vector<Store> completedStorePred = new Vector<Store>();

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
	public void put(String key, byte[] value, boolean queue) {
		// find the node that should hold this key and add the key and value to that
		// node's local store
		try {
			//Hashes the task Id so that it can be compared to other node keys
			int keyHash = hash(key);
			IChordNode keyNode = findSuccessor(keyHash);

			// put can stop looping once the task is in the correct node
			if (keyNode.getKey() == this.getKey()) {
				Store newStore = new Store();
				newStore.key = key;
				newStore.value = value;
				
				// simple if statement to change between updating the queued store and the completed store
				// (only necessary for the fault tolerance - usually tasks get moved straight from queuedStore into completedStore
				if(queue) {queuedStore.add(newStore);}
				else {completedStore.add(newStore);}

				System.out.println("/----------/\nStored: " + key);
			} else {
				// if the task is not at the correct node, it is sent onto the next successor
				this.successor.put(key, value, queue);
				System.out.println("/----------/\nKey sent to successor node: " + this.successor.getKey());
			}
		} catch (Exception e) {
			e.printStackTrace();
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

			// if the requested task is at the current node, the loop stops
			if (keyNode.getKey() == this.getKey()) {

				// Iterate through all stores
				for(Store store: completedStore) {
					if(store.key.equals(key)) {
						System.out.println("/----------/\nRetrieved: " + key);
						return store.value;
					}
				}
			} else {

				// requested task is not at the current node, so is sent to the successor node
				System.out.println("/----------/\nKey sent to successor node: " + this.successor.getKey());
				return this.successor.get(key);
			}
		} catch (Exception e) {
			e.printStackTrace();
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
			//When starting, the node cannot know what its predecessor is, so it is set to null
			predecessor = null;
			predecessorKey = 0;

			// seeks a successor
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

	// Simple function to check if a node of the specified key is alive
	// Necessary for successor and predecessor checks
	boolean isAlive(int key) {
		boolean chordAlive = false;
		try {
			// The registry must loaded and listed to search for the node
			Registry registry = LocateRegistry.getRegistry("localhost");
			String[] names = registry.list();

			for (String name : names) {
				// Check each regsitry item against the specified key
				if (name.equals("IChordNode_" + key)) {
					try {
						IChordNode foundNode = (IChordNode) registry.lookup(name);
						
						// test is not used anywhere else, but is required to check if foundNode exists
						// if it catches an error, we know the chord is not alive
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
			// I have decided to fix all fingers at once instead of one call at a time
			// This is for clarity in terminal
			for (int i = 0; i < KEY_BITS; i++) {
				if (isAlive(finger[i].key) || finger[i].key == 0) {
					// % (int) Math.pow(2, KEY_BITS) is required to stop overflow
					IChordNode fingerSucessor = findSuccessor(
							this.getKey() + (int) Math.pow(2, i) % (int) Math.pow(2, KEY_BITS));
					finger[i].node = fingerSucessor;
					finger[i].key = finger[i].node.getKey();
				} else {
					finger[i].node = null;
					finger[i].key = 0;
				}
			}
		} catch (Exception e) {}
	}

	// This function handles removing dead nodes from the system
	void checkPredecessor() {
		try {
			// Checks for life
			// predecessorKey is needed because using predecessor.getKey() would crash if predecessor had crashed
			if (!isAlive(predecessorKey)) {
				Registry registry = LocateRegistry.getRegistry("localhost");
				registry.unbind("IChordNode_" + predecessorKey);
				System.out.println("/----------/\nPredecessor Chord: " + predecessorKey + " has failed.");

				// predecessor values are set to null to avoid the node trying to reach nodes that dont exist
				predecessor = null;
				predecessorKey = 0;

				// if the predecessor has failed, the queued and completed tasks that were stored up 
				// get repopulated back into the system using .put
				for(Store store: queuedStorePred) {
					System.out.println("(Queued) Putting: " + store.key + " in the ring starting at: " + this.getKey());
					this.put(store.key, store.value, true);
				}
				for(Store store: completedStorePred) {
					System.out.println("(Completed) Putting: " + store.key + " in the ring starting at: " + this.getKey());
					this.put(store.key, store.value, false);
				}
			}
		} catch (Exception e) {}
	}

	// Checks if this successor is alive, mostly calls other functions and if used for clarity
	void checkSuccessor() {
		try {
			if (!isAlive(successorKey)) {
				System.out.println("/----------/\nSuccessor Chord: " + successorKey + " has failed.");
				findNewSuccessor();
			}
		} catch (Exception e) {
		}
	}

	// If a node's successor fails, a new one must be found
	void findNewSuccessor() {
		try {
			Registry registry = LocateRegistry.getRegistry("localhost");
			String[] names = registry.list();
			
			successor = this;
			successorKey = successor.getKey();
			// This if statement is for the specific scenario when there is only one node left on the system
			if (names.length == 1) {
				predecessor = this;
				predecessorKey = predecessor.getKey();
			}
		} catch (Exception e) {
		}
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

			// Checks for outstanding tasks
			try {
				checkQueue();
			} catch (Exception e) {
				e.printStackTrace();
			}
			//Reduces how much the console is spammed with node updates
			i++;
			if(i % 8 == 0){
				printDetails();
			}
		}
	}

	@Override
	// fixStores is called whenever a new task is completed or uploaded
	// it simply updates this node's predecessor task stores
	public void fixStores(Vector<Store> qStore, Vector<Store> cStore) {
		queuedStorePred = qStore;
		completedStorePred = cStore;
	}

	// This is called in the maintenance loop to maintain asynchrony
	// Checks if the queue has any outstanding tasks
	// Only takes the top task to keep asynchrony
	public void checkQueue() {
		if(queuedStore.size() != 0) {
			processTask(queuedStore.get(0));
		}
	}

	// Completes the correct task
	public void processTask(Store taskStore) {
		// Parses the type of task required
		String taskType = taskStore.key.split("[-]")[0];
		Store completedTask = null;

		// Each task has its own function for clarity
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

			// Moves the newly completed task from queue to completed
			// then calls fixStores to update the successor of the new store changes
			completedStore.add(completedTask);
			queuedStore.remove(0);
			try{successor.fixStores(queuedStore, completedStore);}catch(Exception e){}
		}
		else{queuedStore.remove(0);}
		try{successor.fixStores(queuedStore, completedStore);}catch(Exception e){}
	}

	// Logic for task1 - text analysis
	public Store completeTask1(Store taskStore) {

		//We want the final filename to be the same as the inputted file
		String fileName = taskStore.key.split("[-]")[1];
		String fileType = fileName.split("[.]")[1];

		// Error handling, checks that the input file is of the correct filetype (.txt)
		if(fileType.equals("txt")) {
			String textFile = new String(taskStore.value, StandardCharsets.UTF_8);
			String[] lines = textFile.split("\n");

			// Initialisation of required text values
			int wordTotal = 0;
			int totalLength = 0;
			ArrayList<String> words = new ArrayList<>();
			ArrayList<Integer> wordCount = new ArrayList<>();

			for(String line: lines) {
				// Removes all of the punctuation and converts the whole text to lowercase
				// Avoids the same word getting missclassified
				line = line.replaceAll("[^a-zA-Z]", "").toLowerCase();
				wordTotal += line.split(" ").length;
				for(String word: line.split(" ")) {

					// We must make sure that we arent tallying the same word
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

			// Creating the xml file
			try{
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				DocumentBuilder builder = factory.newDocumentBuilder();

				Document document = builder.newDocument();
				
				Element root = document.createElement("Task1");
				document.appendChild(root);

				// Add each task requirement separately
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

				// Creates the store for the freshly completed task
				// taskStore.key is used for the key to ensure the fileName stays consistent on the nodes
				// This is important so that it can be correctly hashed to
				Store completedTask = new Store();
				completedTask.key = taskStore.key;
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

	// Logic for task2 - Zipping a file
	public Store completeTask2(Store taskStore) {

		// We want the final filename to be the same as the input file
		String fileName = taskStore.key.split("[-]")[1];
		try {
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream);

			ZipEntry zipEntry = new ZipEntry(fileName);
			zipOutputStream.putNextEntry(zipEntry);

			zipOutputStream.write(taskStore.value, 0, taskStore.value.length);
			zipOutputStream.closeEntry();
			zipOutputStream.close();

			// Create store for newly completed task
			Store completedTask = new Store();
			completedTask.key = taskStore.key;
			completedTask.value = byteArrayOutputStream.toByteArray();

			System.out.println("/-----------/\nZipped and stored: " + completedTask.key);
			return completedTask;
		} catch (Exception e) {
			System.out.println("Failed to zip file.");
			e.printStackTrace();
		}
		return null;
	}

	// Logic for task3 - thumbnail generation
	public Store completeTask3(Store taskStore) {
		try {
			// Converts the bytes to an image
			ByteArrayInputStream bis = new ByteArrayInputStream(taskStore.value);
			BufferedImage originalImage = ImageIO.read(bis);

			// Our thumbnail should be only 100x100
			int width = 100;
			int height = 100;

			// Scales the image to the specified dimensions
			Image scaledImage = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);
			BufferedImage thumbnailImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

			// Draws the scaled image onto the thumbnail buffer
			Graphics2D g2d = thumbnailImage.createGraphics();
			g2d.drawImage(scaledImage, 0, 0, null);
			g2d.dispose();

			// Converts the image into a byte array
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ImageIO.write(thumbnailImage, "jpg", bos);

			// Create store for the completed task result
			Store completedTask = new Store();
			completedTask.key = taskStore.key;
			completedTask.value = bos.toByteArray();

			return completedTask;

		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	// Small function to show the user the current state of the current node
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
	// Called by Web.java for the download page
	// returns all completed tasks when called
	public List<String> getCompletedTaskIds() {
		List<String> taskIds = new ArrayList<>();
		if (completedStore.size() != 0) {
			System.out.println("/----------/");
		}
		for (Store store : completedStore) {
			System.out.println("Adding completed task: " + store.key);
			
			// Only the store.key is needed, as the value is gotten through the .get method when specifically downloaded
			taskIds.add(store.key);
		}
		return taskIds;
	}

	@Override
	// Called by Web.java for the download page
	// returns all queued tasks when called
	public List<String> getQueuedTaskIds() {
		List<String> taskIds = new ArrayList<>();
		if (queuedStore.size() != 0) {
			System.out.println("/----------/");
		}
		for (Store store : queuedStore) {
			System.out.println("Adding queued task: " + store.key);

			// Only the store.key is needed, as the value is gotten through the .get method when specifically downloaded
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

		Registry registry;

		// Registry is attempted to be initialised
		// If this node is the first node connecting into the system
		// it will successfully initialise the rmi system
		try{
			registry = LocateRegistry.createRegistry(1099);
			System.out.println("Initialising rmi system");
		}catch(Exception e){System.out.println("Not the first node on the system.");}

		// all nodes will lookup the registry to connect to
		try {
			ChordNode node = new ChordNode(nodename);
			registry = LocateRegistry.getRegistry("localhost");
			registry.rebind("IChordNode_" + node.getKey(), node);
			System.out.println("/----------/");
			System.out.println("Node " + node.getKey() + " bound to registry as: " + "IChordNode_" + node.getKey());
			String[] names = registry.list();
			for (String name : names) {
				// Checking to make sure only IChordNodes are processed, and the IChordNode isnt itself
				// because a node cannot join itself
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