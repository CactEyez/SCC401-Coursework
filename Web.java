import java.io.*;
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
            // e.printStackTrace();
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
        response += "<option value=\"task_1\">Task 1</option>";
        response += "<option value=\"task_2\">Task 2</option>";
        response += "<option value=\"task_3\">Task 3</option>";
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

            List<String> taskIdsCompleted = new ArrayList<>();
            List<String> taskIdsQueued = new ArrayList<>();

            // Loop through all registered ChordNode objects and collect task IDs
            for (String name : names) {
                if (name.startsWith("IChordNode_")) {
                    try {
                        IChordNode node = (IChordNode) registry.lookup(name); // Lookup each ChordNode
                        taskIdsCompleted.addAll(node.getCompletedTaskIds()); // Collect all task IDs from this node
                        for (String taskId : taskIdsCompleted) {
                            System.out.println("Found completed task: " + taskId + " on Node: " + name);
                        }
                        taskIdsQueued.addAll(node.getQueuedTaskIds()); // Collect all task IDs from this node
                        for (String taskId : taskIdsQueued) {
                            System.out.println("Found queued task: " + taskId + " on Node: " + name);
                        }
                    } catch (Exception e) {
                        System.err.println("Error looking up " + name + ": " + e.getMessage());
                    }
                }
            }

            // Start HTML content for the download page
            String html = "<html><body>";
            html += "<h1>Completed Tasks</h1>";

            if (taskIdsCompleted.isEmpty()) {
                html += "<p>No completed tasks available.</p>";
            } else {
                html += "<ul>";

                // Create download links for each task ID
                for (String taskId : taskIdsCompleted) {
                    html += "<li>";
                    html += "<form action=\"/download_task\" method=\"POST\">";
                    html += "<input type=\"hidden\" name=\"taskId\" value=\"" + taskId + "\" />";
                    html += "<input type=\"submit\" value=\"Download Task: " + taskId + "\" />";
                    html += "</form>";
                    html += "</li>";
                }

                html += "</ul>";
            }

            html += "<h1>Queued Tasks</h1>";
            if (taskIdsQueued.isEmpty()) {
                html += "<p>No queued tasks available.</p>";
            } else {
                html += "<ul>";

                // Create download links for each task ID
                for (String taskId : taskIdsQueued) {
                    html += "<li><p>" + taskId + "</p></li>";
                }

                html += "</ul>";
            }

            html += "<form action=\"/\" method=\"GET\">";
            html += "<input type=\"submit\" value=\"Return to Main Page\" />";
            html += "</form>";

            // End of the HTML content
            html += "</body></html>";

            // Send the response with the list of available tasks
            sendResponse(output, RESPONSE_OK, "text/html", html.getBytes());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void distributeTaskToDHT(String taskId, byte[] fileContent) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost");
            String[] names = registry.list();
            IChordNode startingNode;
            for (String name : names) {
                if (name.contains("IChordNode_")) {
                    startingNode = (IChordNode) registry.lookup(name);
                    startingNode.put(taskId, fileContent);
                    System.out.println("Uploaded file: " + taskId + " starting at node: " + startingNode.getKey());
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    byte[] retrieveTaskFromDHT(String taskId) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost");
            String[] names = registry.list();
            IChordNode startingNode;
            byte[] retrievedTask = null;
            for (String name : names) {
                if (name.contains("IChordNode_")) {
                    startingNode = (IChordNode) registry.lookup(name);
                    retrievedTask = startingNode.get(taskId);
                    System.out.println("Finding file: " + taskId + " starting at node: " + startingNode.getKey());
                    return retrievedTask;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
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
                byte[] fileData = null;
                for (int i = 0; i < data.fields.length; i++) {
                    if (data.fields[i].name.equals("task")) {
                        // Handle regular form field (task selection)
                        task = new String(data.fields[i].content); // Convert byte array to String
                        System.out.println("Selected task: " + task);
                    } else if (data.fields[i].name.equals("content")) {
                        // Handle file upload field
                        filename = ((FileFormField) data.fields[i]).filename;
                        fileData = data.fields[i].content;
                    }
                }
                if (filename != null && task != null && fileData != null) {
                    distributeTaskToDHT(task + "-" + filename, fileData);
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
        else if (request.resource.contains("/download_task")) {
            if (request.getHeaderValue("content-type") != null
                && request.getHeaderValue("content-type").startsWith("application/x-www-form-urlencoded")) {
                
                String body = new String(payload);
                String[] pairs = body.split("&");
                String taskId = null;
                
                // Extract taskId from the form data
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=");
                    if (keyValue.length == 2 && keyValue[0].equals("taskId")) {
                        taskId = keyValue[1];
                    }
                }
        
                // Check if taskId was found
                if (taskId != null) {
                    byte[] fileData = retrieveTaskFromDHT(taskId);  // Retrieve the file data from DHT
        
                    if (fileData != null) {
                        // Set the file name to taskId.xml
                        String fileName = taskId.split("[.]")[0] + ".xml";
        
                        // Set the response headers for file download
                        // Send the HTTP headers for the download:
                        try{
                            output.write("HTTP/1.1 200 OK\r\n".getBytes());
                            output.write("Content-Type: application/xml\r\n".getBytes());
                            output.write(("Content-Disposition: attachment; filename=\"" + fileName + "\"\r\n").getBytes());
                            output.write("Connection: close\r\n".getBytes());
                            output.write("\r\n".getBytes());  // End of headers
            
                            // Send the actual file content (XML) to the client:
                            output.write(fileData);  // This sends the file content as a download
                            output.flush();  // Make sure the file content is sent out
                        }catch(Exception e) {System.out.println("Failed the output stuff");}
        
                        System.out.println("File downloaded as: " + fileName);
                    } else {
                        // If the task wasn't found, notify the user
                        sendResponse(output, RESPONSE_NOT_FOUND, "text/html", "<html>Task not found</html>".getBytes());
                    }
                } else {
                    // If taskId is invalid or not provided, notify the user
                    sendResponse(output, RESPONSE_NOT_FOUND, "text/html", "<html>Invalid task request.</html>".getBytes());
                }
            } else {
                sendResponse(output, RESPONSE_SERVER_ERROR, null, null);
            }
        }
    }
}