package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.TenPolicyAcceptance.as;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

final class ServiceExpansionAcceptance {
    static Map<String, Object> ipResource(String id) {
        return map("id", id, "title", "Unrelated resource", "type", "ip", "depends_on", List.of());
    }

    static Object docRevision(Store.Db db, String area, String id) throws Exception {
        return EnterpriseData.get(db, area, id).get("revision");
    }

    static void mapping(WorkflowSupport h) throws Exception {
        String aws = "aws/one", cluster = "cluster/qa";
        var service = map("id", "checkout", "title", "Checkout", "roots", List.of("lb"));
        var lb =
                map(
                        "id",
                        "lb",
                        "title",
                        "Checkout LB",
                        "type",
                        "lb",
                        "depends_on",
                        List.of("target"));
        var target =
                map(
                        "id",
                        "target",
                        "title",
                        "Target address",
                        "type",
                        "ip",
                        "depends_on",
                        List.of());
        var pod = map("id", "pod", "title", "Checkout pod", "type", "pod", "depends_on", List.of());
        var db = map("id", "rds", "title", "Database", "type", "db", "depends_on", List.of());
        var snapshot =
                map(
                        "operation",
                        "inventory",
                        "sequence",
                        1,
                        "source",
                        aws,
                        "services",
                        List.of(service),
                        "resources",
                        List.of(lb, target, db));
        post(h, "topology", snapshot);
        post(
                h,
                "topology",
                map(
                        "operation",
                        "inventory",
                        "sequence",
                        1,
                        "source",
                        cluster,
                        "services",
                        List.of(),
                        "resources",
                        List.of(pod)));
        for (var tuple :
                List.of(
                        List.of(aws, "target", "workload"),
                        List.of(cluster, "pod", "workload"),
                        List.of(aws, "rds", "database"))) {
            post(
                    h,
                    "mapping",
                    map(
                            "operation",
                            "bind",
                            "source",
                            tuple.get(0),
                            "resource_id",
                            tuple.get(1),
                            "component",
                            tuple.get(2),
                            "revision",
                            0));
        }
        var link =
                map(
                        "operation",
                        "link",
                        "component",
                        "workload",
                        "dependency",
                        "database",
                        "revision",
                        0);
        post(h, "mapping", link);
        var trace = map("operation", "map", "source", aws, "service_id", "checkout");
        var result = object(post(h, "mapping", trace).get("components"));
        assertEquals(3, result.size());
        assertEquals(
                "database",
                objects(object(result.get("workload")).get("manual_links"))
                        .getFirst()
                        .get("dependency"));
        assertEquals(2, objects(object(result.get("workload")).get("evidence")).size());
        snapshot.put("resources", List.of(lb));
        snapshot.put("sequence", 2);
        bad(h, "topology", "global", snapshot, 400);
        assertEquals(3, object(post(h, "mapping", trace).get("components")).size());
        post(
                h,
                "topology",
                map(
                        "operation",
                        "signals",
                        "source",
                        aws,
                        "resource_id",
                        "rds",
                        "alerts",
                        List.of(
                                TenJourneysAcceptance.signal(
                                        "health",
                                        "down",
                                        Instant.now().getEpochSecond() - 10,
                                        "down"))));
        var impact = post(h, "mapping", map("operation", "outage", "component", "database"));
        assertEquals(List.of(aws + " :: checkout"), impact.get("affected_services"));
        assertEquals(1, ((List<?>) impact.get("related_open_incidents")).size());
        String address = "vpc-1/10.0.0.1", arn = "arn:aws:rds:us-east-1:012345678901:db:orders";
        for (String incomplete : List.of("10.0.0.1", "peer/10.0.0.1", "vpc-1/not-an-address"))
            assertFalse(FederatedMaps.matches(incomplete, List.of("PodAddress", "ip")));
        assertFalse(FederatedMaps.matches("db:orders", List.of("DeclaredDatabase", "RDSInstance")));
        var ip = map("id", address, "title", "Target", "type", "ip", "depends_on", List.of());
        var rds =
                map(
                        "id",
                        arn,
                        "title",
                        "Orders DB",
                        "type",
                        "RDSInstance",
                        "depends_on",
                        List.of());
        lb.put("depends_on", List.of(address));
        snapshot.put("resources", List.of(lb, ip, rds));
        post(h, "topology", snapshot);
        var podAddress =
                map(
                        "id",
                        address,
                        "title",
                        "Pod address",
                        "type",
                        "PodAddress",
                        "depends_on",
                        List.of("pod"));
        pod.put("depends_on", List.of(arn));
        var declared = new LinkedHashMap<>(rds);
        declared.put("type", "DeclaredDatabase");
        post(
                h,
                "topology",
                map(
                        "operation",
                        "inventory",
                        "sequence",
                        2,
                        "source",
                        cluster,
                        "services",
                        List.of(),
                        "resources",
                        List.of(podAddress, pod, declared, lb, ipResource("target"))));
        var reconcile = map("operation", "reconcile", "source", aws, "peer", cluster);
        var shared = post(h, "mapping", reconcile);
        reconcile.putAll(map("source", cluster, "peer", aws));
        assertEquals(shared, post(h, "mapping", reconcile));
        assertEquals(2, shared.size());
        var traced = object(post(h, "mapping", trace).get("components"));
        assertEquals(2, objects(object(traced.get(shared.get(arn))).get("evidence")).size());
        reconcile.put("source", aws);
        bad(h, "mapping", "global", reconcile, 400);
        post(
                h,
                "mapping",
                map(
                        "operation",
                        "bind",
                        "source",
                        aws,
                        "resource_id",
                        arn,
                        "component",
                        "operator-pinned",
                        "revision",
                        1));
        reconcile.put("peer", cluster);
        bad(h, "mapping", "global", reconcile, 400);
        var cycle =
                map(
                        "operation",
                        "link",
                        "component",
                        "workload",
                        "dependency",
                        "workload",
                        "revision",
                        0);
        rejectVariants(
                h,
                "mapping",
                "global",
                cycle,
                List.of(map("dependency", "workload"), map("dependency", "removed")));
        cycle.put("dependency", shared.get(address));
        post(h, "mapping", cycle);
        post(h, "mapping", trace);
        post(h, "mapping", map("operation", "outage", "component", "operator-pinned"));
        post(
                h,
                "topology",
                map(
                        "operation",
                        "inventory",
                        "sequence",
                        3,
                        "source",
                        cluster,
                        "services",
                        List.of(),
                        "resources",
                        List.of()));
        post(h, "mapping", trace);
    }

    static void duty(WorkflowSupport h) throws Exception {
        MajorIncidentsAcceptance.setup(h);
        String incident = MajorIncidentsAcceptance.incident(h),
                primary = uid(h, "MajorCommander"),
                backup = uid(h, "MajorOther"),
                roster = id();
        String anchor = LocalDate.now(ZoneOffset.UTC).minusDays(1) + "T20:00:00Z";
        var entry =
                map(
                        "operation",
                        "roster",
                        "roster_id",
                        roster,
                        "title",
                        "Critical service",
                        "anchor",
                        anchor,
                        "zone",
                        "UTC",
                        "hours",
                        12,
                        "people",
                        List.of(primary, backup),
                        "minutes",
                        5,
                        "revision",
                        0);
        rejectVariants(
                h,
                "duty",
                "global",
                entry,
                List.of(
                        map("people", List.of(primary)),
                        map("people", List.of(primary, primary)),
                        map("zone", "Invalid/Zone")));
        post(h, "duty", entry);
        try (var db = h.store.open()) {
            db.update(
                    "UPDATE os_user SET email=?, email_verified=1 WHERE user_id=?",
                    "backup@example.test",
                    backup);
            db.update(
                    "UPDATE os_user SET email=?, email_verified=1 WHERE user_id=?",
                    "primary@example.test",
                    primary);
            db.connection.commit();
        }
        String midnight = LocalDate.now(ZoneOffset.UTC) + "T01:00:00Z";
        assertEquals(
                primary,
                post(h, "duty", map("operation", "who", "roster_id", roster, "at", midnight))
                        .get("primary"));
        var change =
                map(
                        "operation",
                        "override",
                        "roster_id",
                        roster,
                        "primary",
                        backup,
                        "backup",
                        primary,
                        "start",
                        anchor,
                        "end",
                        LocalDate.now(ZoneOffset.UTC).plusDays(2) + "T00:00:00Z",
                        "note",
                        "Holiday coverage",
                        "revision",
                        1);
        rejectVariants(
                h, "duty", "global", change, List.of(map("end", anchor), map("backup", backup)));
        post(h, "duty", change);
        bad(h, "duty", "global", change, 400);
        var who = map("operation", "who", "roster_id", roster, "at", anchor);
        assertEquals(backup, post(h, "duty", who).get("primary"));
        who.put("at", "2000-01-01T00:00:00Z");
        bad(h, "duty", "global", who, 400);
        who.put("at", LocalDate.now(ZoneOffset.UTC).plusDays(3) + "T00:00:00Z");
        post(h, "duty", who);
        MajorIncidentsAcceptance.propose(h, incident);
        MajorIncidentsAcceptance.accept(h, incident);
        var page = map("operation", "page", "incident_id", incident, "roster_id", roster);
        var before = new LinkedHashMap<>(change);
        before.putAll(
                map("start", "2020-01-01T01:00:00Z", "end", "2020-01-01T02:00:00Z", "revision", 2));
        post(h, "duty", before);
        var after = new LinkedHashMap<>(change);
        after.putAll(
                map("start", "2099-01-01T01:00:00Z", "end", "2099-01-01T02:00:00Z", "revision", 3));
        post(h, "duty", after);
        var normal = MajorIncidentsAcceptance.incident(h);
        bad(
                h,
                "duty",
                "global",
                map("operation", "page", "roster_id", roster, "incident_id", normal),
                400);
        bad(
                h,
                "duty",
                "global",
                map(
                        "operation",
                        "handoff",
                        "incident_id",
                        normal,
                        "responder_id",
                        backup,
                        "note",
                        "No page",
                        "revision",
                        0),
                400);
        h.account("DutyRelief", "agent");
        String relief = uid(h, "DutyRelief");
        post(
                h,
                "duty",
                map(
                        "operation",
                        "watch",
                        "source",
                        "qa/monitor",
                        "roster_id",
                        roster,
                        "revision",
                        0));
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            for (var item :
                    List.of(
                            map("state", "open", "incident", id(), "source", "ignored"),
                            map("state", "closed", "incident", id()),
                            map(
                                    "state",
                                    "open",
                                    "incident",
                                    id(),
                                    "commander",
                                    primary,
                                    "source",
                                    "unwatched")))
                EnterpriseData.save(db, "service-episode", id(), 0, item);
            EnterpriseData.save(
                    db,
                    "service-episode",
                    id(),
                    0,
                    map(
                            "state",
                            "open",
                            "incident",
                            incident,
                            "commander",
                            primary,
                            "source",
                            "qa/monitor"));
            DutyRosters.tick(h.store, db, System.currentTimeMillis());
            db.connection.commit();
        }
        post(h, "duty", page);
        var transport =
                new DeliveryWorker(
                        h.store,
                        (key, payload) -> {
                            throw new java.io.IOException("Provider unavailable");
                        });
        assertTrue(transport.runOne(System.currentTimeMillis() + 1000));
        var history = map("operation", "history", "incident_id", incident);
        var events = objects(object(post(h, "duty", history).get("definition")).get("history"));
        assertEquals("DELIVERY_FAILED", object(events.getFirst().get("transport")).get("error"));
        var handoff =
                map(
                        "operation",
                        "handoff",
                        "incident_id",
                        incident,
                        "responder_id",
                        backup,
                        "note",
                        "Shift handover",
                        "revision",
                        1);
        bad(h, "duty", "global", handoff, 400);
        var ack = map("operation", "ack", "incident_id", incident);
        as(h, "MajorCommander", "duty", ack, 400);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            DutyRosters.tick(h.store, db, System.currentTimeMillis() + 360000);
            db.connection.commit();
        }
        handoff.putAll(map("responder_id", relief, "revision", 2));
        as(h, "DutyRelief", "duty", handoff, 400);
        as(h, "MajorCommander", "duty", handoff, 200);
        as(h, "MajorCommander", "duty", ack, 400);
        var result = object(as(h, "DutyRelief", "duty", ack, 200).get("definition"));
        assertEquals("acknowledged", result.get("state"));
        assertEquals(4, objects(result.get("history")).size());
        try (var db = h.store.open()) {
            DutyRosters.tick(h.store, db, System.currentTimeMillis() + 900000);
            db.connection.commit();
        }
        assertEquals(
                4,
                objects(object(post(h, "duty", history).get("definition")).get("history")).size());
        assertTrue(transport.runOne(System.currentTimeMillis() + 60000));
        as(h, "DutyRelief", "duty", ack, 200);
        bad(h, "duty", "global", handoff, 400);
        String team = h.add("sys_user_group").get("sys_id").toString();
        String critical = MajorIncidentsAcceptance.incident(h);
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/incident/" + critical,
                                map("priority", "1", "assignment_group", team),
                                auth("admin"))
                        .statusCode());
        post(
                h,
                "duty",
                map("operation", "team", "group_id", team, "roster_id", roster, "revision", 0));
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            DutyRosters.tick(h.store, db, System.currentTimeMillis());
            db.connection.commit();
        }
        assertEquals(
                "primary",
                object(
                                post(
                                                h,
                                                "duty",
                                                map(
                                                        "operation",
                                                        "history",
                                                        "incident_id",
                                                        critical))
                                        .get("definition"))
                        .get("state"));
        var contact = map("incident", critical, "person", backup, "email", "backup@example.test");
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            assertTrue(DutyRosters.valid(db, contact));
            contact.put("email", "wrong@example.test");
            assertFalse(DutyRosters.valid(db, contact));
            contact.put("person", relief);
            assertFalse(DutyRosters.valid(db, contact));
            String requester = RecordAccess.owner(db, "admin");
            for (String fixture : List.of("missing", "inactive", "requester")) {
                if (fixture.equals("inactive"))
                    db.update("UPDATE os_user SET active=0 WHERE user_id=?", relief);
                if (fixture.equals("requester"))
                    db.update(
                            "UPDATE os_user SET active=1,role='requester' WHERE user_id=?", relief);
                var outcome =
                        map(
                                "primary",
                                fixture.equals("missing") ? id() : relief,
                                "owner",
                                requester,
                                "history",
                                List.of());
                DutyRosters.notify(
                        h.store, db, critical, outcome, "primary", System.currentTimeMillis());
                assertEquals(
                        "unavailable", objects(outcome.get("history")).getFirst().get("delivery"));
            }
            var nativeRecord = Service.readRecord(db, "incident", normal);
            var fields = object(nativeRecord.get("data"));
            fields.putAll(map("state", "7", "priority", "1"));
            db.update(
                    "UPDATE os_record SET data=" + db.jsonParameter() + " WHERE sys_id=?",
                    JSON.writeValueAsString(fields),
                    normal);
            DutyRosters.pageWatched(
                    h.store,
                    db,
                    map("owner", db.viewerId, "roster", roster),
                    normal,
                    System.currentTimeMillis());
            assertEquals(0, EnterpriseData.get(db, "incident-page", normal).get("revision"));
            String orphan = id();
            EnterpriseData.save(
                    db,
                    "incident-page",
                    orphan,
                    0,
                    map("primary", backup, "backup", primary, "state", "primary"));
            db.viewerId = backup;
            db.viewerRole = "agent";
            assertFalse(DeliveryWorkspace.workspaceData(db, "duty").toString().contains(orphan));
            PlatformFoundationTest.bind(db);
            var cfgBefore = JourneyForms.definition(db, "duty-roster", roster);
            var temporary = new LinkedHashMap<>(cfgBefore);
            temporary.put(
                    "overrides",
                    List.of(
                            map(
                                    "start",
                                    System.currentTimeMillis() + 300000,
                                    "end",
                                    System.currentTimeMillis() + 600000,
                                    "primary",
                                    relief,
                                    "backup",
                                    backup)));
            assertTrue(
                    DutyRosters.coverage(temporary, System.currentTimeMillis())
                            .containsKey("backup"));
            var future = id();
            var cfg = JourneyForms.definition(db, "duty-roster", roster);
            cfg.put("anchor", System.currentTimeMillis() + 86400000L);
            EnterpriseData.save(db, "duty-roster", future, 0, cfg);
            DutyRosters.pageWatched(
                    h.store,
                    db,
                    map("owner", db.viewerId, "roster", future),
                    normal,
                    System.currentTimeMillis());
            DutyRosters.pageWatched(
                    h.store,
                    db,
                    map("owner", relief, "roster", roster),
                    normal,
                    System.currentTimeMillis());
        }
        as(h, "MajorOther", "duty", map("operation", "ack", "incident_id", critical), 200);
    }

    static void routes(WorkflowSupport h) throws Exception {
        var ids = FieldDispatchAcceptance.setup(h);
        String task = ids[1],
                other = WorkTasksAcceptance.create(h, ids[0], ids[2], List.of(), true),
                a = uid(h, "TaskOne"),
                b = uid(h, "TaskTwo"),
                plan = id();
        var c1 =
                map(
                        "id",
                        a,
                        "depot",
                        "west",
                        "start",
                        480,
                        "end",
                        1080,
                        "skills",
                        List.of("install"),
                        "parts",
                        map("kit", 2));
        var c2 = new LinkedHashMap<>(c1);
        c2.putAll(map("id", b, "depot", "east"));
        var job =
                map(
                        "task",
                        task,
                        "crew",
                        a,
                        "place",
                        "east",
                        "open",
                        480,
                        "close",
                        600,
                        "duration",
                        60,
                        "skill",
                        "install",
                        "part",
                        "kit");
        var next = new LinkedHashMap<>(job);
        next.putAll(map("task", other, "place", "west"));
        var day = LocalDate.now().plusDays(2);
        while (day.getDayOfWeek().getValue() > 5) day = day.plusDays(1);
        var route =
                map(
                        "operation",
                        "optimize",
                        "new_id",
                        plan,
                        "territory",
                        "Metro",
                        "date",
                        day.toString(),
                        "crews",
                        List.of(c1, c2),
                        "jobs",
                        List.of(job, next),
                        "travel",
                        List.of(
                                map("from", "east", "to", "west", "minutes", 50),
                                map("from", "west", "to", "east", "minutes", 50)));
        var unqualified = new LinkedHashMap<>(c1);
        unqualified.put("id", uid(h, "admin"));
        var shift = new LinkedHashMap<>(c1);
        shift.put("end", 480);
        var invalidWindow = new LinkedHashMap<>(job);
        invalidWindow.put("close", 480);
        rejectVariants(
                h,
                "routing",
                "global",
                route,
                List.of(
                        map("crews", List.of(c1, c1)),
                        map("crews", List.of(unqualified)),
                        map("crews", List.of(shift)),
                        map("jobs", List.of(invalidWindow))));
        var result = object(post(h, "routing", route).get("definition"));
        assertEquals(1, object(result.get("original")).get("missed_windows"));
        assertEquals(0, object(result.get("proposed")).get("missed_windows"));
        bad(
                h,
                "routing",
                "global",
                map(
                        "operation",
                        "replan",
                        "plan_id",
                        plan,
                        "absent",
                        uid(h, "admin"),
                        "revision",
                        1),
                400);
        var replan =
                object(
                        post(
                                        h,
                                        "routing",
                                        map(
                                                "operation",
                                                "replan",
                                                "plan_id",
                                                plan,
                                                "absent",
                                                b,
                                                "revision",
                                                1))
                                .get("definition"));
        assertEquals(1, object(replan.get("proposed")).get("missed_windows"));
        assertEquals(
                2,
                post(h, "routing", map("operation", "inspect", "plan_id", plan)).get("revision"));
        var publish = map("operation", "publish", "plan_id", plan, "revision", 2, "confirm", false);
        bad(h, "routing", "global", publish, 400);
        publish.put("confirm", true);
        post(h, "routing", publish);
        publish.put("revision", 3);
        bad(h, "routing", "global", publish, 400);
        route.put("new_id", id());
        var completePlan = post(h, "routing", route);
        publish.putAll(
                map("plan_id", route.get("new_id"), "revision", completePlan.get("revision")));
        post(h, "routing", publish);
        var prior=object(FieldDispatchAcceptance.page(h,ids[0],task,"admin").get("dispatch"));
        route.put("new_id",id());
        var identical=post(h,"routing",route);
        publish.putAll(map("plan_id",route.get("new_id"),"revision",identical.get("revision")));
        post(h,"routing",publish);
        assertEquals(prior,object(FieldDispatchAcceptance.page(h,ids[0],task,"admin").get("dispatch")));
        job.put("open",510);
        route.put("new_id",id());
        var laterWindow=post(h,"routing",route);
        publish.putAll(map("plan_id",route.get("new_id"),"revision",laterWindow.get("revision")));
        post(h,"routing",publish);
        var rescheduled=object(FieldDispatchAcceptance.page(h,ids[0],task,"admin").get("dispatch"));
        assertNotEquals(prior.get("starts"),rescheduled.get("starts"));
        assertEquals(b,rescheduled.get("technician_id"));
        FieldDispatchAcceptance.act(h, ids[0], task, "accept", "TaskTwo");
        route.put("new_id", id());
        var frozen = object(post(h, "routing", route).get("definition"));
        assertTrue(
                Boolean.TRUE.equals(
                        object(object(object(frozen.get("proposed")).get("visits")).get(task))
                                .get("locked")));
        var revise =
                map(
                        "operation",
                        "replan",
                        "plan_id",
                        route.get("new_id"),
                        "absent",
                        b,
                        "revision",
                        1);
        post(h, "routing", revise);
        publish.putAll(map("plan_id", route.get("new_id"), "revision", 2));
        post(h, "routing", publish);
        assertEquals(
                "accepted",
                object(FieldDispatchAcceptance.page(h, ids[0], task, "admin").get("dispatch"))
                        .get("status"));
        route.put("new_id", id());
        rejectVariants(
                h,
                "routing",
                "global",
                route,
                List.of(
                        map("date", day.minusDays(1).toString()),
                        map("date", day.plusDays(1).toString())));
        var skilled = new TerritoryRoutes.Crew("s", "a", 0, 1000, Set.of("s"), Map.of("p", 1));
        var unskilled = new TerritoryRoutes.Crew("u", "a", 0, 1000, Set.of(), Map.of());
        var noParts = new TerritoryRoutes.Crew("n", "a", 0, 1000, Set.of("s"), Map.of());
        var visit = new TerritoryRoutes.Job("v", "s", "b", 0, 1000, 20, "s", "p", false);
        var matrix =
                Map.of(DeliveryWorkspace.key("a", "b"), 10, DeliveryWorkspace.key("b", "a"), 10);
        assertEquals(
                1,
                TerritoryRoutes.solve(
                                List.of(unskilled, noParts), List.of(visit), matrix, "", false)
                        .get("missed_windows"));
        var near=new TerritoryRoutes.Crew("near","b",0,1000,Set.of("s"),Map.of("p",1));
        for(var order:List.of(List.of(skilled,near),List.of(near,skilled))){
            var solution=TerritoryRoutes.solve(order,List.of(visit),matrix,"",false);
            assertEquals("near",object(object(solution.get("visits")).get("v")).get("crew"));
        }
        assertNotNull(
                TerritoryRoutes.best(
                        skilled,
                        visit,
                        List.of(
                                new TerritoryRoutes.Visit(100, 140, "a"),
                                new TerritoryRoutes.Visit(200, 250, "b")),
                        matrix));
    }

    static void access(WorkflowSupport h) throws Exception {
        h.account("EmployeeA", "requester");
        h.account("LeadA", "agent");
        h.account("ReviewA", "agent");
        String group = h.add("sys_user_group").get("sys_id").toString(),
                user = uid(h, "EmployeeA"),
                manager = uid(h, "LeadA"),
                policy = id(),
                action = id(),
                run = id();
        for (String reviewer : List.of("LeadA", "ReviewA"))
            RecordScopesAcceptance.member(h, group, uid(h, reviewer), true);
        post(
                h,
                "studio",
                map(
                        "operation",
                        "identity",
                        "user_id",
                        user,
                        "manager_id",
                        manager,
                        "directory_id",
                        UUID.randomUUID().toString(),
                        "revision",
                        0));
        try (var p = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            p.settings.put("CONNECTOR_WRITES_ENABLED", "true");
            boolean cold = "1".equals(System.getenv("OPENSERVICE_QA_RESTART"));
            var written = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var interrupt = new AtomicBoolean();
            var writes = new AtomicInteger();
            var state = new AtomicReference<>("1");
            p.server.createContext(
                    "/rest/api/3/issue/OPS-42",
                    x -> {
                        if (x.getRequestMethod().equals("POST")) {
                            writes.incrementAndGet();
                            state.set("2");
                            if (!interrupt.get()) x.sendResponseHeaders(204, -1);
                            else if (cold) {
                                written.countDown();
                                try {
                                    release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (InterruptedException stopped) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        } else {
                            byte[] bytes =
                                    JSON.writeValueAsBytes(
                                            map(
                                                    "key",
                                                    "OPS-42",
                                                    "fields",
                                                    map("status", map("id", state.get()))));
                            x.sendResponseHeaders(200, bytes.length);
                            x.getResponseBody().write(bytes);
                        }
                        x.close();
                    });
            var spec =
                    map(
                            "operation",
                            "action",
                            "action_id",
                            action,
                            "environment",
                            "test",
                            "kind",
                            "jira_transition",
                            "alias",
                            p.alias,
                            "oauth",
                            "alias",
                            "target",
                            "OPS-42",
                            "argument",
                            "10",
                            "expected",
                            "2",
                            "expires",
                            Instant.ofEpochSecond(Instant.now().getEpochSecond() + 86400)
                                    .toString(),
                            "revision",
                            0);
            rejectVariants(
                    h,
                    "access",
                    "global",
                    spec,
                    List.of(map("argument", "unknown"), map("expected", "pending")));
            post(h, "access", spec);
            var design =
                    map(
                            "operation",
                            "design",
                            "policy_id",
                            policy,
                            "title",
                            "Privileged operations",
                            "group_id",
                            group,
                            "action_id",
                            action,
                            "environment",
                            "test",
                            "days",
                            2,
                            "revision",
                            0);
            post(h, "access", design);
            var activate = map("operation", "activate", "policy_id", policy, "revision", 1);
            bad(h, "access", "global", activate, 400);
            var sim =
                    post(
                            h,
                            "access",
                            map("operation", "simulate", "policy_id", policy, "revision", 1));
            assertEquals(2, objects(sim.get("standard")).size());
            assertEquals(3, objects(sim.get("privileged")).size());
            post(h, "access", activate);
            post(h, "access", activate);
            as(
                    h,
                    "EmployeeA",
                    "access",
                    map(
                            "operation",
                            "apply",
                            "level",
                            "privileged",
                            "policy_id",
                            policy,
                            "new_id",
                            run,
                            "revision",
                            1),
                    200);
            var advance =
                    map(
                            "operation",
                            "advance",
                            "step",
                            1,
                            "run_id",
                            run,
                            "revision",
                            1,
                            "note",
                            "Approved");
            as(h, "LeadA", "studio", advance, 200);
            advance.putAll(map("step", 2, "revision", 2));
            as(h, "LeadA", "studio", advance, 400);
            as(h, "ReviewA", "studio", advance, 200);
            post(h, "studio", TenConnectorAcceptance.call("act", run, 3, 3));
            assertEquals(1, writes.get());
            var receipt =
                    map(
                            "operation",
                            "qualify",
                            "action_id",
                            action,
                            "environment",
                            "test",
                            "run_id",
                            run,
                            "step",
                            3);
            assertEquals(
                    "fixture_or_custom_endpoint",
                    object(post(h, "access", receipt).get("definition")).get("qualification"));
            post(h, "access", receipt);
            var status = map("operation", "status", "action_id", action, "environment", "test");
            assertEquals(1, objects(post(h, "access", status).get("evidence")).size());
            String standard = id();
            as(
                    h,
                    "EmployeeA",
                    "access",
                    map(
                            "operation",
                            "apply",
                            "level",
                            "standard",
                            "policy_id",
                            policy,
                            "new_id",
                            standard,
                            "revision",
                            1),
                    200);
            as(
                    h,
                    "LeadA",
                    "studio",
                    map(
                            "operation",
                            "advance",
                            "run_id",
                            standard,
                            "step",
                            1,
                            "revision",
                            1,
                            "note",
                            "Approved"),
                    200);
            state.set("1");
            interrupt.set(true);
            if (cold)
                ConnectorRestartQualification.recover(h, p.origin(), standard, written, release);
            else {
                post(h, "studio", TenConnectorAcceptance.call("act", standard, 2, 2));
                var restarted = new Store(h.environment);
                try (var fresh = restarted.open()) {
                    PlatformFoundationTest.bind(fresh);
                    WorkflowStudio.command(
                            restarted,
                            fresh,
                            "recheck",
                            map(
                                    "operation",
                                    "recheck",
                                    "run_id",
                                    standard,
                                    "step",
                                    2,
                                    "revision",
                                    3));
                    fresh.connection.commit();
                }
                var checked=object(doc(h,"workflow-run",standard).get("definition"));
                assertEquals("awaiting_acceptance",objects(checked.get("steps")).get(1).get("state"));
                post(h,"studio",TenConnectorAcceptance.call("rearm",standard,2,4));
            }
            assertEquals(2, writes.get());
            assertEquals(
                    "complete",
                    object(doc(h, "workflow-run", standard).get("definition")).get("state"));
            receipt.putAll(map("run_id", standard, "step", 2));
            bad(h, "access", "global", receipt, 400);
            receipt.putAll(map("run_id", run, "step", 1));
            bad(h, "access", "global", receipt, 400);
            receipt.put("environment", "production");
            bad(h, "access", "global", receipt, 400);
            spec.putAll(map("revision", 1, "environment", "production"));
            bad(h, "access", "global", spec, 400);
            spec.putAll(map("environment", "test", "expires", "2020-01-01T00:00:00Z"));
            bad(h, "access", "global", spec, 400);
            String procedure = id();
            post(
                    h,
                    "access",
                    map(
                            "operation",
                            "procedure",
                            "procedure_id",
                            procedure,
                            "kind",
                            "work",
                            "title",
                            "Record equipment",
                            "group_id",
                            group,
                            "revision",
                            0));
            var extend =
                    map(
                            "operation",
                            "extend",
                            "policy_id",
                            policy,
                            "procedure_id",
                            procedure,
                            "level",
                            "privileged",
                            "revision",
                            1);
            post(h, "access", extend);
            activate.put("revision", 2);
            bad(h, "access", "global", activate, 400);
            var revised =
                    post(
                            h,
                            "access",
                            map("operation", "simulate", "policy_id", policy, "revision", 2));
            assertEquals(2, objects(revised.get("standard")).size());
            assertEquals(4, objects(revised.get("privileged")).size());
            var released = object(post(h, "access", activate).get("definition"));
            as(
                    h,
                    "EmployeeA",
                    "studio",
                    map(
                            "operation",
                            "request",
                            "template_id",
                            released.get("privileged"),
                            "revision",
                            1,
                            "new_id",
                            id()),
                    400);
            assertTrue(AccessPolicies.vendor("entra_add", "graph.microsoft.com"));
            assertFalse(AccessPolicies.vendor("entra_add", "example.com"));
            assertTrue(AccessPolicies.vendor("jira_transition", "qa.atlassian.net"));
            assertEquals(
                    "vendor_destination_verified",
                    AccessPolicies.qualification("entra_add", "graph.microsoft.com"));
            String oauth = id();
            post(
                    h,
                    "integrations",
                    map(
                            "operation",
                            "schedule",
                            "key",
                            oauth,
                            "revision",
                            0,
                            "kind",
                            "inventory",
                            "target",
                            "Test inventory",
                            "alias",
                            p.alias,
                            "path",
                            "/snapshot",
                            "minutes",
                            60,
                            "enabled",
                            false));
            String membership = id();
            spec.putAll(
                    map(
                            "action_id",
                            membership,
                            "environment",
                            "test",
                            "revision",
                            0,
                            "kind",
                            "entra_add",
                            "argument",
                            UUID.randomUUID().toString(),
                            "target",
                            UUID.randomUUID().toString(),
                            "oauth",
                            oauth,
                            "expires",
                            Instant.ofEpochSecond(Instant.now().getEpochSecond() + 86400)
                                    .toString()));
            post(h, "access", spec);
            var token =
                    post(
                            h,
                            "access",
                            map(
                                    "operation",
                                    "status",
                                    "environment",
                                    "test",
                                    "action_id",
                                    membership));
            assertTrue(token.containsKey("oauth_token_expires_at"));
            assertEquals("GroupMember.ReadWrite.All", token.get("permission"));
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                var lease = DeliveryWorkspace.key(action, "test");
                var current = EnterpriseData.get(db, "library-action", lease);
                var expired = object(current.get("definition"));
                expired.put("expires", 1L);
                EnterpriseData.save(db, "library-action", lease, current.get("revision"), expired);
                db.connection.commit();
            }
            assertTrue((Boolean) post(h, "access", status).get("authorization_expired"));
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                EnterpriseData.save(
                        db,
                        "action-evidence",
                        id(),
                        0,
                        map("binding", map("action_id", action, "environment", "production")));
                AccessPolicies.run(h.store, db, "status", status);
                var releasedPolicy = JourneyForms.definition(db, "access-release", policy);
                var template = releasedPolicy.get("standard");
                var leaseRow = EnterpriseData.get(db, "action-lease", template.toString());
                var leaseData = object(leaseRow.get("definition"));
                leaseData.put("expires", 1);
                EnterpriseData.save(
                        db,
                        "action-lease",
                        template.toString(),
                        leaseRow.get("revision"),
                        leaseData);
                assertThrows(
                        Failure.class, () -> AccessPolicies.lease(db, map("template", template)));
                AccessPolicies.lease(db, map("template", id()));
                var localRun = JourneyForms.definition(db, "workflow-run", run);
                objects(localRun.get("steps")).getLast().put("state", "pending");
                EnterpriseData.save(
                        db, "workflow-run", run, docRevision(db, "workflow-run", run), localRun);
                assertThrows(
                        Failure.class,
                        () ->
                                AccessPolicies.run(
                                        h.store,
                                        db,
                                        "qualify",
                                        map(
                                                "environment",
                                                "test",
                                                "action_id",
                                                action,
                                                "run_id",
                                                run,
                                                "step",
                                                3)));
                var stored = JourneyForms.definition(db, "access-design", policy);
                stored.put("procedures", Collections.nCopies(20, map("procedure", procedure)));
                EnterpriseData.save(db, "access-design", policy, 2, stored);
                assertThrows(
                        Failure.class,
                        () ->
                                AccessPolicies.run(
                                        h.store,
                                        db,
                                        "extend",
                                        map(
                                                "policy_id",
                                                policy,
                                                "procedure_id",
                                                procedure,
                                                "level",
                                                "both",
                                                "revision",
                                                3)));
            }
            design.put("revision", 2);
            post(h, "access", design);
            bad(
                    h,
                    "access",
                    "global",
                    map("operation", "simulate", "policy_id", policy, "revision", 3),
                    400);
        }
    }
}
