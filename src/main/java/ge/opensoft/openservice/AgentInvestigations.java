package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class AgentInvestigations {
    private AgentInvestigations() {}

    static String open(
            Store.Db db, String kind, String subject, String owner, Map<String, Object> evidence)
            throws Exception {
        String id = key(kind, subject, evidence);
        var row = get(db, "agent-investigation", id);
        if (row.get("revision").equals(0)) {
            var routing = object(get(db, "investigator-route", owner).get("definition"));
            var human =
                    db.one(
                            "SELECT user_id FROM os_user WHERE active=1 AND api_only=0 AND"
                                + " role='admin' AND password_reset_required=0 ORDER BY CASE WHEN"
                                + " user_id=? THEN 0 WHEN user_id=? THEN 1 ELSE 2 END,user_id LIMIT"
                                + " 1",
                            routing.getOrDefault("reviewer", owner),
                            owner);
            String assignee = human == null ? "unassigned" : human.get("user_id").toString();
            save(
                    db,
                    "agent-investigation",
                    id,
                    0,
                    map(
                            "kind",
                            kind,
                            "subject",
                            subject,
                            "owner",
                            assignee,
                            "authority_owner",
                            owner,
                            "state",
                            "open",
                            "evidence",
                            evidence,
                            "opened",
                            now()));
        }
        return id;
    }

    static void revoked(Store.Db db, String delegate) throws Exception {
        for (var row : list(db, "agent-plan")) {
            var data = object(row.get("definition"));
            if (delegate.equals(data.get("delegate"))
                    && Set.of("reviewed", "queued").contains(data.get("state"))
                    && db.viewerId.equals(data.get("owner")))
                nativeFailure(db, row.get("subject").toString(), data, "revoked_grant");
        }
    }

    static void nativeFailure(Store.Db db, String id, Map<String, Object> data, String reason)
            throws Exception {
        String ticket =
                open(
                        db,
                        "native",
                        id,
                        data.get("owner").toString(),
                        map(
                                "reason",
                                reason,
                                "grant_revision",
                                data.get("grant_revision"),
                                "completed_steps",
                                objects(data.get("receipts")).size()));
        if (!reason.equals("step_failed") && get(db, "agent-hold", id).get("revision").equals(0))
            save(db, "agent-hold", id, 0, map("investigation", ticket));
    }

    static void permitOriginal(Store.Db db, String key) throws Exception {
        require(
                get(db, "agent-hold", key).get("revision").equals(0),
                "Review the human investigation and authorize a replacement plan");
    }

    static boolean claimable(Store.Db db, Map<String, Object> ticket) throws Exception {
        return "open".equals(ticket.get("state"))
                && db.one(
                                "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND"
                                    + " api_only=0 AND role='admin' AND password_reset_required=0",
                                ticket.get("owner"))
                        == null;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        if (op.equals("reviewer")) {
            String reviewer = id(b, "reviewer_id");
            var user = AgentPlans.account(db, reviewer);
            require(
                    "admin".equals(user.get("role")) && user.get("api_only").equals(0),
                    "Choose an active human administrator to investigate");
            return put(
                    db,
                    "investigator-route",
                    db.viewerId,
                    b.get("revision"),
                    map("reviewer", reviewer));
        }
        if (op.equals("investigations")) {
            String state = choice(b, "state", "open", "reviewed", "resumed", "unassigned");
            var mine = new ArrayList<>();
            for (var row : list(db, "agent-investigation")) {
                var data = object(row.get("definition"));
                if ("unassigned".equals(state)) {
                    if (claimable(db, data)) mine.add(row);
                    continue;
                }
                if ((db.viewerId.equals(data.get("owner"))
                                || ("reviewed".equals(state)
                                        && db.viewerId.equals(data.get("authority_owner"))))
                        && state.equals(data.get("state"))) mine.add(row);
            }
            return map("investigations", mine);
        }
        String id = text(b, "investigation_id", 64);
        var data = definition(db, "agent-investigation", id);
        if (op.equals("claim")) {
            var actor = AgentPlans.account(db, db.viewerId);
            require(
                    "admin".equals(actor.get("role")) && actor.get("api_only").equals(0),
                    "Claim as a human investigator");
            require(
                    claimable(db, data),
                    "Only open investigations without an eligible owner can be claimed");
            data.putAll(
                    map(
                            "previous_owner",
                            data.get("owner"),
                            "owner",
                            db.viewerId,
                            "claim_note",
                            text(b, "note", 1500),
                            "claimed_at",
                            now()));
            return put(db, "agent-investigation", id, b.get("revision"), data);
        }
        require(
                db.viewerId.equals(
                        data.get(op.equals("handoff_resume") ? "authority_owner" : "owner")),
                "Assigned investigator or original authorizing owner required");
        if (op.equals("handoff_review")) {
            require("open".equals(data.get("state")), "Choose an open investigation");
            if ("native".equals(data.get("kind"))) {
                var plan = definition(db, "agent-plan", data.get("subject").toString());
                data.put(
                        "reviewed_grant",
                        get(db, "agent-grant", plan.get("delegate").toString()).get("revision"));
            }
            data.putAll(
                    map(
                            "state",
                            "reviewed",
                            "note",
                            text(b, "note", 1500),
                            "by",
                            db.viewerId,
                            "reviewed_at",
                            now()));
        } else {
            require(
                    "reviewed".equals(data.get("state"))
                            && "native".equals(data.get("kind"))
                            && Boolean.TRUE.equals(b.get("confirm")),
                    "Review native work and confirm the replacement plan");
            String plan = data.get("subject").toString();
            var old = object(AgentPlans.owned(db, plan).get("definition"));
            require(
                    Set.of("failed", "reviewed").contains(old.get("state")),
                    "Only failed or revoked pending work can be resumed");
            var grant = AgentPlans.grant(db, old.get("delegate").toString());
            require(
                    ((Number) grant.get("revision")).longValue()
                            > ((Number) data.get("reviewed_grant")).longValue(),
                    "Authorize a fresh grant after the investigation");
            require(
                    get(db, "investigation-replacement", plan).get("revision").equals(0),
                    "A replacement plan already exists");
            String replacement = id(b, "new_id");
            var remaining = new ArrayList<>();
            var steps = objects(old.get("steps"));
            for (int i = objects(old.get("receipts")).size(); i < steps.size(); i++) {
                var step = steps.get(i);
                remaining.add(
                        map(
                                "table",
                                step.get("table"),
                                "record_id",
                                step.get("record_id"),
                                "patch",
                                step.get("patch")));
            }
            AgentPlans.command(
                    store,
                    db,
                    map(
                            "operation",
                            "review",
                            "key",
                            replacement,
                            "delegate_id",
                            old.get("delegate"),
                            "grant_revision",
                            grant.get("revision"),
                            "steps",
                            remaining));
            save(db, "investigation-replacement", plan, 0, map("replacement", replacement));
            data.putAll(
                    map(
                            "state",
                            "resumed",
                            "replacement",
                            replacement,
                            "grant_revision",
                            grant.get("revision"),
                            "resumed_at",
                            now()));
        }
        return put(db, "agent-investigation", id, b.get("revision"), data);
    }
}
