package dev.bobekrown.db.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageEngineTest {

    @TempDir
    Path dir;

    @Test
    void putGetDelete() throws IOException {
        try (StorageEngine db = new StorageEngine(dir)) {
            db.put("name", "alice");
            assertEquals(Optional.of("alice"), db.get("name"));

            db.put("name", "bob");
            assertEquals(Optional.of("bob"), db.get("name"), "second put overwrites");

            assertTrue(db.delete("name"));
            assertEquals(Optional.empty(), db.get("name"));
            assertFalse(db.delete("name"), "deleting a missing key returns false");
        }
    }

    @Test
    void dataSurvivesRestart() throws IOException {
        try (StorageEngine db = new StorageEngine(dir)) {
            db.put("name", "alice");
            db.put("age", "22");
            db.put("city", "chicago");
            db.delete("age");
        }

        try (StorageEngine db = new StorageEngine(dir)) {
            assertEquals(Optional.of("alice"), db.get("name"));
            assertEquals(Optional.of("chicago"), db.get("city"));
            assertEquals(Optional.empty(), db.get("age"), "deletes are replayed too");
            assertEquals(2, db.size());
        }
    }

    @Test
    void manyWritesSurviveRestart() throws IOException {
        int n = 5_000;
        try (StorageEngine db = new StorageEngine(dir, false)) {
            for (int i = 0; i < n; i++) {
                db.put("key" + i, "value" + i);
            }
        }
        try (StorageEngine db = new StorageEngine(dir, false)) {
            assertEquals(n, db.size());
            assertEquals(Optional.of("value4321"), db.get("key4321"));
        }
    }

    @Test
    void rejectsEmptyKey() throws IOException {
        try (StorageEngine db = new StorageEngine(dir)) {
            assertThrows(IllegalArgumentException.class, () -> db.put("", "x"));
        }
    }
}
