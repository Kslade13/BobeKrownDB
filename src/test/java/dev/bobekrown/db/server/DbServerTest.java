package dev.bobekrown.db.server;

import dev.bobekrown.db.client.DbClient;
import dev.bobekrown.db.engine.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbServerTest {

    @TempDir
    Path dir;

    /** Starts a server on a free port in the background; closing it stops the server and engine. */
    private record Running(StorageEngine engine, DbServer server, Thread thread) implements AutoCloseable {
        static Running start(Path dir) throws IOException {
            StorageEngine engine = new StorageEngine(dir, false);
            DbServer server = new DbServer(engine, 0);
            Thread thread = new Thread(server::serve);
            thread.start();
            return new Running(engine, server, thread);
        }

        int port() {
            return server.port();
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join();
            engine.close();
        }
    }

    @Test
    void clientCanSetAndGet() throws Exception {
        try (Running running = Running.start(dir);
             DbClient client = new DbClient("localhost", running.port())) {
            assertEquals("OK", client.send("SET name alice smith"));
            assertEquals("alice smith", client.send("GET name"));
            assertEquals("1", client.send("COUNT"));
            assertEquals("1", client.send("DEL name"));
            assertEquals("(nil)", client.send("GET name"));
            assertTrue(client.send("FLY away").startsWith("ERR"));
        }
    }

    @Test
    void clientsSeeEachOthersWrites() throws Exception {
        try (Running running = Running.start(dir);
             DbClient writer = new DbClient("localhost", running.port());
             DbClient reader = new DbClient("localhost", running.port())) {
            writer.send("SET school BGSU");
            assertEquals("BGSU", reader.send("GET school"));
        }
    }

    @Test
    void dataSurvivesServerRestart() throws Exception {
        try (Running running = Running.start(dir);
             DbClient client = new DbClient("localhost", running.port())) {
            client.send("SET city toledo");
        }
        try (Running running = Running.start(dir);
             DbClient client = new DbClient("localhost", running.port())) {
            assertEquals("toledo", client.send("GET city"));
        }
    }

    @Test
    void manyClientsWritingAtOnce() throws Exception {
        int clients = 8;
        int writesEach = 200;
        try (Running running = Running.start(dir)) {
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            List<Future<?>> done = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                int id = c;
                done.add(pool.submit(() -> {
                    try (DbClient client = new DbClient("localhost", running.port())) {
                        for (int i = 0; i < writesEach; i++) {
                            assertEquals("OK", client.send("SET c" + id + "-k" + i + " v" + i));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get(); // rethrows any assertion failure from a client thread
            }
            pool.shutdown();

            try (DbClient client = new DbClient("localhost", running.port())) {
                assertEquals(String.valueOf(clients * writesEach), client.send("COUNT"));
                assertEquals("v199", client.send("GET c7-k199"));
            }
        }
    }

    @Test
    void multiLineCommandsAreRejected() throws Exception {
        try (Running running = Running.start(dir);
             DbClient client = new DbClient("localhost", running.port())) {
            assertThrows(IllegalArgumentException.class, () -> client.send("SET a 1\nDEL b"));
        }
    }
}
