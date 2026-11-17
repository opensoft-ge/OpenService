package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/**
 * Read-only HTTP aliases. Operators explicitly allow destinations; responses and credentials stay
 * private.
 */
final class Connectors {
    private Connectors() {}

    static Map<String, Object> save(Store store, Store.Db db, String actor, Object input)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(
                body.keySet().equals(Set.of("alias", "origin", "token")),
                "Provide alias, origin and token");
        String alias = WorkJobs.text(body.get("alias"), 80);
        require(alias.matches("[a-z][a-z0-9_-]{0,79}"), "Invalid connector alias");
        String origin = WorkJobs.text(body.get("origin"), 2048),
                token = WorkJobs.text(body.get("token"), 4096);
        require(
                Arrays.asList(
                                store.environment
                                        .getProperty("CONNECTOR_ALLOWED_ORIGINS", "")
                                        .split(","))
                        .contains(origin),
                "Connector origin is not allowed by this deployment");
        ServiceNowSource.origin(origin, origin);
        var destination = java.net.URI.create(origin);
        require(
                destination.getScheme().equals("https")
                        || Set.of("127.0.0.1", "localhost", "[::1]")
                                .contains(destination.getHost()),
                "Use HTTPS outside loopback test environments");
        require(token.matches("[A-Za-z0-9._~+/=-]{1,4096}"), "Invalid bearer token");
        db.update(
                "INSERT INTO os_connector(alias,origin,envelope,revision) VALUES(?,?,?,1) ON"
                    + " CONFLICT(alias) DO UPDATE SET"
                    + " origin=excluded.origin,envelope=excluded.envelope,revision=os_connector.revision+1",
                alias,
                origin,
                Outbox.encrypt(store.secret, JSON.writeValueAsString(map("token", token))));
        SecurityEvents.record(db, actor, actor, "connector.saved", map("alias", alias));
        return db.one("SELECT alias,origin,revision FROM os_connector WHERE alias=?", alias);
    }

    static void retire(Store.Db db, String actor, String alias, Object input) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(body.keySet().equals(Set.of("reason")), "Provide a retirement reason");
        WorkJobs.text(alias, 80);
        String reason = WorkJobs.text(body.get("reason"), 2000);
        if (db.update("DELETE FROM os_connector WHERE alias=?", alias) != 1)
            throw new Failure(404, "Connector alias not found");
        SecurityEvents.record(db, actor, actor, "connector.retired", map("alias", alias, "reason", reason));
    }

    static String review(Map<String,Object> row) {
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(JSON.writeValueAsString(map("alias",row.get("alias"),"origin",row.get("origin"),"revision",row.get("revision"),"envelope",row.get("envelope"))));
    }
    static String path(Object input) {
        String path=WorkJobs.text(input,2048);
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains("#") && !path.contains("\\"),"Use a relative absolute path without a fragment");return path;
    }

    static Map<String, Object> read(Store store, Store.Db db, Object input) throws Exception {
        var body = object(input);
        require(body.keySet().equals(Set.of("alias","path")) || body.keySet().equals(Set.of("alias","path","review")),"Provide alias, path and optional review");
        String alias=WorkJobs.text(body.get("alias"),80),path=path(body.get("path"));
        var row = db.one("SELECT * FROM os_connector WHERE alias=?", alias);
        if (row == null) throw new Failure(404, "Connector alias not found");
        if(body.containsKey("review") && !review(row).equals(WorkJobs.text(body.get("review"),64)))throw new Failure(409,"Connector changed after review");
        String origin = row.get("origin").toString();
        require(
                Arrays.asList(
                                store.environment
                                        .getProperty("CONNECTOR_ALLOWED_ORIGINS", "")
                                        .split(","))
                        .contains(origin),
                "Connector origin is no longer allowed");
        String token =
                Outbox.decrypt(store.secret, row.get("envelope").toString())
                        .get("token")
                        .toString();
        byte[] bytes = ServiceNowSource.get(origin, token, path, 1024 * 1024);
        // Retain a receipt, not arbitrary provider data or secrets, in the job history.
        return map(
                "alias",
                alias,
                "revision",
                row.get("revision"),
                "bytes",
                bytes.length,
                "sha256",
                org.apache.commons.codec.digest.DigestUtils.sha256Hex(bytes));
    }
}
