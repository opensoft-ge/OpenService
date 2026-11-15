package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

final class ApiKeyRotationAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("RotateMachine", "agent");
        h.account("RotateOther", "reader");
        String target, other, admin;
        MapFixture fixture;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "RotateMachine");
            other = RecordAccess.owner(db, "RotateOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id=?", target);
            var issued =
                    ApiKeys.issue(
                            db,
                            admin,
                            target,
                            map("label", "Rotate", "table", "incident", "days", 2));
            fixture =
                    new MapFixture(
                            issued.get("key_id").toString(),
                            issued.get("token").toString(),
                            ((Number) issued.get("expires_at")).longValue());
            db.connection.commit();
        }
        String api = "/api/openservice/users/" + target + "/api-keys/" + fixture.id + "/rotate";
        String form = "/admin/accounts/" + target + "/api-keys/" + fixture.id + "/rotate";
        assertEquals(401, h.json("POST", api, map(), null).statusCode());
        assertEquals(403, h.json("POST", api, map(), auth("RotateOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?extra=1", map(), auth("admin")).statusCode());
        assertEquals(
                409, h.json("POST", api.replace(target, other), map(), auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), map(), auth("admin")).statusCode());
        var rotated = h.json("POST", api, map(), auth("admin"));
        assertEquals(200, rotated.statusCode());
        var result = object(parsed(rotated).get("result"));
        String token = result.get("token").toString();
        assertEquals(fixture.id, result.get("key_id"));
        assertEquals(fixture.expires, ((Number) result.get("expires_at")).longValue());
        assertNotEquals(fixture.token, token);
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + fixture.token).statusCode());
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        assertEquals(403, h.get("/api/now/table/problem", "Bearer " + token).statusCode());
        try (var db = h.store.open()) {
            var before = db.one("SELECT * FROM os_api_key WHERE key_id=?", fixture.id);
            ApiKeyRotation.rotate(db, admin, target, fixture.id);
            db.connection.rollback();
            assertEquals(before, db.one("SELECT * FROM os_api_key WHERE key_id=?", fixture.id));
            db.update("UPDATE os_api_key SET expires_at=0 WHERE key_id=?", fixture.id);
            assertEquals(
                    409,
                    assertThrows(
                                    Failure.class,
                                    () -> ApiKeyRotation.rotate(db, admin, target, fixture.id))
                            .status);
            db.connection.rollback();
            Service.revokeSessions(db, target);
            assertEquals(
                    409,
                    assertThrows(
                                    Failure.class,
                                    () -> ApiKeyRotation.rotate(db, admin, target, fixture.id))
                            .status);
            db.connection.rollback();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> ApiKeyRotation.rotate(db, other, target, fixture.id))
                            .status);
        }
        new Store(h.environment);
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        String csrf = h.login("admin");
        assertEquals(403, h.form(form, map("csrf", "bad"), null).statusCode());
        assertEquals(200, h.form(form, map("csrf", csrf), null).statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        try (var db = h.store.open()) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_api_key WHERE user_id=?", target));
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.api_key_rotated'"));
            assertFalse(db.all("SELECT detail FROM os_security_event").toString().contains(token));
            ApiKeys.revoke(db, admin, target, fixture.id);
            assertEquals(
                    409,
                    assertThrows(
                                    Failure.class,
                                    () -> ApiKeyRotation.rotate(db, admin, target, fixture.id))
                            .status);
        }
    }

    record MapFixture(String id, String token, long expires) {}
}
