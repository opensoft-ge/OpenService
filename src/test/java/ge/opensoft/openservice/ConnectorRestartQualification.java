package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Two application JVMs share a disposable store. Kill the first while the fixture holds its
 * response.
 */
final class ConnectorRestartQualification implements AutoCloseable {
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Process process;
    private final URI origin;

    ConnectorRestartQualification(WorkflowSupport h, String fixture) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        origin = URI.create("http://127.0.0.1:" + port);
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        Application.class.getName(),
                        "--server.address=127.0.0.1",
                        "--server.port=" + port);
        var env = builder.environment();
        env.clear();
        env.put("DATABASE_URL", h.environment.getRequiredProperty("DATABASE_URL"));
        env.put("ADMIN_PASSWORD", WorkflowSupport.PASSWORD);
        env.put("SECRET_KEY", h.store.secret);
        env.put("COOKIE_SECURE", "false");
        env.put("CONNECTOR_WRITES_ENABLED", "true");
        env.put("CONNECTOR_ALLOWED_ORIGINS", fixture);
        var log = Files.createTempFile(Path.of("target"), "restart-app-", ".log");
        process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            var check =
                    HttpRequest.newBuilder(origin.resolve("/healthz"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    if (client.send(check, HttpResponse.BodyHandlers.discarding()).statusCode()
                            == 200) return;
                } catch (IOException starting) {
                    /* Wait until the application binds the port. */
                }
                Thread.sleep(100);
            }
            throw new IOException(
                    "Restart qualification application did not become healthy. See " + log);
        } catch (Exception | Error failed) {
            close();
            throw failed;
        }
    }

    CompletableFuture<HttpResponse<String>> command(Map<String, Object> body) {
        var request =
                HttpRequest.newBuilder(origin.resolve("/api/openservice/enterprise/studio/global"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", WorkflowSupport.auth("admin"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    void terminate() throws Exception {
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }

    public void close() throws Exception {
        if (process.isAlive()) terminate();
        client.close();
    }

    static void recover(
            WorkflowSupport h,
            String fixture,
            String run,
            CountDownLatch written,
            CountDownLatch release)
            throws Exception {
        long first;
        try (var before = new ConnectorRestartQualification(h, fixture)) {
            first = before.process.pid();
            var request = before.command(TenConnectorAcceptance.call("act", run, 2, 2));
            try {
                assertTrue(
                        written.await(15, TimeUnit.SECONDS),
                        "Jira fixture did not receive the reviewed write");
                before.terminate();
            } finally {
                release.countDown();
            }
            assertThrows(ExecutionException.class, () -> request.get(15, TimeUnit.SECONDS));
        }
        try (var after = new ConnectorRestartQualification(h, fixture)) {
            assertNotEquals(first, after.process.pid());
            var result =
                    after.command(
                                    map(
                                            "operation",
                                            "recheck",
                                            "run_id",
                                            run,
                                            "step",
                                            2,
                                            "revision",
                                            2))
                            .get(15, TimeUnit.SECONDS);
            assertEquals(200, result.statusCode(), result.body());
            var record = object(EnterpriseAcceptance.doc(h, "workflow-run", run).get("definition"));
            assertEquals("complete", record.get("state"));
            assertEquals("done", objects(record.get("steps")).getFirst().get("state"));
        }
    }
}
