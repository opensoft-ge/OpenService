package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class GroupSecurityAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("MemberEvidence", "reader");
        h.account("MemberObserver", "reader");
        String user, other, admin, group = h.add("sys_user_group").get("sys_id").toString();
        try (var db = h.store.open()) {
            user = RecordAccess.owner(db, "MemberEvidence");
            other = RecordAccess.owner(db, "MemberObserver");
            admin = RecordAccess.owner(db, "admin");
            GroupMembers.change(db, group, admin, map("user_id", user), true);
            db.connection.rollback();
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_security_event WHERE target_id=?", user));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_group_member WHERE user_id=?", user));
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_event WHERE record_id=? AND"
                                + " action='member_added'",
                            group));
        }
        String api = "/api/openservice/groups/" + group + "/members",
                journal = "/api/openservice/users/" + user + "/security-events";
        assertEquals(401, h.json("PUT", api, map("user_id", user), null).statusCode());
        assertEquals(
                403, h.json("PUT", api, map("user_id", user), auth("MemberObserver")).statusCode());
        assertEquals(400, h.json("PUT", api, map("user_id", id()), auth("admin")).statusCode());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> call =
                    () -> {
                        start.await();
                        return h.json("PUT", api, map("user_id", user), auth("admin")).statusCode();
                    };
            var a = workers.submit(call);
            var b = workers.submit(call);
            start.countDown();
            assertEquals(200, a.get(30, TimeUnit.SECONDS));
            assertEquals(200, b.get(30, TimeUnit.SECONDS));
        }
        assertEquals(200, h.json("DELETE", api + "/" + user, map(), auth("admin")).statusCode());
        assertEquals(200, h.json("DELETE", api + "/" + user, map(), auth("admin")).statusCode());
        var rows = (List<?>) parsed(h.get(journal, auth("admin"))).get("result");
        assertEquals(3, rows.size());
        assertEquals("account.group_membership_removed", object(rows.getFirst()).get("action"));
        assertEquals("account.group_membership_added", object(rows.get(1)).get("action"));
        assertEquals("account.created", object(rows.getLast()).get("action"));
        for (Object value : rows.subList(0, 2)) {
            var row = object(value);
            assertEquals(admin, row.get("actor_id"));
            assertEquals(user, row.get("target_id"));
            assertEquals(map("group_id", group), row.get("detail"));
        }
        assertEquals(rows, parsed(h.get(journal + "?download=true", auth("admin"))).get("result"));
        var otherRows =
                (List<?>) parsed(h.get(journal.replace(user, other), auth("admin"))).get("result");
        assertEquals(1, otherRows.size());
        assertEquals("account.created", object(otherRows.getFirst()).get("action"));
        assertEquals(403, h.get(journal, auth("MemberEvidence")).statusCode());
        new Store(h.environment);
        assertEquals(rows, parsed(h.get(journal, auth("admin"))).get("result"));
        try (var db = h.store.open()) {
            assertEquals("reader", Service.account(db, user).get("role"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_group_member WHERE user_id=?", user));
            for (String action : List.of("member_added", "member_removed"))
                assertEquals(
                        1,
                        db.count(
                                "SELECT COUNT(*) FROM os_event WHERE record_id=? AND action=?",
                                group,
                                action));
        }
    }
}
