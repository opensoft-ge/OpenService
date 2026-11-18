package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Directed dependency impact, traversing only assets the caller can currently see. */
final class AssetOperations {
    private AssetOperations() {}

    static void link(Store.Db db, String actor, Object input) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(
                body.keySet().equals(Set.of("dependent", "dependency", "remove")),
                "Provide dependent, dependency and remove");
        String dependent = WorkJobs.text(body.get("dependent"), 32),
                dependency = WorkJobs.text(body.get("dependency"), 32);
        require(!dependent.equals(dependency), "An asset cannot depend on itself");
        require(body.get("remove") instanceof Boolean, "Remove must be boolean");
        Service.record(db, "cmdb_ci", dependent);
        Service.record(db, "cmdb_ci", dependency);
        if (body.get("remove").equals(true))
            db.update(
                    "DELETE FROM os_dependency WHERE dependent=? AND dependency=?",
                    dependent,
                    dependency);
        else
            db.update(
                    "INSERT INTO os_dependency(dependent,dependency) VALUES(?,?) ON"
                        + " CONFLICT(dependent,dependency) DO NOTHING",
                    dependent,
                    dependency);
        SecurityEvents.record(
                db,
                actor,
                actor,
                "asset.dependency_changed",
                map(
                        "dependent",
                        dependent,
                        "dependency",
                        dependency,
                        "remove",
                        body.get("remove")));
    }

    static Map<String, Object> impact(Store.Db db, String id) throws SQLException {
        RecordPolicy.require(Service.schema(db,"cmdb_ci"),"read",db.viewerRole);
        Service.record(db, "cmdb_ci", id);
        var seen = new LinkedHashSet<String>();
        seen.add(id);
        var pending = new ArrayDeque<String>();
        pending.add(id);
        var result = new ArrayList<Map<String, Object>>();
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            var params = new ArrayList<Object>();
            params.add(next);
            String access = RecordAccess.filter(db, "r.sys_id", params);
            for (var row :
                    db.all(
                            "SELECT r.* FROM (SELECT dependent,dependency FROM os_dependency UNION SELECT e.source_id,e.target_id FROM os_typed_relation e JOIN os_relation_type k ON k.type_id=e.type_id WHERE e.active=1 AND k.propagates=1) d JOIN os_record r ON"
                                + " r.sys_id=d.dependent AND r.\"table\"='cmdb_ci' WHERE"
                                + " d.dependency=? AND "
                                    + access
                                    + " ORDER BY r.seq LIMIT 102",
                            params.toArray())) {
                String target = row.get("sys_id").toString();
                if (seen.add(target)) {
                    if (result.size() == 100)
                        return map("source", id, "impacted", result, "truncated", true);
                    result.add(FieldAccess.serialize(db, row));
                    pending.add(target);
                }
            }
        }
        return map("source", id, "impacted", result, "truncated", false);
    }

    static Map<String, Object> event(Store.Db db, String actor, Object input, long now)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(
                body.keySet().equals(Set.of("key", "asset_id", "severity", "summary")),
                "Provide key, asset_id, severity and summary");
        String key = WorkJobs.text(body.get("key"), 160),
                asset = WorkJobs.text(body.get("asset_id"), 32),
                severity = WorkJobs.text(body.get("severity"), 20),
                summary = WorkJobs.text(body.get("summary"), 2000);
        require(Set.of("info", "warning", "critical").contains(severity), "Unsupported severity");
        Service.record(db, "cmdb_ci", asset);
        var existing = db.one("SELECT asset_id FROM os_operational_event WHERE event_key=?", key);
        if (existing != null && !existing.get("asset_id").equals(asset))
            throw new Failure(409, "This correlation key belongs to another asset");
        db.update(
                "INSERT INTO"
                    + " os_operational_event(event_key,asset_id,severity,summary,occurrences,first_seen,last_seen)"
                    + " VALUES(?,?,?,?,1,?,?) ON CONFLICT(event_key) DO UPDATE SET"
                    + " severity=excluded.severity,summary=excluded.summary,occurrences=os_operational_event.occurrences+1,last_seen=excluded.last_seen,"
                    + "state=CASE WHEN os_operational_event.state='resolved' THEN 'open' ELSE os_operational_event.state END",
                key,
                asset,
                severity,
                summary,
                now,
                now);
        return db.one("SELECT * FROM os_operational_event WHERE event_key=?", key);
    }
}
