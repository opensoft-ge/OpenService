package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class AuditEngagements {
    private AuditEngagements() {}

    static Set<String> names(Object raw, int max) {
        var names = new LinkedHashSet<String>();
        for (Object v : batch(raw, max))
            require(names.add(WorkJobs.text(v, 120)), "Duplicate selection");
        require(!names.isEmpty(), "Provide at least one selection");
        return names;
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("control")) {
            String owner = id(b, "owner_id");
            DutyRosters.responder(db, owner);
            return put(
                    db,
                    "audit-control",
                    text(b, "control_id", 120),
                    b.get("revision"),
                    map(
                            "title",
                            text(b, "title", 160),
                            "framework",
                            text(b, "framework", 120),
                            "owner",
                            owner));
        }
        if (op.equals("engage")) {
            var controls = names(b.get("controls"), 40);
            var catalogue = map();
            for (String control : controls) {
                definition(db, "audit-control", control);
                catalogue.put(control, get(db, "audit-control", control));
            }
            return put(
                    db,
                    "audit-engagement",
                    id(b, "new_id"),
                    0,
                    map(
                            "catalogue",
                            catalogue,
                            "title",
                            text(b, "title", 160),
                            "auditor",
                            db.viewerId,
                            "controls",
                            controls,
                            "evidence",
                            map(),
                            "tests",
                            map(),
                            "state",
                            "open"));
        }
        String id = id(b, "engagement_id");
        var data = definition(db, "audit-engagement", id);
        var evidence = object(data.get("evidence"));
        var tests = object(data.get("tests"));
        boolean admin = "admin".equals(db.viewerRole);
        if (op.equals("package")) {
            require("sealed".equals(data.get("state")), "Seal the engagement before export");
            return definition(db, "audit-package", id);
        }
        if (op.equals("inspect")) {
            if (admin) return get(db, "audit-engagement", id);
            var mine = map();
            for (var e : evidence.entrySet())
                if (db.viewerId.equals(object(e.getValue()).get("owner")))
                    mine.put(e.getKey(), e.getValue());
            var assigned = map();
            for (var e : tests.entrySet())
                if (db.viewerId.equals(object(e.getValue()).get("owner")))
                    assigned.put(e.getKey(), e.getValue());
            require(!mine.isEmpty() || !assigned.isEmpty(), "No assigned audit work");
            return map(
                    "requests",
                    mine,
                    "findings",
                    assigned,
                    "revision",
                    get(db, "audit-engagement", id).get("revision"));
        }
        require("open".equals(data.get("state")), "Sealed audit cannot change");
        if (op.equals("close")) {
            require(
                    ((List<?>) data.get("controls"))
                            .stream()
                                    .allMatch(
                                            c ->
                                                    "pass"
                                                            .equals(
                                                                    object(
                                                                                    tests
                                                                                            .getOrDefault(
                                                                                                    c,
                                                                                                    map()))
                                                                            .get("result"))),
                    "All selected controls must pass");
            data.putAll(
                    map(
                            "state",
                            "sealed",
                            "sealed_at",
                            now(),
                            "sealed_by",
                            db.viewerId,
                            "evidence_hash",
                            key(evidence, tests)));
            String exported = JSON.writeValueAsString(data);
            save(
                    db,
                    "audit-package",
                    id,
                    0,
                    map(
                            "filename",
                            "engagement-" + id + ".json",
                            "content",
                            exported,
                            "sha256",
                            org.apache.commons.codec.digest.DigestUtils.sha256Hex(exported)));
        } else if (op.equals("remediate")) {
            var test = object(tests.getOrDefault(text(b, "control_id", 120), map()));
            require(
                    db.viewerId.equals(test.get("owner")) && "fail".equals(test.get("result")),
                    "Only the assigned owner can submit remediation");
            test.putAll(
                    map(
                            "result",
                            "retest",
                            "resolution",
                            text(b, "note", 1000),
                            "remediated_by",
                            db.viewerId));
        } else {
            String evidenceId = id(b, "evidence_id");
            var item = object(evidence.getOrDefault(evidenceId, map()));
            if (op.equals("request")) {
                require(item.isEmpty(), "Evidence request already exists");
                var selected = names(b.get("controls"), 40);
                require(
                        ((List<?>) data.get("controls")).containsAll(selected),
                        "Choose engagement controls");
                String owner = DutyRosters.responder(db, b.get("owner_id"));
                evidence.put(
                        evidenceId,
                        map(
                                "owner",
                                owner,
                                "controls",
                                selected,
                                "samples",
                                names(b.get("samples"), 40),
                                "state",
                                "requested"));
            } else if (Set.of("submit", "capture").contains(op)) {
                require(
                        db.viewerId.equals(item.get("owner"))
                                && "requested".equals(item.get("state")),
                        "Submit your own outstanding request");
                if (op.equals("capture")) {
                    String content = text(b, "content", 12000);
                    item.putAll(
                            map(
                                    "content",
                                    content,
                                    "sha256",
                                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                                            content)));
                }
                item.putAll(
                        map(
                                "source",
                                text(b, "source", 1000),
                                "note",
                                text(b, "note", 2000),
                                "state",
                                "submitted",
                                "submitted_at",
                                now(),
                                "submitted_by",
                                db.viewerId));
            } else if (op.equals("approve")) {
                require(
                        "submitted".equals(item.get("state"))
                                && !db.viewerId.equals(item.get("owner")),
                        "Independent evidence approval required");
                item.putAll(
                        map("state", "approved", "approved_at", now(), "approved_by", db.viewerId));
            } else {
                String control = text(b, "control_id", 120),
                        result = choice(b, "result", "pass", "fail");
                require(
                        "approved".equals(item.get("state"))
                                && ((List<?>) item.get("controls")).contains(control),
                        "Approved evidence for this control required");
                var previous = object(tests.getOrDefault(control, map()));
                require(
                        previous.isEmpty()
                                || ("retest".equals(previous.get("result"))
                                        && !db.viewerId.equals(previous.get("remediated_by"))),
                        "Independent retest after remediation required");
                var history = new ArrayList<>(objects(previous.getOrDefault("history", List.of())));
                if (!previous.isEmpty()) {
                    var snapshot = new LinkedHashMap<>(previous);
                    snapshot.remove("history");
                    history.add(snapshot);
                }
                String owner = DutyRosters.responder(db, b.get("owner_id"));
                tests.put(
                        control,
                        map(
                                "result",
                                result,
                                "owner",
                                owner,
                                "due",
                                ProjectDelivery.date(b.get("due")),
                                "reviewer",
                                db.viewerId,
                                "evidence",
                                evidenceId,
                                "at",
                                now(),
                                "note",
                                text(b, "note", 1000),
                                "history",
                                history));
            }
        }
        save(db, "audit-engagement", id, b.get("revision"), data);
        return map(
                "state",
                data.get("state"),
                "revision",
                get(db, "audit-engagement", id).get("revision"));
    }
}
