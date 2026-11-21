package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class GrantIndicators {
    private GrantIndicators() {}

    static Map<String, Object> sample(Store.Db db, String run) throws Exception {
        var release = definition(db, "access-run", run);
        var row = get(db, "workflow-run", run);
        var flow = definition(db, "workflow-run", run);
        require(
                flow.get("template").equals(release.get("privileged")),
                "Select a privileged access run");
        String employee = flow.get("employee").toString();
        var evidence = new ArrayList<>();
        var signers = new HashSet<String>();
        var kinds = new HashSet<String>();
        boolean valid = true;
        for (var step : objects(flow.get("steps"))) {
            String kind = step.get("kind").toString();
            if (!Set.of("approval", "team_approval").contains(kind)) continue;
            kinds.add(kind);
            String signer = Objects.toString(step.get("by"), "");
            var user =
                    db.one(
                            "SELECT active,auth_revision,password_reset_required FROM os_user WHERE"
                                    + " user_id=?",
                            signer);
            boolean current =
                    user != null
                            && ((Number) user.get("active")).intValue() == 1
                            && ((Number) user.get("password_reset_required")).intValue() == 0
                            && step.get("signer_revision") instanceof Number signed
                            && ((Number) user.get("auth_revision")).longValue()
                                    == signed.longValue();
            boolean assigned =
                    kind.equals("approval")
                            ? signer.equals(
                                    object(get(db, "workflow-identity", employee).get("definition"))
                                            .get("manager"))
                            : db.count(
                                            "SELECT COUNT(*) FROM os_group_member WHERE user_id=?"
                                                    + " AND group_id=?",
                                            signer,
                                            step.get("group"))
                                    > 0;
            valid &=
                    current
                            && assigned
                            && !employee.equals(signer)
                            && signers.add(signer)
                            && "done".equals(step.get("state"));
            evidence.add(
                    map(
                            "kind",
                            kind,
                            "signer",
                            signer,
                            "signed_version",
                            step.get("signer_revision"),
                            "account",
                            user,
                            "assigned",
                            assigned,
                            "state",
                            step.get("state")));
        }
        return map(
                "run",
                run,
                "revision",
                row.get("revision"),
                "employee",
                employee,
                "evidence",
                evidence,
                "passed",
                valid && kinds.size() == 2);
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        String id = id(b, "control_id");
        if (op.equals("monitor_grant") || op.equals("monitor_policy")) {
            String run = id(b, "run_id");
            var report = sample(db, run);
            require(
                    "complete".equals(definition(db, "workflow-run", run).get("state")),
                    "Monitor a completed privileged grant");
            return put(
                    db,
                    "grant-monitor",
                    id,
                    0,
                    map(
                            "run",
                            report.get("run"),
                            "policy",
                            op.equals("monitor_policy")
                                    ? definition(db, "access-run", run).get("privileged")
                                    : "",
                            "owner",
                            DutyRosters.responder(db, b.get("owner_id")),
                            "minutes",
                            integer(b.get("minutes"), 1, 10080),
                            "scheduled_by",
                            db.viewerId,
                            "authority",
                            AgentPlans.account(db, db.viewerId).get("auth_revision"),
                            "next_at",
                            0));
        }
        var config = definition(db, "grant-monitor", id);
        require(
                "admin".equals(db.viewerRole) || db.viewerId.equals(config.get("owner")),
                "Control owner required");
        if (op.equals("indicator_history")) {
            var results = new ArrayList<>();
            for (var row : list(db, "indicator-sample"))
                if (id.equals(object(row.get("definition")).get("control"))) results.add(row);
            return map(
                    "schedule",
                    config,
                    "samples",
                    results,
                    "issue",
                    get(db, "indicator-issue", id));
        }
        var issue = definition(db, "indicator-issue", id);
        require(
                !db.viewerId.equals(config.get("owner")) && "open".equals(issue.get("state")),
                "Independent retest of an open issue required");
        var evidence = inspect(db, config);
        require(Boolean.TRUE.equals(evidence.get("passed")), "Control still fails");
        issue.putAll(
                map(
                        "state",
                        "closed",
                        "retested_by",
                        db.viewerId,
                        "retested_at",
                        now(),
                        "retest",
                        evidence));
        return put(db, "indicator-issue", id, b.get("revision"), issue);
    }

    static Map<String, Object> inspect(Store.Db db, Map<String, Object> config) throws Exception {
        String policy = Objects.toString(config.get("policy"), "");
        if (policy.isEmpty()) return sample(db, config.get("run").toString());
        var observations = new ArrayList<Map<String, Object>>();
        for (var row : list(db, "access-run")) {
            if (!policy.equals(object(row.get("definition")).get("privileged"))) continue;
            String run = row.get("subject").toString();
            var flow = object(get(db, "workflow-run", run).get("definition"));
            if (policy.equals(flow.get("template")) && "complete".equals(flow.get("state")))
                observations.add(sample(db, run));
        }
        return map(
                "policy",
                policy,
                "observations",
                observations,
                "passed",
                !observations.isEmpty()
                        && observations.stream()
                                .allMatch(r -> Boolean.TRUE.equals(r.get("passed"))));
    }

    static void tick(Store.Db db, long now) throws Exception {
        for (var row : list(db, "grant-monitor")) {
            var config = object(row.get("definition"));
            long due = ((Number) config.get("next_at")).longValue();
            if (due > now) continue;
            String control = row.get("subject").toString();
            var executor =
                    db.one(
                            "SELECT user_id FROM os_user WHERE user_id=? AND role='admin' AND"
                                    + " active=1 AND password_reset_required=0 AND auth_revision=?",
                            config.get("scheduled_by"),
                            config.get("authority"));
            if (executor == null) continue;
            String previous = db.viewerId, role = db.viewerRole;
            var point = db.connection.setSavepoint();
            try {
                db.viewerId = executor.get("user_id").toString();
                db.viewerRole = "admin";
                var report = inspect(db, config);
                report.putAll(map("control", control, "sampled_at", now));
                save(db, "indicator-sample", key(control, due), 0, report);
                if (!Boolean.TRUE.equals(report.get("passed"))) {
                    var issue = get(db, "indicator-issue", control);
                    var data = object(issue.get("definition"));
                    data.putAll(
                            map(
                                    "state",
                                    "open",
                                    "owner",
                                    config.get("owner"),
                                    "last_sample",
                                    key(control, due)));
                    save(db, "indicator-issue", control, issue.get("revision"), data);
                }
                config.remove("error");
            } catch (Failure failure) {
                db.connection.rollback(point);
                config.put("error", failure.getMessage());
            } finally {
                db.connection.releaseSavepoint(point);
                try {
                    config.put(
                            "next_at", now + ((Number) config.get("minutes")).longValue() * 60000);
                    save(db, "grant-monitor", control, row.get("revision"), config);
                } finally {
                    db.viewerId = previous;
                    db.viewerRole = role;
                }
            }
            return;
        }
    }
}
