package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** Application endpoints share existing request authentication, CSRF and transaction boundaries. */
final class Operations {
    private Operations() {}

    static Map<String, Object> dashboard(Store store, Store.Db db, String actor) throws Exception {
        return map(
                "table",
                "operations",
                "jobs",
                WorkJobs.list(db, actor),
                "connectors",
                db.all("SELECT alias,origin,revision FROM os_connector ORDER BY alias"),
                "calendars",
                db.all("SELECT * FROM os_calendar ORDER BY calendar_id"),
                "clocks",
                db.all(
                        "SELECT sla_id,table_name,record_id,remaining_ms,state FROM os_sla ORDER BY"
                                + " last_tick DESC LIMIT 100"),
                "qualification",
                qualification(store, db));
    }

    static Map<String, Object> qualification(Store store, Store.Db db) throws Exception {
        var env = store.environment;
        return map(
                "database",
                store.postgres ? "PostgreSQL" : "SQLite",
                "schema",
                Migrations.VERSION,
                "secure_cookies",
                env.getProperty("COOKIE_SECURE", Boolean.class, true),
                "email_enabled",
                env.getProperty("DELIVERY_ENABLED", Boolean.class, false),
                "worker_enabled",
                env.getProperty("WORK_ENABLED", Boolean.class, false),
                "failed_jobs",
                db.count("SELECT COUNT(*) FROM os_work WHERE state='failed'"),
                "stale_leases",
                db.count(
                        "SELECT COUNT(*) FROM os_work WHERE state='processing' AND available_at<=?",
                        System.currentTimeMillis()),
                "active_administrators",
                db.count("SELECT COUNT(*) FROM os_user WHERE role='admin' AND active=1"),
                "external_verification",
                "Actual TLS, proxy, inbox delivery, recovery targets and operational ownership"
                        + " require deployment qualification.");
    }

    static Object execute(
            Store store,
            Store.Db db,
            String actor,
            String resource,
            String id,
            String method,
            Object input)
            throws Exception {
        if (resource.equals("audit-checkpoint") && method.equals("GET")) {
            AuditGovernance.requireViewer(db, actor);
            return AuditGovernance.checkpoint(db);
        }
        if (resource.equals("reports")) {
            if (method.equals("GET"))
                return id.isEmpty()
                        ? SavedReports.list(db, actor)
                        : SavedReports.run(db, actor, id);
            if (method.equals("DELETE")) {
                SavedReports.remove(db, actor, id);
                return map("removed", true);
            }
            return SavedReports.save(db, actor, id, input);
        }
        if(resource.equals("report-history")) {
            if(!method.equals("GET")) throw new Failure(405,"Use GET for report history");
            return ReportRuns.history(db,actor,id);
        }
        if (resource.equals("dependencies") && method.equals("GET"))
            return AssetOperations.impact(db, id);
        if (resource.equals("references") && method.equals("GET")) {
            require(!id.isEmpty(), "Specify a table");
            return Service.page(
                            db,
                            id,
                            Map.of(
                                    "sysparm_limit",
                                    "20",
                                    "sysparm_fields",
                                    "sys_id,short_description"),
                            "")
                    .rows();
        }
        Service.requireAccountAdministrator(db, actor);
        if (resource.equals("connectors") && method.equals("DELETE")) {
            Connectors.retire(db, actor, id, input);
            return map("removed", true);
        }
        if (method.equals("GET"))
            return switch (resource) {
                case "jobs" -> id.isEmpty() ? WorkJobs.list(db, actor) : WorkJobs.detail(store, db, actor, id);
                case "connectors" ->
                        db.all("SELECT alias,origin,revision FROM os_connector ORDER BY alias");
                case "calendars" -> db.all("SELECT * FROM os_calendar ORDER BY calendar_id");
                case "knowledge" -> {
                    if (id.isEmpty()) throw new Failure(405, "Specify an article for publication history");
                    yield PublishedKnowledge.history(db, actor, id, input);
                }
                case "clocks" -> db.all("SELECT * FROM os_sla ORDER BY last_tick DESC LIMIT 100");
                case "events" ->
                        db.all(
                                "SELECT * FROM os_operational_event ORDER BY last_seen DESC LIMIT"
                                        + " 100");
                case "qualification" -> qualification(store, db);
                case "preflight" -> DeploymentPreflight.inspect(store, db);
                case "password-policy" -> PasswordPolicies.status(db);
                default -> throw new Failure(405, "GET is not supported here");
            };
        require(method.equals("POST"), "Use POST for this operation");
        var body = object(input);
        switch (resource) {
            case "approval-reminders":
                return ApprovalReminders.send(db, actor, id, body);
            case "assist":
                return ProviderAssist.summarize(store, db, actor, body);
            case "assist-preview":
                return ProviderAssist.preview(store, db, actor, body);
            case "password-policy":
                PasswordPolicies.replace(db, actor, body);
                break;
            case "provisioning":
                return IdentityProvisioning.synchronize(db, actor, body);
            case "audit-access":
                AuditGovernance.grant(db, actor, body);
                break;
            case "jobs":
                if (id.isEmpty()) return WorkJobs.enqueue(store, db, actor, body);
                require(body.keySet().equals(Set.of("operation")), "Provide operation");
                WorkJobs.control(db, actor, id, WorkJobs.text(body.get("operation"), 20));
                break;
            case "connectors":
                return Connectors.save(store, db, actor, body);
            case "calendars":
                ServiceClocks.calendar(db, actor, body);
                break;
            case "clocks":
                if (id.isEmpty())
                    return ServiceClocks.start(db, actor, body, System.currentTimeMillis());
                require(body.keySet().equals(Set.of("operation")), "Provide operation");
                ServiceClocks.control(
                        db,
                        actor,
                        id,
                        WorkJobs.text(body.get("operation"), 20),
                        System.currentTimeMillis());
                break;
            case "knowledge":
                if ("restore".equals(body.get("operation")))
                    return PublishedKnowledge.restore(db, actor, id, body);
                require(body.keySet().equals(Set.of("operation")), "Provide operation");
                if ("publish".equals(body.get("operation")))
                    return PublishedKnowledge.publish(db, actor, id);
                require("retire".equals(body.get("operation")), "Choose publish or retire");
                PublishedKnowledge.retire(db, actor, id);
                break;
            case "dependencies":
                AssetOperations.link(db, actor, body);
                break;
            case "events":
                return AssetOperations.event(db, actor, body, System.currentTimeMillis());
            case "event-actions":
                return EventActions.apply(db, actor, body);
            default:
                throw new Failure(405, "POST is not supported here");
        }
        return map("saved", true);
    }

    static void form(Store store, Store.Db db, String actor, Map<String, String[]> params)
            throws Exception {
        var value = new LinkedHashMap<String, String>();
        params.forEach(
                (key, values) -> {
                    require(values.length == 1, "Repeated form field");
                    value.put(key, values[0]);
                });
        String operation = value.getOrDefault("operation", "");
        switch (operation) {
            case "notification" ->
                    WorkJobs.enqueue(
                            store,
                            db,
                            actor,
                            map(
                                    "kind",
                                    "notification",
                                    "key",
                                    id(),
                                    "payload",
                                    map(
                                            "username",
                                            value.get("username"),
                                            "message",
                                            value.get("message"))));
            case "job" ->
                    WorkJobs.control(
                            db, actor, value.get("job_id"), value.getOrDefault("action", ""));
            case "clock" ->
                    ServiceClocks.control(
                            db,
                            actor,
                            value.get("sla_id"),
                            value.getOrDefault("action", ""),
                            System.currentTimeMillis());
            case "connector" ->
                    Connectors.save(
                            store,
                            db,
                            actor,
                            map(
                                    "alias",
                                    value.get("alias"),
                                    "origin",
                                    value.get("origin"),
                                    "token",
                                    value.get("token")));
            default -> throw new Failure(400, "Unsupported operation");
        }
    }
}
