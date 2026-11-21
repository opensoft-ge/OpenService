package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseExpansionAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class VolumeImportAcceptance {
  /**
   * Subprocess-only fault: terminate the JVM during the database insert, before checkpoint commit.
   */
  public static void main(String[] args) throws Exception {
    if (!"throwaway-sqlite-fixture".equals(System.getenv("OPEN_SERVICE_CRASH_TEST")))
      throw new IllegalStateException();
    var store =
        new Store(new org.springframework.core.env.StandardEnvironment()) {
          @Override
          public Store.Db open() throws java.sql.SQLException {
            var db = super.open();
            org.sqlite.Function.create(
                db.connection,
                "interrupt_fixture",
                new org.sqlite.Function() {
                  @Override
                  protected void xFunc() {
                    Runtime.getRuntime().halt(86);
                  }
                });
            if (db.count("SELECT COUNT(*) FROM sqlite_master WHERE name='os_import_receipt'") > 0)
              db.update(
                  "CREATE TEMP TRIGGER stop_fixture BEFORE INSERT ON os_import_receipt WHEN"
                      + " NEW.cursor_value='1900' BEGIN SELECT interrupt_fixture(); END");
            return db;
          }
        };
    IncrementalImports.poll(store, 109);
    throw new AssertionError("Expected the worker to exit during the page");
  }

  static void run(WorkflowSupport h) throws Exception {
    String pkg = id();
    String binding = ImportReceipts.binding(map("package_id", pkg, "environment", "test"));
    var interruptions = new AtomicInteger();
    try (var provider = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
      provider.server.createContext(
          "/changes",
          exchange -> {
            int page = Integer.parseInt(exchange.getRequestURI().getRawQuery().split("=")[1]);
            int attempt = page == 9 ? interruptions.incrementAndGet() : 0;
            if (attempt == 2) {
              exchange.sendResponseHeaders(429, -1);
              exchange.close();
              return;
            }
            var rows = new ArrayList<>();
            for (int cursor = Math.max(1, page * 200);
                cursor <= Math.min(10000, (page + 1) * 200);
                cursor++) {
              if (h.store.postgres && attempt == 1 && cursor == 1900) {
                rows.add(map("broken", "no cursor"));
                break;
              }
              rows.add(
                  map(
                      "event",
                      String.valueOf(cursor),
                      "units",
                      cursor == 7001 ? "missing" : cursor));
            }
            var data =
                JSON.writeValueAsBytes(
                    map("rows", rows, "next", page == 49 ? "" : "/changes?page=" + (page + 1)));
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
          });
      var spec =
          map(
              "method",
              "GET",
              "auth",
              "bearer",
              "path",
              "/changes",
              "inputs",
              List.of(
                  map(
                      "key",
                      "after",
                      "type",
                      "string",
                      "label",
                      "After",
                      "required",
                      true,
                      "choices",
                      List.of())),
              "outputs",
              map("event", "string", "units", "number"),
              "items",
              "rows",
              "next",
              "next");
      cmd(
          h,
          "admin",
          "packages",
          "rest_define",
          "package_id",
          pkg,
          "manifest",
          spec,
          "revision",
          0,
          "title",
          "Volume import");
      cmd(
          h,
          "admin",
          "packages",
          "rest_bind",
          "package_id",
          pkg,
          "environment",
          "test",
          "alias",
          provider.alias,
          "revision",
          0);
      cmd(
          h,
          "admin",
          "packages",
          "schedule_delta",
          "package_id",
          pkg,
          "environment",
          "test",
          "cursor_input",
          "after",
          "cursor_field",
          "event",
          "cursor",
          "0",
          "minutes",
          1,
          "active",
          true,
          "manifest",
          map(),
          "revision",
          0);
      try (var db = h.store.open()) {
        String otherBinding =
            ImportReceipts.binding(map("package_id", pkg, "environment", "production"));
        ImportReceipts.accept(
            db, otherBinding, "event", object(spec.get("outputs")), map("event", "1", "units", 1));
        db.connection.commit();
      }
      long tick = 100;
      for (int i = 0; i < 9; i++) IncrementalImports.poll(h.store, tick++);
      if (h.store.postgres) IncrementalImports.poll(h.store, tick++);
      else {
        var builder =
            new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                VolumeImportAcceptance.class.getName());
        builder.environment().put("OPEN_SERVICE_CRASH_TEST", "throwaway-sqlite-fixture");
        for (String name :
            List.of("DATABASE_URL", "SECRET_KEY", "ADMIN_PASSWORD", "CONNECTOR_ALLOWED_ORIGINS"))
          builder.environment().put(name, h.environment.getProperty(name));
        var process =
            builder
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(86, process.exitValue());
      }
      try (var db = h.store.open()) {
        assertEquals(
            1800, db.count("SELECT COUNT(*) FROM os_import_receipt WHERE binding=?", binding));
      }
      var restarted = new Store(h.environment);
      tick += 60000;
      IncrementalImports.poll(restarted, tick);
      var progress =
          object(
              cmd(h, "admin", "packages", "delta_status", "package_id", pkg, "environment", "test")
                  .get("definition"));
      assertEquals("retry_pending", progress.get("state"));
      assertEquals("1800", object(progress.get("values")).get("after"));
      tick += 60000;
      for (int i = 9; i < 50; i++) IncrementalImports.poll(restarted, tick++);
      try (var db = h.store.open()) {
        assertEquals(
            9999,
            db.count(
                "SELECT COUNT(*) FROM os_import_receipt WHERE binding=? AND state='accepted'",
                binding));
        assertEquals(
            1,
            db.count(
                "SELECT COUNT(*) FROM os_import_receipt WHERE binding=? AND state='rejected'",
                binding));
      }
      cmd(
          h,
          "admin",
          "packages",
          "correct_change",
          "package_id",
          pkg,
          "environment",
          "test",
          "cursor",
          "7001",
          "manifest",
          map("event", "7001", "units", 7001));
      assertEquals(
          7001,
          cmd(
                  h,
                  "admin",
                  "packages",
                  "read_change",
                  "package_id",
                  pkg,
                  "environment",
                  "test",
                  "cursor",
                  "7001")
              .get("units"));
      try (var db = h.store.open()) {
        assertEquals(
            10000,
            db.count(
                "SELECT COUNT(*) FROM os_import_receipt WHERE binding=? AND state='accepted'",
                binding));
        ImportReceipts.accept(
            db,
            binding,
            "event",
            object(spec.get("outputs")),
            map("event", "7001", "units", "missing"));
        assertEquals(
            10000, db.count("SELECT COUNT(*) FROM os_import_receipt WHERE binding=?", binding));
      }
    }
  }
}
