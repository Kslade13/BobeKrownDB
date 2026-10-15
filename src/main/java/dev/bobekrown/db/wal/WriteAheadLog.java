package dev.bobekrown.db.wal;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * An append-only log of every change made to the database.
 *
 * <p>Every write is recorded here <em>before</em> it is applied in memory. If the process
 * crashes, replaying the log on startup rebuilds the exact state the database was in.
 *
 * <p>On-disk format of one record (all integers big-endian):
 * <pre>
 * +-----------+--------+------------+--------------+-----------+-------------+
 * | crc32 (4) | type(1)| keyLen (4) | valueLen (4) | key bytes | value bytes |
 * +-----------+--------+------------+--------------+-----------+-------------+
 * </pre>
 * The CRC covers everything after itself. A crash in the middle of a write leaves a
 * partial ("torn") record at the end of the file; replay detects it via the length or
 * checksum and truncates it away.
 */
public final class WriteAheadLog implements Closeable {

    /** type + keyLen + valueLen; the CRC is stored in front of this. */
    private static final int BODY_HEADER_SIZE = 1 + 4 + 4;
    private static final int RECORD_HEADER_SIZE = 4 + BODY_HEADER_SIZE;
    /** Guards against allocating huge arrays when reading garbage lengths. */
    private static final int MAX_FIELD_SIZE = 16 * 1024 * 1024;

    private final FileChannel channel;
    private final boolean syncOnWrite;

    private WriteAheadLog(FileChannel channel, boolean syncOnWrite) {
        this.channel = channel;
        this.syncOnWrite = syncOnWrite;
    }

    /**
     * Opens the log at {@code path}, creating it if missing. Every valid record already in the
     * file is passed to {@code replayHandler} in order. Anything after the last valid record
     * (a torn write from a crash) is truncated.
     *
     * @param syncOnWrite if true, every append is forced to the physical disk before returning
     *                    (durable but slow); if false, the OS decides when to flush (fast, but the
     *                    last few writes can be lost on power failure)
     */
    public static WriteAheadLog open(Path path, boolean syncOnWrite, Consumer<LogRecord> replayHandler)
            throws IOException {
        long validLength = Files.exists(path) ? replay(path, replayHandler) : 0;

        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (channel.size() > validLength) {
            channel.truncate(validLength);
            channel.force(true);
        }
        channel.position(validLength);
        return new WriteAheadLog(channel, syncOnWrite);
    }

    public synchronized void append(LogRecord record) throws IOException {
        ByteBuffer buf = encode(record);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        if (syncOnWrite) {
            channel.force(false);
        }
    }

    /** Size of the log file in bytes. */
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public synchronized void close() throws IOException {
        channel.force(true);
        channel.close();
    }

    // ---------------------------------------------------------------- encoding

    static ByteBuffer encode(LogRecord record) {
        byte[] key = record.key().getBytes(StandardCharsets.UTF_8);
        byte[] value = record.value() == null ? new byte[0] : record.value().getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(RECORD_HEADER_SIZE + key.length + value.length);
        buf.putInt(checksum(record.type().code, key, value));
        buf.put(record.type().code);
        buf.putInt(key.length);
        buf.putInt(value.length);
        buf.put(key);
        buf.put(value);
        buf.flip();
        return buf;
    }

    private static int checksum(byte type, byte[] key, byte[] value) {
        ByteBuffer header = ByteBuffer.allocate(BODY_HEADER_SIZE);
        header.put(type).putInt(key.length).putInt(value.length).flip();

        CRC32 crc = new CRC32();
        crc.update(header);
        crc.update(key);
        crc.update(value);
        return (int) crc.getValue();
    }

    // ------------------------------------------------------------------ replay

    /** Replays valid records and returns the byte offset where valid data ends. */
    private static long replay(Path path, Consumer<LogRecord> handler) throws IOException {
        long validLength = 0;
        try (InputStream file = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(new BufferedInputStream(file))) {
            while (true) {
                LogRecord record = readRecord(in);
                if (record == null) {
                    break;
                }
                handler.accept(record);
                validLength += RECORD_HEADER_SIZE
                        + utf8Length(record.key())
                        + (record.value() == null ? 0 : utf8Length(record.value()));
            }
        }
        return validLength;
    }

    /** Returns the next record, or null at end of file or at the first torn/corrupt record. */
    private static LogRecord readRecord(DataInputStream in) throws IOException {
        try {
            int storedCrc = in.readInt();
            byte typeCode = in.readByte();
            int keyLen = in.readInt();
            int valueLen = in.readInt();
            if (keyLen < 0 || valueLen < 0 || keyLen > MAX_FIELD_SIZE || valueLen > MAX_FIELD_SIZE) {
                return null;
            }
            byte[] key = new byte[keyLen];
            byte[] value = new byte[valueLen];
            in.readFully(key);
            in.readFully(value);

            LogRecord.Type type = LogRecord.Type.fromCode(typeCode);
            if (type == null || checksum(typeCode, key, value) != storedCrc) {
                return null;
            }
            String k = new String(key, StandardCharsets.UTF_8);
            return type == LogRecord.Type.PUT
                    ? LogRecord.put(k, new String(value, StandardCharsets.UTF_8))
                    : LogRecord.delete(k);
        } catch (EOFException e) {
            return null; // clean end of file, or a record cut off mid-write
        }
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
