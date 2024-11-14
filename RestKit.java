import java.io.*;
import java.net.*;

enum RequestType {
    GET,
    POST
}

class HTTPHeader {
    String key;
    String value;
}

public class RestKit {

    static Web web = new Web();

    // This function reads and parses an HTTP request into a HTTPRequest object
    static HTTPRequest parseRequest(InputStream input) throws IOException {
        HTTPRequest req = new HTTPRequest();

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        byte last4[] = new byte[4];

        while ((b = input.read()) != -1) {
            buf.write(b);

            last4[0] = last4[1];
            last4[1] = last4[2];
            last4[2] = last4[3];
            last4[3] = (byte) b;

            if (last4[0] == '\r' && last4[1] == '\n' && last4[2] == '\r' && last4[3] == '\n') {
                break;
            }
        }

        if (last4[0] == '\r' && last4[1] == '\n' && last4[2] == '\r' && last4[3] == '\n') {
            String data = buf.toString();
            String parts[] = data.split("\r\n");

            // Parse the command line
            String cmd_parts[] = parts[0].split(" ");

            if (cmd_parts[0].toLowerCase().equals("get")) {
                req.type = RequestType.GET;
            } else if (cmd_parts[0].toLowerCase().equals("post")) {
                req.type = RequestType.POST;
            }

            req.resource = cmd_parts[1];
            req.headers = new HTTPHeader[parts.length - 1];

            // Parse headers
            for (int i = 1; i < parts.length; i++) {
                int ndx = parts[i].indexOf(":");
                String k = parts[i].substring(0, ndx).trim();
                String v = parts[i].substring(ndx + 1).trim();

                HTTPHeader nhdr = new HTTPHeader();
                nhdr.key = k.toLowerCase();
                nhdr.value = v;

                req.headers[i - 1] = nhdr;
            }
            return req;
        }
        return null;
    }

    static void processStream(Socket s) {
        try {
            InputStream input = s.getInputStream();
            OutputStream output = s.getOutputStream();
            InputStreamReader reader = new InputStreamReader(input);
            PrintWriter writer = new PrintWriter(output, true);

            HTTPRequest req = parseRequest(input);
            if (req != null) {
                if (req.type == RequestType.GET) {
                    output.write("HTTP/1.1 200 OK\r\n".getBytes());
                    web.get(req, output);
                } else if (req.type == RequestType.POST) {
                    int plen = Integer.parseInt(req.getHeaderValue("content-length"));
                    byte payload[] = new byte[plen];
                    input.read(payload, 0, plen);
                    web.post(req, payload, output);
                }
            }
        } catch (IOException e) {
            System.out.println("I/O error on socket receive");
        }

        try {
            s.close();
        } catch (IOException e) {
        }
    }

    public static void main(String args[]) throws IOException {
        ServerSocket serverSocket = new ServerSocket(8000);
        while (true) {
            Socket socket = serverSocket.accept();
            processStream(socket);
        }
    }
}