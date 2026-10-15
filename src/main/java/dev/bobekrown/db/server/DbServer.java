package dev.bobekrown.db.server;

import dev.bobekrown.db.engine.StorageEngine;
import dev.bobekrown.db.protocol.CommandProcessor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets other programs use the database over TCP.
 *
 * <p>The protocol is plain text, one line per message: the client sends a command such as
 * {@code SET name alice} and the server answers with exactly one line, the same reply the
 * local shell would print. {@code QUIT} ends the connection.
 *
 * <p>Each connected client gets its own virtual thread, so many clients can be connected at once.
 * They all share one {@link StorageEngine}, whose methods are synchronized, so concurrent
 * writes are applied one at a time in a well-defined order.
 *
 * <p>The server listens on the loopback address only (127.0.0.1), so programs on this machine
 * can connect but other machines on the network cannot.
 */
public final class DbServer implements Closeable {

    public static final int DEFAULT_PORT = 7379;

    private final ServerSocket serverSocket;
    private final CommandProcessor processor;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextClientId = new AtomicInteger(1);
    private final PrintStream log;

    /** Binds to {@code port} on 127.0.0.1. Port 0 picks any free port (see {@link #port()}). */
    public DbServer(StorageEngine engine, int port) throws IOException {
        this(engine, port, null);
    }

    /** Like {@link #DbServer(StorageEngine, int)}, but prints connections and commands to {@code log}. */
    public DbServer(StorageEngine engine, int port, PrintStream log) throws IOException {
        this.processor = new CommandProcessor(engine);
        this.serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        this.log = log;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Accepts connections until {@link #close()} is called. Blocks the calling thread. */
    public void serve() {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break; // close() was called
                }
                System.err.println("accept failed: " + e.getMessage());
                continue;
            }
            openSockets.add(socket);
            int clientId = nextClientId.getAndIncrement();
            try {
                clients.submit(() -> handle(socket, clientId));
            } catch (RejectedExecutionException e) {
                closeQuietly(socket); // close() ran between accept() and submit()
            }
        }
    }

    private void handle(Socket socket, int clientId) {
        log("[client " + clientId + "] connected (" + openSockets.size() + " connected)");
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.strip().equalsIgnoreCase("QUIT")) {
                    break;
                }
                String reply = processor.execute(line);
                log("[client " + clientId + "] " + line.strip() + "  ->  " + reply);
                out.write(reply);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            // The client disconnected abruptly or the server is shutting down; nothing to undo.
        } finally {
            openSockets.remove(socket);
            log("[client " + clientId + "] disconnected (" + openSockets.size() + " connected)");
        }
    }

    private void log(String message) {
        if (log != null) {
            log.println(message);
        }
    }

    /** Stops accepting, disconnects every client and waits for in-flight commands to finish. */
    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Socket socket : openSockets) {
            closeQuietly(socket); // unblocks that client's readLine()
        }
        clients.shutdown();
        try {
            clients.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Already broken; nothing more to do.
        }
    }
}
