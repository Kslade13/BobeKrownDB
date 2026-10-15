package dev.bobekrown.db.wal;

/**
 * One entry in the write-ahead log: either "key was set to value" or "key was deleted".
 */
public record LogRecord(Type type, String key, String value) {

    public enum Type {
        PUT((byte) 1),
        DELETE((byte) 2);

        final byte code;

        Type(byte code) {
            this.code = code;
        }

        static Type fromCode(byte code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            return null;
        }
    }

    public static LogRecord put(String key, String value) {
        return new LogRecord(Type.PUT, key, value);
    }

    public static LogRecord delete(String key) {
        return new LogRecord(Type.DELETE, key, null);
    }
}
