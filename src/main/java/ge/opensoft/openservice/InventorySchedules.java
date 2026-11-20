package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class InventorySchedules {
    private InventorySchedules() {}

    static Object configure(Store.Db db, Map<String, Object> b) throws Exception {
        String source = text(b, "source", 80), alias = text(b, "alias", 80);
        require(b.get("active") instanceof Boolean, "Choose enabled or paused");
        var connector = ConnectorConsole.page(db, alias);
        var data =
                map(
                        "owner",
                        db.viewerId,
                        "authority",
                        AgentPlans.account(db, db.viewerId).get("auth_revision"),
                        "alias",
                        alias,
                        "review",
                        connector.get("review"),
                        "path",
                        Connectors.path(b.get("path")),
                        "minutes",
                        integer(b.get("minutes"), 1, 10080),
                        "active",
                        b.get("active"));
        save(db, "inventory-schedule", source, b.get("revision"), data);
        var progress = get(db, "inventory-progress", source);
        save(
                db,
                "inventory-progress",
                source,
                progress.get("revision"),
                map("next_at", System.currentTimeMillis(), "state", "configured"));
        return get(db, "inventory-schedule", source);
    }

    static void authority(Store.Db db, Map<String, Object> config) throws Exception {
        db.viewerId = config.get("owner").toString();
        var owner = AgentPlans.account(db, db.viewerId);
        db.viewerRole = owner.get("role").toString();
        require("admin".equals(db.viewerRole), "Collector owner lost authority");
        ProblemInvestigations.revision(config.get("authority"), owner.get("auth_revision"));
        require(
                config.get("review")
                        .equals(
                                ConnectorConsole.page(db, config.get("alias").toString())
                                        .get("review")),
                "Rebind rotated collection credentials");
    }

    record Attempt(
            Map<String, Object> schedule,
            String source,
            Map<String, Object> config,
            Map<String, Object> snapshot) {}

    static boolean refresh(Store store, long time) throws Exception {
        Attempt attempt;
        try (var reader = store.open()) {
            attempt = collect(store, reader, time);
        }
        if (attempt == null) return false;
        try (var writer = store.open()) {
            return accept(writer, attempt, time);
        }
    }

    private static Attempt collect(Store store, Store.Db db, long time) throws Exception {
        // The collector can be slow. Release SQLite's writer lock while the HTTP read is in flight.
        // The second transaction checks both revisions before accepting these observations.
        db.connection.setAutoCommit(true);
        String due =
                db.postgres
                        ? "CAST(p.definition::jsonb->>'next_at' AS BIGINT)"
                        : "json_extract(p.definition,'$.next_at')";
        String active =
                db.postgres
                        ? "(s.definition::jsonb->>'active')::boolean"
                        : "json_extract(s.definition,'$.active')=1";
        var schedule =
                db.one(
                        "SELECT s.subject,s.definition,s.revision,p.revision AS cursor_revision"
                                + " FROM os_enterprise_document s JOIN os_enterprise_document p ON"
                                + " s.subject=p.subject AND p.area='inventory-progress' WHERE"
                                + " s.area='inventory-schedule' AND "
                                + active
                                + " AND "
                                + due
                                + "<=? ORDER BY "
                                + due
                                + ",s.subject LIMIT 1",
                        time);
        if (schedule == null) return null;
        String source = schedule.get("subject").toString();
        var config = object(schedule.get("definition"));
        Map<String, Object> snapshot = null;
        try {
            authority(db, config);
            var response =
                    Connectors.fetch(
                            store,
                            db,
                            map(
                                    "alias",
                                    config.get("alias"),
                                    "review",
                                    config.get("review"),
                                    "path",
                                    config.get("path")));
            var packet =
                    object(ConnectorSynchronization.readJson((byte[]) response.get("content")));
            keys(packet, "operation", "source", "sequence", "resources", "services");
            require(
                    "inventory".equals(packet.get("operation"))
                            && source.equals(packet.get("source")),
                    "Snapshot source differs from schedule");
            snapshot = packet;
        } catch (Failure failed) {
            /* Keep the last accepted inventory. */
        }

        return new Attempt(schedule, source, config, snapshot);
    }

    private static boolean accept(Store.Db db, Attempt attempt, long time) throws Exception {
        var schedule = attempt.schedule();
        var config = attempt.config();
        var source = attempt.source();
        var snapshot = attempt.snapshot();
        lock(db);
        if (!schedule.get("revision").equals(get(db, "inventory-schedule", source).get("revision"))
                || !schedule.get("cursor_revision")
                        .equals(get(db, "inventory-progress", source).get("revision")))
            return false;
        var checkpoint = db.connection.setSavepoint();
        var progress =
                map(
                        "next_at",
                        time + ((Number) config.get("minutes")).longValue() * 60000,
                        "attempted_at",
                        now(),
                        "state",
                        "failed");
        try {
            authority(db, config);
            if (snapshot != null) {
                ServiceTopology.inventory(db, source, snapshot);
                progress.putAll(map("state", "refreshed", "sequence", snapshot.get("sequence")));
            }
        } catch (Failure invalid) {
            db.connection.rollback(checkpoint);
        } finally {
            db.connection.releaseSavepoint(checkpoint);
        }
        // A deleted owner still leaves a visible failed cursor and cannot refresh inventory.
        db.update(
                "UPDATE os_enterprise_document SET definition=?,revision=revision+1 WHERE"
                        + " area='inventory-progress' AND subject=?",
                JSON.writeValueAsString(progress),
                source);
        db.connection.commit();
        return true;
    }
}
