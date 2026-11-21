package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

final class EmployeeAgreements {
    private EmployeeAgreements() {}

    static Object template(Store.Db db, Map<String, Object> b) throws Exception {
        return put(
                db,
                "agreement-template",
                key(text(b, "location", 100)),
                b.get("revision"),
                map("text", text(b, "body", 8000), "days", integer(b.get("days"), 1, 3650)));
    }

    static void refresh(Store.Db db, String hr, Map<String, Object> event) throws Exception {
        String location = event.get("space").toString();
        var template = get(db, "agreement-template", key(location));
        var head = get(db, "agreement-head", hr);
        var data = object(head.get("definition"));
        if (data.containsKey("document")) {
            var previous = definition(db, "agreement", data.get("document").toString());
            if ("cancelled".equals(event.get("state"))
                    || !event.get("effective").equals(previous.get("effective"))
                    || !location.equals(previous.get("location"))) {
                previous.put("superseded", true);
                save(
                        db,
                        "agreement",
                        data.get("document").toString(),
                        get(db, "agreement", data.get("document").toString()).get("revision"),
                        previous);
                save(db, "agreement-head", hr, head.get("revision"), map());
            } else return;
        }
        if (template.get("revision").equals(0) || "cancelled".equals(event.get("state"))) return;
        var person = db.one("SELECT user_id FROM os_hr_case WHERE record_id=?", hr);
        require(person != null, "Employee case owner required");
        String employee = person.get("user_id").toString(),
                effective = event.get("effective").toString();
        var terms = object(template.get("definition"));
        String body =
                terms.get("text")
                        .toString()
                        .replace("{employee}", Service.account(db, employee).get("name").toString())
                        .replace("{location}", location)
                        .replace("{effective}", effective);
        String doc = Values.id();
        save(
                db,
                "agreement",
                doc,
                0,
                map(
                        "case_id",
                        hr,
                        "employee",
                        employee,
                        "effective",
                        effective,
                        "location",
                        location,
                        "template_version",
                        template.get("revision"),
                        "body",
                        body,
                        "digest",
                        key(body),
                        "state",
                        "pending",
                        "superseded",
                        false,
                        "retain_until",
                        LocalDate.parse(effective)
                                .plusDays((Integer) terms.get("days"))
                                .toString()));
        save(
                db,
                "agreement-head",
                hr,
                get(db, "agreement-head", hr).get("revision"),
                map("document", doc));
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("agreement_template")) return template(db, b);
        String hr = id(b, "case_id");
        boolean employee = Set.of("my_agreement", "sign_agreement").contains(op);
        var parent = EmployeeCases.record(db, hr, employee);
        if (op.equals("transfer_agreement")) {
            var transfer = definition(db, "employment-change", hr);
            require(
                    "transfer".equals(transfer.get("type")) && EmployeeOnboarding.open(parent),
                    "Choose an open employee transfer");
            String location = text(b, "location", 100);
            definition(db, "agreement-template", key(location));
            save(db, "transfer-location", hr, b.get("revision"), map("location", location));
            refresh(
                    db,
                    hr,
                    map(
                            "space",
                            location,
                            "effective",
                            transfer.get("effective"),
                            "state",
                            "scheduled"));
            return get(db, "agreement-head", hr);
        }
        if (op.equals("agreement_hold"))
            return put(
                    db,
                    "agreement-hold",
                    hr,
                    b.get("revision"),
                    map(
                            "active",
                            b.get("active"),
                            "reason",
                            text(b, "note", 400),
                            "by",
                            db.viewerId));
        if (op.equals("my_agreement")) {
            Object active = object(get(db, "agreement-head", hr).get("definition")).get("document");
            if (active == null) return map("case_id", hr, "state", "none");
            String doc = active.toString();
            return map("document_id", doc, "agreement", get(db, "agreement", doc));
        }
        String doc = id(b, "document_id");
        var data = definition(db, "agreement", doc);
        require(hr.equals(data.get("case_id")), "Agreement belongs to another case");
        if (op.equals("review_agreement")) return get(db, "agreement", doc);
        require(
                EmployeeOnboarding.open(parent)
                        && db.viewerId.equals(data.get("employee"))
                        && "pending".equals(data.get("state"))
                        && !LocalDate.now(ZoneOffset.UTC)
                                .isAfter(LocalDate.parse(data.get("retain_until").toString()))
                        && Boolean.FALSE.equals(data.get("superseded"))
                        && data.get("digest").equals(b.get("digest")),
                "Sign the current reviewed agreement");
        data.putAll(map("state", "signed", "signed_at", now(), "signed_by", db.viewerId));
        return put(db, "agreement", doc, b.get("revision"), data);
    }

    static void expire(Store.Db db, LocalDate today) throws Exception {
        for (var row : list(db, "agreement")) {
            var data = object(row.get("definition"));
            if (!data.containsKey("body")
                    || !today.isAfter(LocalDate.parse(data.get("retain_until").toString())))
                continue;
            if (Boolean.TRUE.equals(
                    object(
                                    get(db, "agreement-hold", data.get("case_id").toString())
                                            .get("definition"))
                            .get("active"))) continue;
            data.remove("body");
            data.put("state", "expired");
            db.update(
                    "UPDATE os_enterprise_document SET definition=?,revision=revision+1 WHERE"
                            + " area='agreement' AND subject=? AND revision=?",
                    JSON.writeValueAsString(data),
                    row.get("subject"),
                    row.get("revision"));
        }
    }
}
