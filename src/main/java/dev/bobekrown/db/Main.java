package dev.bobekrown.db;

import dev.bobekrown.db.engine.StorageEngine;
import dev.bobekrown.db.protocol.CommandProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Interactive shell that talks to the engine directly (no network yet).
 *
 * <pre>
 * java -jar target/bobekrowndb.jar [dataDir]     (default dataDir: ./data)
 * </pre>
 */
public final class Main {

    private static final String BYTE_ORDER_MARK = "﻿";

    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");

        try (StorageEngine engine = new StorageEngine(dataDir);
             BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            CommandProcessor processor = new CommandProcessor(engine);
            System.out.println("BobeKrownDB - data in " + dataDir.toAbsolutePath()
                    + " (" + engine.size() + " keys loaded). Type EXIT to quit.");

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
                    System.out.println(processor.execute(line));
                }
            }
        }
    }
}
