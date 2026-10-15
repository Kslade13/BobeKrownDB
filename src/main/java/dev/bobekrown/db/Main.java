package dev.bobekrown.db;

import dev.bobekrown.db.client.DbClient;
import dev.bobekrown.db.engine.StorageEngine;
import dev.bobekrown.db.protocol.CommandProcessor;
import dev.bobekrown.db.server.DbServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Command-line entry point. Three ways to run it:
 *
 * <pre>
 * java -jar target/bobekrowndb.jar [dataDir]                  local shell, no network
 * java -jar target/bobekrowndb.jar serve [dataDir] [port]     start the TCP server
 * java -jar target/bobekrowndb.jar connect [host] [port]      shell that talks to a server
 * </pre>
 *
 * Defaults: dataDir {@code ./data}, host {@code localhost}, port {@value DbServer#DEFAULT_PORT}.
 */
public final class Main {

    private static final String BYTE_ORDER_MARK = "﻿";

    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "serve" -> runServer(Path.of(arg(args, 1, "data")), port(args, 2));
            case "connect" -> runClient(arg(args, 1, "localhost"), port(args, 2));
            default -> runLocal(Path.of(arg(args, 0, "data")));
        }
    }

    private static void runLocal(Path dataDir) throws IOException {
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            CommandProcessor processor = new CommandProcessor(engine);
            System.out.println("BobeKrownDB - data in " + dataDir.toAbsolutePath()
                    + " (" + engine.size() + " keys loaded). Type EXIT to quit.");
            repl(processor::execute);
        }
    }

    private static void runServer(Path dataDir, int port) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir);
        DbServer server;
        try {
            server = new DbServer(engine, port, System.out);
        } catch (BindException e) {
            engine.close();
            System.err.println("Port " + port + " is already in use. Is another BobeKrownDB server running?");
            System.exit(1);
            return;
        }
        // Ctrl+C: disconnect clients first so no command is mid-write, then close the log.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
                engine.close();
            } catch (IOException e) {
                System.err.println("shutdown failed: " + e.getMessage());
            }
        }));
        System.out.println("BobeKrownDB server - data in " + dataDir.toAbsolutePath()
                + " (" + engine.size() + " keys loaded)");
        System.out.println("Listening on 127.0.0.1:" + server.port() + ". Press Ctrl+C to stop.");
        server.serve();
    }

    private static void runClient(String host, int port) throws IOException {
        DbClient connection;
        try {
            connection = new DbClient(host, port);
        } catch (ConnectException e) {
            System.err.println("No BobeKrownDB server at " + host + ":" + port
                    + ". Start one first in another terminal: java -jar target\\bobekrowndb.jar serve");
            System.exit(1);
            return;
        }
        try (DbClient client = connection) {
            System.out.println("Connected to BobeKrownDB at " + host + ":" + port + ". Type EXIT to quit.");
            repl(client::send);
        }
    }

    /** Reads commands from the keyboard until EXIT, printing each reply. */
    private static void repl(CommandHandler handler) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("bobekrown> ");
            System.out.flush();
            String line = in.readLine();
            if (line != null && line.startsWith(BYTE_ORDER_MARK)) {
                line = line.substring(1); // some shells (e.g. PowerShell) prefix piped input with one
            }
            if (line == null || line.strip().equalsIgnoreCase("EXIT")) {
                break;
            }
            if (!line.isBlank()) {
                System.out.println(handler.handle(line));
            }
        }
    }

    @FunctionalInterface
    private interface CommandHandler {
        String handle(String line) throws IOException;
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    private static int port(String[] args, int index) {
        return Integer.parseInt(arg(args, index, String.valueOf(DbServer.DEFAULT_PORT)));
    }
}
