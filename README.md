# BobeKrownDB

A persistent key-value database built from scratch in Java 21: no database libraries, just files, data structures and sockets.

```
bobekrown> SET name alice
OK
bobekrown> GET name
alice
```

Data survives crashes and restarts: every write goes to a **write-ahead log** on disk before it is applied in memory, and the log is replayed on startup.

## Status

| Version | Feature | Status |
|---|---|---|
| v1 | In-memory table + write-ahead log with CRC32 checksums and torn-write recovery | ✅ |
| v2 | TCP server + command-line client | ⏳ |
| v3 | Hand-written skip list memtable, flushed to sorted on-disk files (SSTables) | ⏳ |
| v4 | Bloom filters + background compaction | ⏳ |
| v5 | Crash/fault-injection tests, benchmarks | ⏳ |

## Run it

Requires JDK 21. Maven is not needed (the wrapper downloads it).

```bash
./mvnw package            # build + run tests
java -jar target/bobekrowndb.jar
```

Commands: `SET key value`, `GET key`, `DEL key`, `COUNT`, `EXIT`.

## How a write works (v1)

```
SET name alice
      │
      ▼
┌─────────────────────┐  1. append + fsync    ┌──────────────┐
│   StorageEngine     │ ────────────────────► │  wal.log     │ (disk)
│                     │                       └──────────────┘
│                     │  2. update            ┌──────────────┐
│                     │ ────────────────────► │  memtable    │ (RAM)
└─────────────────────┘                       └──────────────┘
On startup: read wal.log top to bottom → rebuild memtable.
```
