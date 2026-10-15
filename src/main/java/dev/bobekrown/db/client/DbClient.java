package dev.bobekrown.db.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Connects to a {@link dev.bobekrown.db.server.DbServer} and sends it commands.
 *
 * <pre>
 * try (DbClient db = new DbClient("localhost", 7379)) {
 *     db.send("SET name alice");   // "OK"
 *     db.send("GET name");         // "alice"
 * }
 * </pre>
 */
public final class DbClient implements Closeable {

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;

    public DbClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** Sends one command and waits for the server's one-line reply. */
    public synchronized String send(String command) throws IOException {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a command must be a single line");
        }
        out.write(command);
        out.write('\n');
        out.flush();
        String reply = in.readLine();
        if (reply == null) {
            throw new EOFException("server closed the connection");
        }
        return reply;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            out.write("QUIT\n");
            out.flush();
        } catch (IOException e) {
            // The server is already gone; closing the socket below is all that's left.
        }
        socket.close();
    }
}
