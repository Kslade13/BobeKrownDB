package dev.bobekrown.db.protocol;

import dev.bobekrown.db.engine.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandProcessorTest {

    @TempDir
    Path dir;

    @Test
    void basicCommands() throws IOException {
        try (StorageEngine db = new StorageEngine(dir)) {
            CommandProcessor p = new CommandProcessor(db);

            assertEquals("OK", p.execute("SET greeting hello world"));
            assertEquals("hello world", p.execute("get greeting"), "commands are case-insensitive");
            assertEquals("1", p.execute("COUNT"));
            assertEquals("1", p.execute("DEL greeting"));
            assertEquals("0", p.execute("DEL greeting"));
            assertEquals(CommandProcessor.NIL, p.execute("GET greeting"));
        }
    }

    @Test
    void badInputReturnsErrors() throws IOException {
        try (StorageEngine db = new StorageEngine(dir)) {
            CommandProcessor p = new CommandProcessor(db);

            assertTrue(p.execute("SET onlykey").startsWith("ERR"));
            assertTrue(p.execute("GET").startsWith("ERR"));
            assertTrue(p.execute("FLY away").startsWith("ERR"));
            assertTrue(p.execute("   ").startsWith("ERR"));
        }
    }
}
