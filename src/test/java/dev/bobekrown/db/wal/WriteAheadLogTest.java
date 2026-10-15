package dev.bobekrown.db.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteAheadLogTest {

    @TempDir
    Path dir;

    private List<LogRecord> replayAll(Path file) throws IOException {
        List<LogRecord> records = new ArrayList<>();
        WriteAheadLog.open(file, false, records::add).close();
        return records;
    }

    @Test
    void replaysRecordsInOrder() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WriteAheadLog wal = WriteAheadLog.open(file, true, r -> {})) {
            wal.append(LogRecord.put("a", "1"));
            wal.append(LogRecord.put("b", "hello world"));
            wal.append(LogRecord.delete("a"));
        }

        assertEquals(List.of(
                LogRecord.put("a", "1"),
                LogRecord.put("b", "hello world"),
                LogRecord.delete("a")), replayAll(file));
    }

    @Test
    void handlesUnicode() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WriteAheadLog wal = WriteAheadLog.open(file, false, r -> {})) {
            wal.append(LogRecord.put("ключ", "値 🚀"));
        }
        assertEquals(List.of(LogRecord.put("ключ", "値 🚀")), replayAll(file));
    }

    @Test
    void tornWriteAtEndIsDiscardedAndTruncated() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WriteAheadLog wal = WriteAheadLog.open(file, false, r -> {})) {
            wal.append(LogRecord.put("good", "value"));
        }
        long goodSize = Files.size(file);

        // Simulate a crash halfway through writing the next record.
        byte[] next = WriteAheadLog.encode(LogRecord.put("lost", "value")).array();
        Files.write(file, java.util.Arrays.copyOf(next, next.length / 2), StandardOpenOption.APPEND);

        assertEquals(List.of(LogRecord.put("good", "value")), replayAll(file));
        assertEquals(goodSize, Files.size(file), "torn bytes should be truncated");
    }

    @Test
    void corruptedRecordStopsReplay() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WriteAheadLog wal = WriteAheadLog.open(file, false, r -> {})) {
            wal.append(LogRecord.put("a", "1"));
            wal.append(LogRecord.put("b", "2"));
        }
        // Flip the last byte (part of record b's value) so its checksum no longer matches.
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x7F;
        Files.write(file, bytes);

        assertEquals(List.of(LogRecord.put("a", "1")), replayAll(file));
    }

    @Test
    void appendsAfterReopenGoAfterExistingRecords() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WriteAheadLog wal = WriteAheadLog.open(file, false, r -> {})) {
            wal.append(LogRecord.put("a", "1"));
        }
        try (WriteAheadLog wal = WriteAheadLog.open(file, false, r -> {})) {
            wal.append(LogRecord.put("b", "2"));
        }
        assertEquals(List.of(LogRecord.put("a", "1"), LogRecord.put("b", "2")), replayAll(file));
    }
}
