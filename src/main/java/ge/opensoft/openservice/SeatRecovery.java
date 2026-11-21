package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class SeatRecovery {
    private SeatRecovery() {}

    static Map<String, Object> publisher(Store.Db db, String license) throws Exception {
        var binding = definition(db, "seat-publisher", license);
        require(
                binding.get("origin")
                        .equals(
                                ConnectorConsole.page(db, binding.get("alias").toString())
                                        .get("origin")),
                "Publisher destination changed. Review the binding.");
        return binding;
    }

    static void allowed(Store.Db db, String license, String user) throws Exception {
        var notice = object(get(db, "seat-notice", key(license, user)).get("definition"));
        require(
                !notice.containsKey("exception_until")
                        || LocalDate.parse(notice.get("exception_until").toString())
                                .isBefore(LocalDate.now(ZoneOffset.UTC)),
                "A current retention exception protects this seat");
    }

    static void candidate(Store.Db db, String license, String user) throws Exception {
        var rights = definition(db, "entitlement", license);
        allowed(db, license, user);
        var notice = definition(db, "seat-notice", key(license, user));
        require("notified".equals(notice.get("state")), "An active seat notice is required");
        var review =
                object(
                        InvoiceSettlement.command(
                                db, "reclaim", map("product", rights.get("product"))));
        require(
                objects(review.get("candidates")).stream()
                        .anyMatch(
                                r ->
                                        license.equals(r.get("license_id"))
                                                && user.equals(r.get("user_id"))),
                "Subscriber is no longer a reclamation candidate");
    }

    static void guardWrite(Store.Db db, String job) throws Exception {
        var link = object(get(db, "seat-write", job).get("definition"));
        if (link.isEmpty()) return;
        String license = link.get("license").toString();
        var binding = publisher(db, license);
        require(
                Objects.equals(binding.get("action"), link.get("action")),
                "Seat removal action changed");
        candidate(db, license, link.get("user").toString());
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        if (op.equals("notices"))
            return list(db, "seat-notice").stream()
                    .map(r -> object(r.get("definition")))
                    .filter(n -> db.viewerId.equals(n.get("user")))
                    .limit(integer(b.get("limit"), 1, 200))
                    .toList();
        String license = id(b, "license_id");
        var rights = definition(db, "entitlement", license);
        if (op.equals("publisher")) {
            String alias = text(b, "alias", 80);
            var connector = ConnectorConsole.page(db, alias);
            String template = text(b, "subscriber_path", 2000);
            require(template.indexOf("{user_id}") >= 0, "Include {user_id} in the subscriber path");
            Connectors.path(template.replace("{user_id}", license));
            return put(
                    db,
                    "seat-publisher",
                    license,
                    b.get("revision"),
                    map(
                            "alias",
                            alias,
                            "origin",
                            connector.get("origin"),
                            "usage_path",
                            Connectors.path(b.get("usage_path")),
                            "subscriber_path",
                            template));
        }
        if (op.equals("bind_removal")) {
            var binding = publisher(db, license);
            String action = id(b, "action_id");
            var spec = ConnectorActions.find(db, action);
            require(
                    binding.get("alias").equals(spec.get("alias")) && spec.get("active").equals(1),
                    "Choose an active action on the bound publisher connection");
            var names = new HashSet<>();
            for (var field : objects(spec.get("inputs"))) names.add(field.get("key"));
            require(
                    names.equals(Set.of("user_id", "product")),
                    "Removal action requires product and user_id inputs");
            binding.put("action", action);
            return put(db, "seat-publisher", license, b.get("revision"), binding);
        }
        if (Set.of("preview_removal", "remove_at_provider").contains(op)) {
            var binding = publisher(db, license);
            require(binding.containsKey("action"), "Bind the provider removal action");
            String action = binding.get("action").toString(), user = id(b, "user_id");
            var spec = ConnectorActions.find(db, action);
            require(
                    binding.get("alias").equals(spec.get("alias")),
                    "Removal action connection changed");
            candidate(db, license, user);
            var values = map("user_id", user, "product", rights.get("product"));
            String token = id(b, "new_id");
            if (op.equals("preview_removal"))
                return ConnectorActions.preview(
                        store, db, action, map("values", values, "key", token));
            String noticeId = key(license, user);
            var notice = definition(db, "seat-notice", noticeId);
            if (notice.containsKey("job")) {
                require(
                        token.equals(notice.get("removal_key")),
                        "Reconcile the existing removal job");
                return map("job_id", notice.get("job"));
            }
            var job =
                    ConnectorActions.queue(
                            store,
                            db,
                            action,
                            map(
                                    "values",
                                    values,
                                    "key",
                                    token,
                                    "confirm",
                                    b.get("confirm"),
                                    "state",
                                    b.get("state"),
                                    "preview_token",
                                    b.get("preview_token")));
            save(
                    db,
                    "seat-write",
                    job.get("job_id").toString(),
                    0,
                    map("license", license, "user", user, "action", action));
            notice.putAll(map("job", job.get("job_id"), "removal_key", token));
            save(
                    db,
                    "seat-notice",
                    noticeId,
                    get(db, "seat-notice", noticeId).get("revision"),
                    notice);
            return job;
        }
        if (op.equals("import_usage")) {
            var binding = publisher(db, license);
            var fetched =
                    Connectors.fetch(
                            store,
                            db,
                            map("alias", binding.get("alias"), "path", binding.get("usage_path")));
            var report = object(JSON.readValue((byte[]) fetched.get("content"), Object.class));
            keys(report, "product", "as_of", "observations");
            require(
                    rights.get("product").equals(report.get("product")),
                    "Report does not cover this product");
            return InvoiceSettlement.command(
                    db,
                    "seat_usage",
                    map(
                            "product",
                            report.get("product"),
                            "source",
                            binding.get("alias"),
                            "as_of",
                            report.get("as_of"),
                            "observations",
                            report.get("observations"),
                            "revision",
                            b.get("revision")));
        }
        if (op.equals("savings")) {
            int recovered = 0, avoided = 0;
            var credits = new ArrayList<>();
            var totals = new TreeMap<String, BigDecimal>();
            for (var row : list(db, "seat-receipt")) {
                var r = object(row.get("definition"));
                if (license.equals(r.get("license"))) {
                    recovered++;
                    if (r.containsKey("replacement")) avoided++;
                }
            }
            for (var row : list(db, "seat-credit")) {
                var credit = object(row.get("definition"));
                if (license.equals(credit.get("license"))) {
                    credits.add(credit);
                    totals.merge(
                            credit.get("currency").toString(),
                            money(credit.get("amount")),
                            BigDecimal::add);
                }
            }
            return map(
                    "recovered_seats",
                    recovered,
                    "purchases_avoided",
                    avoided,
                    "posted_invoice_credits",
                    credits,
                    "invoice_savings_by_currency",
                    totals);
        }
        if (op.equals("record_credit")) {
            String ref = text(b, "reference", 120);
            var credit = definition(db, "posted-expense", key(ref));
            require("credit".equals(credit.get("kind")), "Select a posted credit");
            var exports =
                    objects(
                            definition(db, "finance-settlement", credit.get("invoice").toString())
                                    .get("exports"));
            var line =
                    exports.stream()
                            .filter(r -> credit.get("batch").equals(r.get("export_id")))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new Failure(
                                                    409,
                                                    "The posted credit has no matching invoice"
                                                        + " export"));
            require(
                    object(line.get("lines")).keySet().equals(Set.of(rights.get("product"))),
                    "Select a credit exclusively for this product");
            require(
                    list(db, "seat-receipt").stream()
                            .anyMatch(
                                    r ->
                                            license.equals(
                                                    object(r.get("definition")).get("license"))),
                    "Verify a recovered seat first");
            return put(
                    db,
                    "seat-credit",
                    key(ref),
                    0,
                    map(
                            "license",
                            license,
                            "product",
                            rights.get("product"),
                            "amount",
                            credit.get("amount"),
                            "reference",
                            ref,
                            "invoice",
                            credit.get("invoice"),
                            "currency",
                            line.get("currency"),
                            "project",
                            credit.get("project")));
        }
        if (op.equals("waitlist")) {
            require(
                    !((List<?>) rights.get("users")).contains(db.viewerId)
                            && ((List<?>) rights.get("users")).size()
                                    >= ((Number) rights.get("seats")).intValue(),
                    "Choose an exhausted entitlement you do not hold");
            String k = key(license, db.viewerId);
            var prev = get(db, "seat-wait", k);
            if ("waiting".equals(object(prev.get("definition")).get("state"))) return prev;
            return put(
                    db,
                    "seat-wait",
                    k,
                    prev.get("revision"),
                    map(
                            "license",
                            license,
                            "user",
                            db.viewerId,
                            "created",
                            System.currentTimeMillis(),
                            "state",
                            "waiting"));
        }
        String user = id(b, "user_id"), noticeId = key(license, user);
        var row = get(db, "seat-notice", noticeId);
        var notice = object(row.get("definition"));
        if (op.equals("exception")) {
            require(
                    !notice.isEmpty() && ((List<?>) rights.get("users")).contains(user),
                    "Select a current seat notice");
            String until = ProjectDelivery.date(b.get("expires_on"));
            require(SupplierGovernance.valid(until), "Choose a current exception expiry");
            notice.putAll(
                    map(
                            "exception_until",
                            until,
                            "reason",
                            text(b, "note", 500),
                            "approved_by",
                            db.viewerId));
            return put(db, "seat-notice", noticeId, b.get("revision"), notice);
        }
        String product = PurchasingControls.canonical(db, b.get("product"));
        require(product.equals(rights.get("product")), "Choose the entitlement's product");
        if (op.equals("notice")) {
            var review = object(InvoiceSettlement.command(db, "reclaim", b));
            require(
                    objects(review.get("candidates")).stream()
                            .anyMatch(
                                    r ->
                                            license.equals(r.get("license_id"))
                                                    && user.equals(r.get("user_id"))),
                    "Select an inactive subscriber");
            if (!notice.isEmpty() && !"released".equals(notice.get("state"))) return row;
            CustomerImpact.notify(
                    db,
                    user,
                    "Your unused "
                            + product
                            + " seat is under review. Contact the license owner if you still need"
                            + " access.");
            return put(
                    db,
                    "seat-notice",
                    noticeId,
                    row.get("revision"),
                    map(
                            "license",
                            license,
                            "product",
                            product,
                            "user",
                            user,
                            "at",
                            now(),
                            "state",
                            "notified"));
        }
        require(!notice.isEmpty(), "Notify the owner before release");
        if ("released".equals(notice.get("state"))) return row;
        allowed(db, license, user);
        var binding = publisher(db, license);
        String path = binding.get("subscriber_path").toString().replace("{user_id}", user);
        var result = Connectors.fetch(store, db, map("alias", binding.get("alias"), "path", path));
        var provider = object(JSON.readValue((byte[]) result.get("content"), Object.class));
        require(
                product.equals(provider.get("product"))
                        && user.equals(provider.get("user_id"))
                        && Boolean.FALSE.equals(provider.get("assigned")),
                "Provider must confirm removal of this specific subscription");
        InvoiceSettlement.command(
                db,
                "release_unused",
                map(
                        "product",
                        product,
                        "license_id",
                        license,
                        "user_id",
                        user,
                        "usage_revision",
                        b.get("usage_revision"),
                        "revision",
                        b.get("revision"),
                        "confirm",
                        true));
        var receipt =
                map(
                        "product",product,
                        "license",
                        license,
                        "user",
                        user,
                        "at",
                        now(),
                        "alias",
                        binding.get("alias"),
                        "path",
                        path,
                        "connection_revision",
                        result.get("revision"));
        var waiting =
                list(db, "seat-wait").stream()
                        .filter(
                                r -> {
                                    var d = object(r.get("definition"));
                                    return license.equals(d.get("license"))
                                            && "waiting".equals(d.get("state"));
                                })
                        .sorted(
                                Comparator.comparingLong(
                                        r ->
                                                ((Number)
                                                                object(r.get("definition"))
                                                                        .get("created"))
                                                        .longValue()))
                        .toList();
        for (var wait : waiting) {
            var request = object(wait.get("definition"));
            Object replacement = request.get("user");
            if (((List<?>) rights.get("users")).contains(replacement)
                    || db.count(
                                    "SELECT COUNT(*) FROM os_user WHERE active=1 AND user_id=?",
                                    replacement)
                            == 0) continue;
            AssetPlanning.command(
                    db,
                    map(
                            "operation",
                            "allocate",
                            "license_id",
                            license,
                            "user_id",
                            replacement,
                            "revision",
                            get(db, "entitlement", license).get("revision")));
            request.put("state", "fulfilled");
            save(db, "seat-wait", wait.get("subject").toString(), wait.get("revision"), request);
            receipt.put("replacement", replacement);
            break;
        }
        save(db, "seat-receipt", key(noticeId, row.get("revision")), 0, receipt);
        notice.putAll(map("state", "released", "receipt", receipt));
        return put(db, "seat-notice", noticeId, row.get("revision"), notice);
    }
}
