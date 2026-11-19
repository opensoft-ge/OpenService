package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;

final class ThreatIndicators {
    private ThreatIndicators() {}

    static String digest(Object value) {
        String normalized = WorkJobs.text(value, 64).toLowerCase(Locale.ROOT);
        require(normalized.matches("[0-9a-f]{64}"), "Provide a SHA-256 file digest");
        return normalized;
    }

    static void ingest(Store.Db db, String incident, Map<String,Object> body) throws Exception {
        keys(body, "operation", "source", "revision", "indicators");
        String source = WorkJobs.text(body.get("source"), 160);
        var indicators = new TreeMap<String,Object>();
        for (Object entry : EnterpriseData.batch(body.get("indicators"), 100)) {
            var indicator = object(entry);
            keys(indicator, "sha256", "label");
            String hash = digest(indicator.get("sha256"));
            String label = WorkJobs.text(indicator.get("label"), 200);
            require(indicators.put(hash, label) == null, "Each indicator must be unique in the snapshot");
        }
        save(db, "threat-feed", incident, body.get("revision"),
                map("source", source, "indicators", indicators, "received", now(), "actor", db.viewerId));
    }

    static void fetch(Store store, Store.Db db, String incident, Map<String,Object> body) throws Exception {
        Service.requireAccountAdministrator(db, db.viewerId);
        keys(body, "operation", "alias", "path", "revision");
        ProblemInvestigations.revision(body.get("revision"), get(db, "threat-feed", incident).get("revision"));
        String path = Connectors.path(body.get("path"));
        String alias = WorkJobs.text(body.get("alias"), 80);
        String source = WorkJobs.text(alias + ":" + path, 160);
        var response = Connectors.fetch(store, db, map("alias", alias, "path", path));
        ingest(db, incident, map("operation","feed","revision",body.get("revision"),"source",source,
                "indicators",ConnectorSynchronization.readJson((byte[])response.get("content"))));
    }

    static Object lookup(Store.Db db, String incident, Map<String,Object> body) throws Exception {
        keys(body, "operation", "sha256");
        var row = get(db, "threat-feed", incident);
        var feed = object(row.get("definition"));
        String hash = digest(body.get("sha256"));
        var indicators = object(feed.getOrDefault("indicators", map()));
        return map("sha256", hash, "matched", indicators.containsKey(hash), "label", indicators.get(hash),
                "source", feed.get("source"), "feed_revision", row.get("revision"));
    }
}
