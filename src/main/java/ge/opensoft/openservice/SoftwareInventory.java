package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;

import java.time.LocalDate;
import java.util.*;

final class SoftwareInventory {
    private SoftwareInventory() {}

    static void replace(Store.Db db, Map<String,Object> body) throws Exception {
        keys(body, "operation", "product", "revision", "installations");
        String product = WorkJobs.text(body.get("product"), 160);
        var devices = new TreeMap<String,String>();
        for (Object entry : EnterpriseData.batch(body.get("installations"), 100)) {
            var install = object(entry);
            keys(install, "asset_id", "user_id");
            String device = EnterpriseData.id(install.get("asset_id"));
            String user = EnterpriseData.id(install.get("user_id"));
            AssetCustody.record(db, device);
            Service.account(db, user);
            require(devices.put(device, user) == null, "Report each asset once per product");
        }
        save(db, "installations", ConnectorSynchronization.hash(product), body.get("revision"),
                map("product", product, "devices", devices, "observed_by", db.viewerId, "received", now()));
    }

    static List<Map<String,Object>> reconcile(Store.Db db) throws Exception {
        var issued = new HashMap<String,Set<Object>>();
        for (var row : list(db, "entitlement")) {
            var entitlement = object(row.get("definition"));
            if (LocalDate.parse(entitlement.get("expires_on").toString()).isBefore(LocalDate.now())) continue;
            issued.computeIfAbsent(entitlement.get("product").toString(), ignored -> new HashSet<>())
                    .addAll((List<?>) entitlement.get("users"));
        }
        var result = new ArrayList<Map<String,Object>>();
        for (var row : list(db, "installations")) {
            var snapshot = object(row.get("definition"));
            var users = new TreeSet<>(object(snapshot.get("devices")).values().stream().map(Object::toString).toList());
            var seats = issued.getOrDefault(snapshot.get("product"), Set.of());
            var unused = new HashSet<>(seats);
            unused.removeAll(users);
            users.removeAll(seats);
            result.add(map("revision", row.get("revision"), "snapshot", snapshot,
                    "users_without_seats", users, "allocated_without_installation", unused));
        }
        return result;
    }
}
