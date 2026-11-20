package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.*;
import java.util.*;

final class SalesOrders {
    private SalesOrders() {}

    static Map<String, Object> account(Store.Db db, String account) throws Exception {
        var row = CustomerAccounts.account(db, account);
        require(row.get("active").equals(1), "Customer account is inactive");
        if ("admin".equals(db.viewerRole)) return row;
        if ("agent".equals(db.viewerRole))
            require(
                    Fulfilment.member(db, row.get("group_id").toString()),
                    "Sales team membership required");
        else {
            var contact = CustomerAccounts.contact(db, db.viewerId);
            require(
                    "requester".equals(db.viewerRole)
                            && contact.get("active").equals(1)
                            && account.equals(contact.get("account_id")),
                    "Active customer contact required");
        }
        return row;
    }

    static String entitlement(
            Store.Db db, Map<String, Object> sale, String order, boolean installation)
            throws Exception {
        String account = sale.get("account").toString();
        require(
                db.count("SELECT COUNT(*) FROM os_customer_entitlement WHERE account_id=?", account)
                        < 20,
                "Customer entitlement capacity reached");
        String ent = Values.id();
        long start = System.currentTimeMillis();
        String name =
                (installation ? "Installation of " : "Product support for ")
                        + sale.get("product_name");
        db.update(
                "INSERT INTO"
                    + " os_customer_entitlement(entitlement_id,account_id,name,description,active,starts,ends,case_limit,revision)"
                    + " VALUES(?,?,?,?,1,?,?,?,1)",
                ent,
                account,
                name,
                "Customer accepted order " + order,
                start,
                start + 365L * 86400000,
                installation ? 1 : 0);
        return ent;
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("offer")) {
            var options = new TreeMap<String, String>();
            for (Object value : batch(b.get("options"), 32)) {
                var o = object(value);
                keys(o, "name", "surcharge");
                require(
                        options.put(text(o, "name", 80), money(o.get("surcharge")).toPlainString())
                                == null,
                        "Duplicate product option");
            }
            require(!options.isEmpty(), "Provide a product configuration");
            String currency = text(b, "currency", 3);
            require(currency.matches("[A-Z]{3}"), "Use a currency code");
            return put(
                    db,
                    "sale-product",
                    id(b, "product_id"),
                    b.get("revision"),
                    map(
                            "title",
                            text(b, "title", 120),
                            "currency",
                            currency,
                            "price",
                            money(b.get("price")).toPlainString(),
                            "options",
                            options,
                            "limit",
                            integer(b.get("limit"), 0, 100)));
        }
        if (op.equals("opportunity")) {
            String customer = id(b, "account_id"), ent = text(b, "entitlement_id", 32);
            account(db, customer);
            if (!ent.equals("new")) CustomerEntitlements.find(db, customer, EnterpriseData.id(ent));
            return put(
                    db,
                    "opportunity",
                    id(b, "new_id"),
                    0,
                    map(
                            "account",
                            customer,
                            "entitlement",
                            ent,
                            "title",
                            text(b, "title", 120),
                            "state",
                            "open",
                            "seller",
                            db.viewerId));
        }
        String id = id(b, "opportunity_id");
        var row = get(db, "opportunity", id);
        var sale = definition(db, "opportunity", id);
        var account = account(db, sale.get("account").toString());
        String state = sale.get("state").toString();
        if (op.equals("inspect")) return row;
        if (Set.of("quote","accept").contains(op)) {
            var bundle=object(get(db,"site-bundle-link",id).get("definition"));
            require(bundle.isEmpty() || Objects.equals(bundle.get("bundle"),b.get("bundle")), "Review and accept the bundled quote as a whole");
        }
        if (op.equals("accept") && sale.containsKey("accepted_revision")) {
            require(
                    b.get("revision").equals(sale.get("accepted_revision"))
                            && db.viewerId.equals(sale.get("customer")),
                    "Quote acceptance differs from recorded order");
            return row;
        }
        ProblemInvestigations.revision(b.get("revision"), row.get("revision"));
        if (op.equals("quote")) {
            require(Set.of("open", "offered", "review").contains(state), "Order already placed");
            if (sale.containsKey("total")) {
                var versions =
                        new ArrayList<>(objects(sale.getOrDefault("prior_quotes", List.of())));
                var previous = new LinkedHashMap<>(sale);
                previous.remove("prior_quotes");
                previous.put("revision", row.get("revision"));
                versions.add(previous);
                sale.put("prior_quotes", versions);
            }
            String product = id(b, "product_id");
            var spec = definition(db, "sale-product", product);
            var options = object(spec.get("options"));
            String option = text(b, "option", 80);
            require(options.containsKey(option), "Choose a published product option");
            int quantity = integer(b.get("quantity"), 1, 10000),
                    discount = integer(b.get("discount"), 0, 100);
            var unit = money(spec.get("price")).add(money(options.get(option)));
            var total =
                    unit.multiply(BigDecimal.valueOf(quantity))
                            .multiply(BigDecimal.valueOf(100 - discount, 2))
                            .setScale(2, RoundingMode.HALF_UP);
            sale.putAll(
                    map(
                            "state",
                            discount > (Integer) spec.get("limit") ? "review" : "offered",
                            "product",
                            product,
                            "product_revision",
                            get(db, "sale-product", product).get("revision"),
                            "product_name",
                            spec.get("title"),
                            "option",
                            option,
                            "unit",
                            unit.toPlainString(),
                            "quantity",
                            quantity,
                            "discount",
                            discount,
                            "total",
                            total.toPlainString(),
                            "currency",
                            spec.get("currency"),
                            "seller",
                            db.viewerId,
                            "seller_revision",
                            AgentPlans.account(db, db.viewerId).get("auth_revision")));
            sale.remove("approved_by");
        } else if (op.equals("approve")) {
            require(
                    state.equals("review") && !db.viewerId.equals(sale.get("seller")),
                    "Independent discount approval required");
            sale.putAll(map("state", "offered", "approved_by", db.viewerId));
        } else if (op.equals("accept")) {
            require(
                    "requester".equals(db.viewerRole) && state.equals("offered"),
                    "A customer must accept the offered revision");
            String entitlement = sale.get("entitlement").toString();
            if (entitlement.equals("new")) {
                entitlement = entitlement(db, sale, id, true);
                sale.put("installation_entitlement", entitlement);
            }
            var offering = CustomerCases.offering(db, entitlement);
            var item =
                    CustomerCases.create(
                            db,
                            entitlement,
                            map(
                                    "account_revision",
                                    account.get("revision"),
                                    "contact_revision",
                                    offering.get("contact_revision"),
                                    "entitlement_revision",
                                    object(offering.get("entitlement")).get("revision"),
                                    "summary",
                                    "Install " + sale.get("product_name"),
                                    "description",
                                    "Installation of "
                                            + sale.get("quantity")
                                            + " × "
                                            + sale.get("option"),
                                    "key",
                                    id));
            String customer = db.viewerId, role = db.viewerRole;
            var seller = AgentPlans.account(db, sale.get("seller").toString());
            ProblemInvestigations.revision(
                    sale.get("seller_revision"), seller.get("auth_revision"));
            try {
                db.viewerId = sale.get("seller").toString();
                db.viewerRole = seller.get("role").toString();
                account(db, sale.get("account").toString());
                String parent = item.get("sys_id").toString();
                WorkTasks.command(
                        db,
                        "customer_case",
                        parent,
                        map(
                                "operation",
                                "configure",
                                "revision",
                                0,
                                "record_revision",
                                item.get("revision"),
                                "close_at",
                                "closed"));
                WorkTasks.command(
                        db,
                        "customer_case",
                        parent,
                        map(
                                "operation",
                                "create",
                                "revision",
                                WorkTasks.plan(db, parent).get("revision"),
                                "record_revision",
                                WorkTasks.record(db, "customer_case", parent).get("revision"),
                                "summary",
                                "Install " + sale.get("product_name"),
                                "details",
                                "Accepted order " + id,
                                "group_id",
                                account.get("group_id"),
                                "required",
                                true,
                                "predecessors",
                                List.of()));
                String task =
                        db.one("SELECT task_id FROM os_case_task WHERE parent_id=?", parent)
                                .get("task_id")
                                .toString();
                sale.putAll(
                        map(
                                "state",
                                "ordered",
                                "order_id",
                                Values.id(),
                                "case_id",
                                parent,
                                "installation_task",
                                task,
                                "customer",
                                customer,
                                "accepted_revision",
                                b.get("revision")));
            } finally {
                db.viewerId = customer;
                db.viewerRole = role;
            }
        } else {
            require(state.equals("ordered"), "Choose a placed order");
            var task = FieldPlanning.task(db, sale.get("installation_task").toString());
            require(
                    "complete".equals(task.get("state")),
                    "Complete the installation task before registering equipment");
            String asset = id(b, "asset_id");
            AssetCustody.record(db, asset);
            if (sale.containsKey("installation_entitlement"))
                sale.put("entitlement", entitlement(db, sale, id, false));
            save(
                    db,
                    "installation-account",
                    asset,
                    0,
                    map(
                            "account",
                            sale.get("account"),
                            "asset",
                            asset,
                            "order_id",
                            sale.get("order_id"),
                            "entitlement",
                            sale.get("entitlement"),
                            "installed_at",
                            now()));
            sale.putAll(map("state", "installed", "asset", asset));
        }
        return put(db, "opportunity", id, b.get("revision"), sale);
    }
}
