import java.io.*;
import java.nio.channels.FileChannel;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

class HTTPRequest {
    RequestType type;
    String resource;
    HTTPHeader headers[];

    String getHeaderValue(String key) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].key.equals(key))
                return headers[i].value;
        }

        return null;
    }
}

public class Web {

    static int RESPONSE_OK = 200;
    static int RESPONSE_NOT_FOUND = 404;
    static int RESPONSE_SERVER_ERROR = 501;

    FormMultipart formParser = new FormMultipart();

    private void sendResponse(OutputStream output, int responseCode, String contentType, byte content[]) {
        try {
            output.write(new String("HTTP/1.1 " + responseCode + "\r\n").getBytes());
            output.write("Server: Kitten Server\r\n".getBytes());
            if (content != null)
                output.write(new String("Content-length: " + content.length + "\r\n").getBytes());
            if (contentType != null)
                output.write(new String("Content-type: " + contentType + "\r\n").getBytes());
            output.write(new String("Connection: close\r\n").getBytes());
            output.write(new String("\r\n").getBytes());

            if (content != null)
                output.write(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void page_index(OutputStream output) {
        String response = "";
        response += "<html>";
        response += "<body>";
        response += "<h1>Welcome to the Task Submission System</h1>";
        response += "<ul>";
        response += "<li><a href=\"/upload\">Upload Task</a></li>";
        response += "<li><a href=\"/download\">Download Task Results</a></li>";
        response += "</ul>";
        response += "</body>";
        response += "</html>";

        sendResponse(output, RESPONSE_OK, "text/html", response.getBytes());
    }

    void page_upload(OutputStream output) {
        String response = "";
        response += "<html>";
        response += "<body>";
        response += "<h1>Upload Your Task</h1>";
        response += "<form action=\"/upload_do\" method=\"POST\" enctype=\"multipart/form-data\">";

        // Dropdown menu for selecting task
        response += "<label for=\"task\">Select Task:</label>";
        response += "<select name=\"task\" required>";
        response += "<option value=\"1\">Task 1</option>";
        response += "<option value=\"2\">Task 2</option>";
        response += "<option value=\"3\">Task 3</option>";
        response += "</select><br>";

        // File upload input field
        response += "<label for=\"content\">Upload File:</label>";
        response += "<input type=\"file\" name=\"content\" required/><br>";

        // Submit button
        response += "<input type=\"submit\" value=\"Submit Task\"/>";
        response += "</form>";

        response += "</body>";
        response += "</html>";

        sendResponse(output, RESPONSE_OK, "text/html", response.getBytes());
    }

    void page_download(OutputStream output) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost");
            String[] names = registry.list();
            
            List<String> taskIds = new ArrayList<>();
            
            // Loop through all registered ChordNode objects and collect task IDs
            for (String name : names) {
                if (name.startsWith("ChordNode_")) {
                    try {
                        ChordNode node = (ChordNode) registry.lookup(name); // Lookup each ChordNode
                        taskIds.addAll(node.getTaskIds()); // Collect all task IDs from this node
                    } catch (Exception e) {
                        System.err.println("Error looking up " + name + ": " + e.getMessage());
                    }
                }
            }
            
            // Start HTML content for the download page
            String html = "<html><body>";
            html += "<h1>Completed Tasks</h1>";
            
            if (taskIds.isEmpty()) {
                html += "<p>No completed tasks available.</p>";
            } else {
                html += "<ul>";
                
                // Create download links for each task ID
                for (String taskId : taskIds) {
                    html += "<li><a href='/download_task?taskId=" + taskId + "'>Download Task: " + taskId + "</a></li>";
                }
                
                html += "</ul>";
            }
            
            // End of the HTML content
            html += "</body></html>";
            
            // Send the response with the list of available tasks
            sendResponse(output, RESPONSE_OK, "text/html", html.getBytes());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void download_task(HTTPRequest request, OutputStream output) {
        // Extract the task ID from the request
        String taskId = request.resource.split("\\?")[1].split("=")[1];  // Extracts the value of taskId
    
        // Retrieve the file content from the DHT based on the taskId
        byte[] fileContent = retrieveFileFromChordDHT(taskId);
    
        // Send the file content as a response
        if (fileContent != null) {
            sendResponse(output, RESPONSE_OK, "application/octet-stream", fileContent);
        } else {
            sendResponse(output, RESPONSE_NOT_FOUND, "text/html", "<html><body>Task not found.</body></html>".getBytes());
        }
    }
    
    byte[] retrieveFileFromChordDHT(String taskId) {
        try {
            // Get the registry and list all registered ChordNode objects
            Registry registry = LocateRegistry.getRegistry("localhost");
            String[] names = registry.list();
            
            ChordNode targetNode = null;
    
            // Loop through the registry list and find the first ChordNode_ object
            for (String name : names) {
                if (name.startsWith("ChordNode_")) {
                    try {
                        // Lookup the ChordNode from the registry
                        ChordNode node = (ChordNode) registry.lookup(name);
                        
                        // Now use this node to retrieve the task (you can stop once you find a valid node)
                        targetNode = node;
                        break;  // Break after finding the first valid ChordNode
                    } catch (Exception e) {
                        System.err.println("Error looking up " + name + ": " + e.getMessage());
                    }
                }
            }
    
            // If no valid ChordNode found, return null
            if (targetNode == null) {
                System.err.println("No ChordNode found in registry.");
                return null;
            }
    
            // Now that we have a target node, retrieve the file content for the given taskId
            for (Store store : targetNode.dataStore) {
                if (store.key.equals(taskId)) {
                    return store.value;  // Return the file content
                }
            }
    
            // If no matching taskId found in the node's data store
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    

    // this function maps GET requests onto functions / code which return HTML pages
    void get(HTTPRequest request, OutputStream output) {
        for (int i = 0; i < request.headers.length; i++) {
            System.out.println(request.headers[i].key + ": " + request.headers[i].value);
        }
        System.out.println("-");
        if (request.resource.equals("/") || request.resource.equals(("/?")))
            page_index(output);
        else if (request.resource.equals("/upload"))
            page_upload(output);
        else if (request.resource.equals("/download"))
            page_download(output);
        else
            sendResponse(output, RESPONSE_NOT_FOUND, null, null);
    }

    // this function maps POST requests onto functions / code which return HTML
    // pages
    void post(HTTPRequest request, byte payload[], OutputStream output) {
        if (request.resource.equals("/upload_do")) {
            // FormMultipart
            if (request.getHeaderValue("content-type") != null
                    && request.getHeaderValue("content-type").startsWith("multipart/form-data")) {
                FormData data = formParser.getFormData(request.getHeaderValue("content-type"), payload);
                String filename = null;
                String task = null;
                for (int i = 0; i < data.fields.length; i++) {
                    System.out.println("field: " + data.fields[i].name);

                    if (data.fields[i].name.equals("content")) {
                        filename = ((FileFormField) data.fields[i]).filename;
                        System.out.println(" -- filename: " + ((FileFormField) data.fields[i]).filename);
                    }

                    if (data.fields[i].name.equals("task")) {
                        task = new String(data.fields[i].content);
                        System.out.println(" -- selected task: " + task);
                    }

                }
                String response = "";
                response += "<html>";
                response += "<body>";
                response += "<h1>Upload Complete</h1>";
                response += "<p>Task: " + task + "</p>";
                response += "<p>File Uploaded: " + filename + "</p>";
                response += "<form action=\"/\" method=\"GET\">";
                response += "<input type=\"submit\" value=\"Return to Main Page\" />";
                response += "</form>";
                response += "</body>";
                response += "</html>";

                // Send the response to the client
                sendResponse(output, RESPONSE_OK, "text/html", response.getBytes());

                sendResponse(output, RESPONSE_OK, "text/html", "<html>File sent, thanks!</html>".getBytes());
            } else {
                sendResponse(output, RESPONSE_SERVER_ERROR, null, null);
            }
        }
    }

    public void getFile(File file) {
        // Define the source directory path
        String path = "/Users/Stephen/OneDrive/Documents/SCC401/task2/lab2";

        // Create a File object for the specific file to be downloaded
        File source = new File(path, file.getName()); // Construct the full path to the file

        // Get the user's home directory and create a path for the destination
        // (Downloads folder)
        String home = System.getProperty("user.home");
        File dest = new File(home + "/Downloads/" + file.getName());

        if (!source.exists()) {
            System.out.println("Source file does not exist.");
            return;
        }

        System.out.println("Source path: " + source.getPath());
        System.out.println("Dest path: " + dest.getPath());

        // Declare channels for file transfer
        try (FileInputStream fis = new FileInputStream(source);
                FileOutputStream fos = new FileOutputStream(dest);
                FileChannel sourceChannel = fis.getChannel();
                FileChannel destChannel = fos.getChannel()) {

            // Transfer content from source to destination file
            destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());

        } catch (IOException e) {
            // Handle exception and print stack trace
            System.out.println("Error during file download: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void listFilesXML(OutputStream output) {
        String xml = "";
        sendResponse(output, RESPONSE_OK, "application/xml", xml.getBytes());
    }

}