package dev.bobekrown.db.protocol;

import dev.bobekrown.db.engine.StorageEngine;

import java.io.IOException;

/**
 * Turns one line of text like {@code SET name alice} into a call on the engine and returns
 * the reply as text. The interactive shell uses this today; the TCP server will reuse it.
 *
 * <p>Commands (case-insensitive):
 * <pre>
 * SET key value...   -> OK            (value is the rest of the line, spaces allowed)
 * GET key            -> value | (nil)
 * DEL key            -> 1 | 0         (1 if the key existed)
 * COUNT              -> number of keys
 * </pre>
 */
public final class CommandProcessor {

    public static final String NIL = "(nil)";

    private final StorageEngine engine;

    public CommandProcessor(StorageEngine engine) {
        this.engine = engine;
    }

    public String execute(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return "ERR empty command";
        }
        // Split into at most 3 parts so a SET value can contain spaces.
        String[] parts = trimmed.split("\\s+", 3);
        String command = parts[0].toUpperCase();

        try {
            return switch (command) {
                case "SET" -> {
                    if (parts.length < 3) yield "ERR usage: SET key value";
                    engine.put(parts[1], parts[2]);
                    yield "OK";
                }
                case "GET" -> {
                    if (parts.length != 2) yield "ERR usage: GET key";
                    yield engine.get(parts[1]).orElse(NIL);
                }
                case "DEL" -> {
                    if (parts.length != 2) yield "ERR usage: DEL key";
                    yield engine.delete(parts[1]) ? "1" : "0";
                }
                case "COUNT" -> String.valueOf(engine.size());
                default -> "ERR unknown command '" + parts[0] + "'";
            };
        } catch (IOException e) {
            return "ERR io: " + e.getMessage();
        }
    }
}
