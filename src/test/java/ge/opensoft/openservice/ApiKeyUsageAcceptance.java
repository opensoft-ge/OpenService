package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class ApiKeyUsageAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("UsageMachine", "agent");
        h.account("UsageOther", "reader");
        String target, other, admin, key, token;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "UsageMachine");
            other = RecordAccess.owner(db, "UsageOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id IN (?,?)", target, other);
            var issued =
                    ApiKeyWrites.issue(
                            db,
                            admin,
                            target,
                            map("label", "Activity", "table", "incident", "days", 1));
            key = issued.get("key_id").toString();
            token = issued.get("token").toString();
            ApiKeys.issue(db, admin, other, map("label", "Unused", "table", "incident", "days", 1));
            db.connection.commit();
            assertEquals(
                    "Never", ApiKeys.list(db, admin, target).getFirst().get("last_used_at_utc"));
        }
        String bearer = "Bearer " + token,
                list = "/api/openservice/users/" + target + "/api-keys",
                collection = "/api/now/table/incident";
        assertEquals(200, h.get(collection, bearer).statusCode());
        assertEquals(200, h.request("HEAD", collection, null, null, bearer).statusCode());
        var created = h.json("POST", collection, map("short_description", "Usage"), bearer);
        assertEquals(201, created.statusCode());
        String path = collection + "/" + object(parsed(created).get("result")).get("sys_id");
        for (String method : List.of("PATCH", "PUT"))
            assertEquals(
                    200,
                    h.json(method, path, map("short_description", "Used"), bearer).statusCode());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> h.get(collection, bearer));
            var b = workers.submit(() -> h.get(collection, bearer));
            assertEquals(200, a.get(15, TimeUnit.SECONDS).statusCode());
            assertEquals(200, b.get(15, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(400, h.json("POST", collection, map(), bearer).statusCode());
        assertEquals(404, h.get(collection + "/" + id(), bearer).statusCode());
        assertEquals(403, h.get(list, bearer).statusCode());
        assertEquals(401, h.get(collection, "Bearer osk_" + "x".repeat(32)).statusCode());
        assertEquals(200, h.get("/healthz", bearer).statusCode());
        assertEquals(401, h.get(list, null).statusCode());
        assertEquals(403, h.get(list, auth("UsageOther")).statusCode());
        var response = h.get(list, auth("admin"));
        assertEquals(200, response.statusCode());
        var row = objects(parsed(response).get("result")).getFirst();
        assertEquals(7, ((Number) row.get("use_count")).intValue());
        assertTrue(((Number) row.get("last_used_at")).longValue() > 0);
        assertNotEquals("Never", row.get("last_used_at_utc"));
        assertFalse(row.containsKey("token_hash"));
        assertFalse(html(response).contains(token));
        String rotated;
        try (var db = h.store.open()) {
            assertEquals(
                    0,
                    ((Number) ApiKeys.list(db, admin, other).getFirst().get("use_count"))
                            .intValue());
            var before =
                    db.one("SELECT use_count,last_used_at FROM os_api_key WHERE key_id=?", key);
            ApiKeyUsage.accepted(db, key);
            db.connection.rollback();
            assertEquals(
                    before,
                    db.one("SELECT use_count,last_used_at FROM os_api_key WHERE key_id=?", key));
            var inFlight = ApiKeys.authenticate(db, bearer, collection, "GET");
            ApiKeyRotation.rotate(db, admin, target, key);
            ApiKeyUsage.accepted(db, inFlight.get("api_key_id").toString());
            assertEquals(8, ((Number) ApiKeys.list(db, admin, target).getFirst().get("use_count")).intValue());
            db.connection.rollback();
            rotated = ApiKeyRotation.rotate(db, admin, target, key).get("token").toString();
            db.connection.commit();
            assertEquals(
                    before,
                    db.one("SELECT use_count,last_used_at FROM os_api_key WHERE key_id=?", key));
            db.update(
                    "UPDATE os_api_key SET last_used_at=? WHERE key_id=?",
                    System.currentTimeMillis() + 60000,
                    key);
            db.connection.commit();
        }
        new Store(h.environment);
        assertEquals(200, h.get(collection, "Bearer " + rotated).statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    8,
                    ((Number) ApiKeys.list(db, admin, target).getFirst().get("use_count"))
                            .intValue());
            assertTrue(
                    ((Number) ApiKeys.list(db, admin, target).getFirst().get("last_used_at"))
                                    .longValue()
                            > System.currentTimeMillis());
            ApiKeys.revoke(db, admin, target, key);
            ApiKeyUsage.accepted(db, key);
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_api_key WHERE key_id=?", key));
            db.connection.rollback();
            db.update("ALTER TABLE os_api_key DROP COLUMN use_count");
            db.update("ALTER TABLE os_api_key DROP COLUMN last_used_at");
            db.update("DELETE FROM os_migration WHERE version>=27");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(
                    0,
                    ((Number) ApiKeys.list(db, admin, target).getFirst().get("use_count"))
                            .intValue());
            assertEquals(
                    "Never", ApiKeys.list(db, admin, target).getFirst().get("last_used_at_utc"));
            db.connection.rollback();
            assertEquals(26, db.count("SELECT COUNT(*) FROM os_migration"));
            Migrations.apply(db);
            db.connection.commit();
        }
        assertEquals(200, h.get(collection, "Bearer " + rotated).statusCode());
    }
}
