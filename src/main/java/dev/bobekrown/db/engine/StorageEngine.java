package dev.bobekrown.db.engine;

import dev.bobekrown.db.wal.LogRecord;
import dev.bobekrown.db.wal.WriteAheadLog;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The core of the database: stores key/value pairs and guarantees they survive restarts.
 *
 * <p>Write path: append to the write-ahead log first, then update the in-memory table.
 * Because the log is written first, anything acknowledged to the caller can be rebuilt
 * after a crash by replaying the log.
 *
 * <p>The in-memory table is a {@link TreeMap} for now (sorted, so range scans are possible).
 * A later step replaces it with a hand-written skip list and flushes it to sorted files on disk.
 */
public final class StorageEngine implements Closeable {

    static final String WAL_FILE = "wal.log";

    private final NavigableMap<String, String> memtable = new TreeMap<>();
    private final WriteAheadLog wal;

    /** Opens (or creates) a database in {@code dataDir}, syncing every write to disk. */
    public StorageEngine(Path dataDir) throws IOException {
        this(dataDir, true);
    }

    public StorageEngine(Path dataDir, boolean syncOnWrite) throws IOException {
        Files.createDirectories(dataDir);
        // Rebuild the in-memory state from the log before accepting any new writes.
        this.wal = WriteAheadLog.open(dataDir.resolve(WAL_FILE), syncOnWrite, this::apply);
    }

    public synchronized void put(String key, String value) throws IOException {
        requireValidKey(key);
        Objects.requireNonNull(value, "value");
        LogRecord record = LogRecord.put(key, value);
        wal.append(record);
        apply(record);
    }

    public synchronized Optional<String> get(String key) {
        requireValidKey(key);
        return Optional.ofNullable(memtable.get(key));
    }

    /** Removes {@code key}. Returns true if it existed. */
    public synchronized boolean delete(String key) throws IOException {
        requireValidKey(key);
        if (!memtable.containsKey(key)) {
            return false;
        }
        LogRecord record = LogRecord.delete(key);
        wal.append(record);
        apply(record);
        return true;
    }

    /** Number of live keys. */
    public synchronized int size() {
        return memtable.size();
    }

    @Override
    public synchronized void close() throws IOException {
        wal.close();
    }

    private void apply(LogRecord record) {
        switch (record.type()) {
            case PUT -> memtable.put(record.key(), record.value());
            case DELETE -> memtable.remove(record.key());
        }
    }

    private static void requireValidKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must be non-empty");
        }
    }
}
