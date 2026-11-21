package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class CustomerImpact {
    private CustomerImpact() {}

    static void notify(Store.Db db, String user, String message) throws Exception {
        Notifications.deliver(
                db,
                Values.id(),
                map("username", Service.account(db, user).get("name"), "message", message),
                System.currentTimeMillis());
    }

    static Object topology(Store store, Store.Db db, String operation, Map<String, Object> body)
            throws Exception {
        if (!Set.of("signals", "recover").contains(operation)
                || db.count("SELECT COUNT(*) FROM os_enterprise_document WHERE area='site-monitor'")
                        == 0) return ServiceTopology.command(db, operation, body);
        String source = text(body, "source", 80), resource = text(body, "resource_id", 200);
        String identity = ServiceTopology.identity(source, resource);
        var before = object(get(db, "monitor-state", identity).get("definition"));
        var result = ServiceTopology.command(db, operation, body);
        if (operation.equals("signals")) {
            var state = object(object(result).get("definition"));
            if (state.containsKey("episode")) {
                String episode = state.get("episode").toString();
                if (get(db, "site-outage", episode).get("revision").equals(0)) {
                    String component =
                            object(get(db, "component-alias", identity).get("definition"))
                                    .getOrDefault("component", identity)
                                    .toString();
                    if (FederatedMaps.graph(db).facts().containsKey(component))
                        command(
                                store,
                                db,
                                "outage",
                                map(
                                        "new_id",
                                        episode,
                                        "component",
                                        component,
                                        "title",
                                        "Service interruption at your installation"));
                }
            }
        } else if (before.containsKey("episode")
                && !get(db, "site-outage", before.get("episode").toString())
                        .get("revision")
                        .equals(0)) {
            command(
                    store,
                    db,
                    "recover",
                    map(
                            "new_id",
                            before.get("episode"),
                            "note",
                            "Monitoring confirmed service restoration at your installation."));
        }
        return result;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        if (op.equals("bind_site")) {
            String bundle = id(b, "bundle_id"),
                    site = text(b, "site", 120),
                    service = text(b, "service", 240),
                    contact = id(b, "contact_id");
            var order = definition(db, "site-bundle", bundle);
            var line = object(object(order.get("sites")).get(site));
            var sale = definition(db, "opportunity", line.get("opportunity").toString());
            require(
                    "installed".equals(sale.get("state"))
                            && sale.get("entitlement").equals(id(b, "entitlement_id")),
                    "Select an installed site and its support entitlement");
            var person = CustomerAccounts.contact(db, contact);
            require(
                    person.get("active").equals(1)
                            && person.get("account_id").equals(order.get("account")),
                    "Choose an active customer contact at this account");
            require(
                    FederatedMaps.graph(db).roots().containsKey(service),
                    "Select a mapped service");
            String calendar = text(b, "calendar", 80);
            require(
                    db.one("SELECT calendar_id FROM os_calendar WHERE calendar_id=?", calendar)
                            != null,
                    "Choose an installed support calendar");
            return put(
                    db,
                    "site-monitor",
                    key(bundle, site),
                    b.get("revision"),
                    map(
                            "bundle",
                            bundle,
                            "site",
                            site,
                            "asset",
                            sale.get("asset"),
                            "account",
                            order.get("account"),
                            "entitlement",
                            sale.get("entitlement"),
                            "contact",
                            contact,
                            "service",
                            service,
                            "calendar",
                            calendar,
                            "minutes",
                            integer(b.get("minutes"), 1, 43200)));
        }
        if (op.equals("my_impact")) {
            int limit = integer(b.get("limit"), 1, 200);
            var results = new ArrayList<>();
            for (var row : list(db, "site-outage"))
                for (var site : objects(object(row.get("definition")).get("sites"))) {
                    try {
                        CustomerReplies.record(db, site.get("case_id").toString());
                    } catch (Failure denied) {
                        if (denied.status == 403 || denied.status == 404) continue;
                        throw denied;
                    }
                    results.add(site);
                    if (results.size() == limit) return results;
                }
            return results;
        }
        String id = id(b, "new_id");
        var row = get(db, "site-outage", id);
        var data = object(row.get("definition"));
        if (op.equals("outage") || op.equals("circuit_outage")) {
            String node = text(b, "component", 200), title = text(b, "title", 160);
            if (!data.isEmpty()) {
                require(
                        node.equals(data.get("component")) && title.equals(data.get("title")),
                        "Outage key already used");
                return row;
            }
            var graph = FederatedMaps.graph(db);
            boolean circuit=op.equals("circuit_outage");
            require(circuit || graph.facts().containsKey(node), "Choose an observed component");
            var affected = MapHealth.impact(graph, node);
            var sites = new ArrayList<>();
            var skipped = new ArrayList<>();
            long started = System.currentTimeMillis();
            for (var binding : list(db, "site-monitor")) {
                var site = object(binding.get("definition"));
                if (circuit ? !binding.get("subject").equals(b.get("circuit")) : !affected.contains(site.get("service"))) continue;
                String user = site.get("contact").toString(),
                        ent = site.get("entitlement").toString();
                String admin = db.viewerId, role = db.viewerRole;
                Map<String, Object> ticket;
                try {
                    db.viewerId = user;
                    db.viewerRole = "requester";
                    Map<String, Object> offering;
                    try {
                        offering = CustomerCases.offering(db, ent);
                    } catch (Failure unavailable) {
                        if (unavailable.status != 403 && unavailable.status != 404)
                            throw unavailable;
                        skipped.add(
                                map(
                                        "site_binding",
                                        binding.get("subject"),
                                        "reason",
                                        "Contact or entitlement unavailable"));
                        continue;
                    }
                    require(
                            site.get("account")
                                    .equals(CustomerAccounts.contact(db, user).get("account_id")),
                            "Site contact changed");
                    ticket =
                            CustomerCases.create(
                                    db,
                                    ent,
                                    map(
                                            "summary",
                                            title,
                                            "description",
                                            "Service interruption at " + site.get("site"),
                                            "key",
                                            key(id, binding.get("subject")).substring(0, 32),
                                            "account_revision",
                                            object(offering.get("account")).get("revision"),
                                            "contact_revision",
                                            offering.get("contact_revision"),
                                            "entitlement_revision",
                                            object(offering.get("entitlement")).get("revision")),
                                    Service.account(db, admin).get("name").toString());
                } finally {
                    db.viewerId = admin;
                    db.viewerRole = role;
                }
                Service.audit(
                        db,
                        ticket,
                        actor(db),
                        "customer.proactive_outage",
                        map("outage", id, "asset", site.get("asset")));
                var clock =
                        ServiceClocks.start(
                                db,
                                admin,
                                map(
                                        "table",
                                        "customer_case",
                                        "record_id",
                                        ticket.get("sys_id"),
                                        "calendar",
                                        site.get("calendar"),
                                        "minutes",
                                        site.get("minutes"),
                                        "purpose",
                                        "resolution"),
                                started);
                sites.add(
                        map(
                                "site",
                                site.get("site"),
                                "account",
                                site.get("account"),
                                "contact",
                                user,
                                "case_id",
                                ticket.get("sys_id"),
                                "sla_id",
                                clock.get("sla_id"),
                                "budget_minutes",
                                site.get("minutes"),
                                "started",
                                started,
                                "state",
                                "interrupted"));
                notify(db, user, "A support case has been opened for your affected installation.");
            }
            if(circuit && sites.isEmpty())return map("definition",map("sites",sites,"skipped",skipped));
            return put(
                    db,
                    "site-outage",
                    id,
                    0,
                    map(
                            "component",
                            node,"circuit",circuit ? b.get("circuit") : "",
                            "title",
                            title,
                            "state",
                            "open",
                            "sites",
                            sites,
                            "skipped",
                            skipped));
        }
        require(!data.isEmpty(), "Choose an existing outage");
        if ("recovered".equals(data.get("state"))) return row;
        require("".equals(data.getOrDefault("circuit","")) || op.equals("restore_circuit"),"Verify the site transaction first");
        String note = text(b, "note", 1000);
        for (var site : objects(data.get("sites"))) {
            var item = Service.readRecord(db, "customer_case", site.get("case_id").toString());
            CustomerReplies.publish(
                    store,
                    db,
                    item.get("sys_id").toString(),
                    map(
                            "record_revision",
                            item.get("revision"),
                            "key",
                            key(id, site.get("case_id"), "recovery").substring(0, 32),
                            "body",
                            note,
                            "email",
                            false));
            var clock = db.one("SELECT state FROM os_sla WHERE sla_id=?", site.get("sla_id"));
            if (clock != null && Set.of("running", "paused").contains(clock.get("state")))
                ServiceClocks.control(
                        db,
                        db.viewerId,
                        site.get("sla_id").toString(),
                        "complete",
                        System.currentTimeMillis());
            String recipient = site.get("contact").toString();
            var eligible =
                    db.one(
                            "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND"
                                + " role='requester' AND password_reset_required=0",
                            recipient);
            if (eligible != null
                    && CustomerReplies.canReadAsCustomer(
                            db, recipient, item.get("sys_id").toString()))
                notify(
                        db,
                        recipient,
                        "Service at your installation has recovered. Open your support case for the"
                            + " update.");
            site.putAll(
                    map(
                            "state",
                            "recovered",
                            "recovery",
                            note,
                            "recovered_at",
                            System.currentTimeMillis()));
        }
        data.put("state", "recovered");
        return put(db, "site-outage", id, row.get("revision"), data);
    }
}
