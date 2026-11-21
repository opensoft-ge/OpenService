package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class PipelineReleases {
    private PipelineReleases() {}

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("policy")) {
            String ci = id(b, "service_ci"),
                    pipeline = DutyRosters.responder(db, b.get("pipeline_id"));
            require(!pipeline.equals(db.viewerId), "Use an independent pipeline identity");
            Service.readRecord(db, "cmdb_ci", ci);
            return put(
                    db,
                    "release-policy",
                    id(b, "policy_id"),
                    b.get("revision"),
                    map(
                            "title",
                            text(b, "title", 120),
                            "ci",
                            ci,
                            "pipeline",
                            pipeline,
                            "owner",
                            db.viewerId,
                            "owner_revision",
                            AgentPlans.account(db, db.viewerId).get("auth_revision"),
                            "limit",
                            integer(b.get("limit"), 1, 5)));
        }
        if (op.equals("submit")) {
            String policy = id(b, "policy_id"), id = id(b, "new_id");
            var rule = definition(db, "release-policy", policy);
            require(
                    db.viewerId.equals(rule.get("pipeline")),
                    "Use the registered pipeline identity");
            checkOwner(db, rule);
            Service.readRecord(db, "cmdb_ci", rule.get("ci").toString());
            int impact = integer(b.get("impact_score"), 1, 5),
                    likelihood = integer(b.get("likelihood"), 1, 5);
            String artifact = text(b, "artifact", 200),
                    evidence = text(b, "evidence", 2000),
                    rollback = text(b, "rollback", 1000);
            var submitted =
                    map(
                            "policy",
                            policy,
                            "pipeline",
                            db.viewerId,
                            "artifact",
                            artifact,
                            "evidence",
                            evidence,
                            "rollback",
                            rollback,
                            "impact",
                            impact,
                            "likelihood",
                            likelihood);
            var old = get(db, "pipeline-release", id);
            if (!old.get("revision").equals(0)) {
                var prior = object(old.get("definition"));
                require(
                        submitted.equals(prior.get("submitted")),
                        "Release ID already used for different evidence");
                Service.readRecord(db, "change_request", prior.get("change_id").toString());
                return old;
            }
            var item =
                    Service.create(
                            db,
                            "change_request",
                            map(
                                    "short_description",
                                    "Deploy "
                                            + artifact.substring(
                                                    0, Math.min(145, artifact.length()))),
                            actor(db));
            String change = item.get("sys_id").toString();
            ChangePlanning.save(
                    db,
                    change,
                    map(
                            "revision",
                            0,
                            "record_revision",
                            item.get("revision"),
                            "implementation",
                            artifact,
                            "impact",
                            rule.get("ci"),
                            "backout",
                            rollback,
                            "test",
                            evidence,
                            "impact_score",
                            impact,
                            "likelihood",
                            likelihood,
                            "ready",
                            true));
            transition(db, change, "review");
            boolean auto = impact * likelihood <= (int) rule.get("limit");
            if (auto) {
                String pipeline = db.viewerId, role = db.viewerRole;
                var approval = db.connection.setSavepoint();
                try {
                    db.viewerId = rule.get("owner").toString();
                    db.viewerRole = "admin";
                    transition(db, change, "approved");
                } catch (Failure pending) {
                    db.connection.rollback(approval);
                    auto = false;
                } finally {
                    db.connection.releaseSavepoint(approval);
                    db.viewerId = pipeline;
                    db.viewerRole = role;
                }
            }
            var data =
                    map(
                            "title",
                            artifact,
                            "policy_id",
                            policy,
                            "policy_revision",
                            get(db, "release-policy", policy).get("revision"),
                            "policy",
                            rule,
                            "submitted",
                            submitted,
                            "change_id",
                            change,
                            "state",
                            auto ? "authorized" : "awaiting_review",
                            "submitted_at",
                            now());
            if (auto) data.put("authorized_by", rule.get("owner"));
            return put(db, "pipeline-release", id, 0, data);
        }
        String id = id(b, "release_id");
        var data = definition(db, "pipeline-release", id);
        var rule = object(data.get("policy"));
        String change = data.get("change_id").toString();
        Service.readRecord(db, "change_request", change);
        require(
                "admin".equals(db.viewerRole) || db.viewerId.equals(rule.get("pipeline")),
                "Release access required");
        if (op.equals("inspect")) return get(db, "pipeline-release", id);
        String state = data.get("state").toString();
        if (op.equals("authorize")) {
            require(
                    state.equals("awaiting_review") && !db.viewerId.equals(rule.get("pipeline")),
                    "Choose a pending independent review");
            transition(db, change, "approved");
            data.putAll(
                    map(
                            "state",
                            "authorized",
                            "authorized_by",
                            db.viewerId,
                            "decision",
                            text(b, "note", 1000),
                            "authorized_at",
                            now()));
        } else if (op.equals("recover")) {
            require(state.equals("failed"), "Choose a failed deployment");
            transition(db, change, "closed");
            data.putAll(
                    map(
                            "state",
                            "recovered",
                            "actual_rollback",
                            text(b, "rollback", 1000),
                            "recovery_evidence",
                            text(b, "evidence", 2000),
                            "recovered_by",
                            db.viewerId,
                            "recovered_at",
                            now()));
        } else {
            require(
                    state.equals("authorized") && db.viewerId.equals(rule.get("pipeline")),
                    "Authorized pipeline release required");
            checkOwner(db, rule);
            ProblemInvestigations.revision(
                    data.get("policy_revision"),
                    get(db, "release-policy", data.get("policy_id").toString()).get("revision"));
            if(op.equals("deployment_gate"))return ChangeBoard.clear(db,id,b.get("artifact"),System.currentTimeMillis());
            ChangeBoard.outcome(db,id,b.get("artifact"),op.equals("checked_outcome"),System.currentTimeMillis());
            transition(db, change, "implement");
            String outcome = choice(b, "result", "deployed", "failed");
            String ref = text(b, "reference", 160);
            data.putAll(map("state", outcome, "deployment", ref, "reported_at", now()));
            if (outcome.equals("failed")) {
                var incident =
                        Service.create(
                                db,
                                "incident",
                                map(
                                        "short_description",
                                        "Deployment failed",
                                        "description",
                                        "Release " + id + " change " + change),
                                actor(db));
                String incidentId = incident.get("sys_id").toString();
                ProblemInvestigations.link(
                        db,
                        incidentId,
                        map(
                                "operation",
                                "create",
                                "revision",
                                incident.get("revision"),
                                "summary",
                                "Investigate failed deployment",
                                "details",
                                ref));
                data.putAll(
                        map(
                                "incident_id",
                                incidentId,
                                "problem_id",
                                db.one(
                                                "SELECT problem_id FROM os_problem_link WHERE"
                                                    + " incident_id=?",
                                                incidentId)
                                        .get("problem_id")));
            }
        }
        Service.audit(
                db,
                Service.readRecord(db, "change_request", change),
                actor(db),
                "deployment." + op,
                map("release_id", id, "state", data.get("state")));
        return put(db, "pipeline-release", id, b.get("revision"), data);
    }

    static void transition(Store.Db db, String change, String state) throws Exception {
        var item = Service.readRecord(db, "change_request", change);
        if (!state.equals(object(item.get("data")).get("state")))
            Service.update(
                    db,
                    "change_request",
                    item,
                    map("state", state),
                    actor(db),
                    db.viewerRole,
                    item.get("revision").toString());
    }

    static void checkOwner(Store.Db db, Map<String, Object> rule) throws Exception {
        var owner = AgentPlans.account(db, rule.get("owner").toString());
        require("admin".equals(owner.get("role")), "Release policy owner lost authority");
        ProblemInvestigations.revision(rule.get("owner_revision"), owner.get("auth_revision"));
    }
}
