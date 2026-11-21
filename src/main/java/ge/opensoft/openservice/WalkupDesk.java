package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class WalkupDesk {
  private WalkupDesk() {}

  static List<Map<String, Object>> visits(Store.Db db, String location) throws Exception {
    return list(db, "walkup-visit").stream()
        .filter(row -> location.equals(object(row.get("definition")).get("location")))
        .toList();
  }

  static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("open_desk"))
      return put(
          db,
          "walkup-location",
          text(b, "location", 80),
          b.get("revision"),
          map(
              "capacity",
              integer(b.get("capacity"), 1, 100),
              "interval",
              integer(b.get("minutes"), 5, 120)));
    if (op.equals("desk_compare")) {
      var locations = new TreeMap<String, Object>();
      for (var desk : list(db, "walkup-location")) {
        String site = desk.get("subject").toString();
        var summary = object(command(db, "desk_status", map("location", site)));
        summary.put("open_visits", ((List<?>) summary.remove("queue")).size());
        locations.put(site, summary);
      }
      return locations;
    }
    if (op.equals("desk_status")) {
      String site = text(b, "location", 80);
      var desk = definition(db, "walkup-location", site);
      var queue = new ArrayList<>();
      int completed = 0, missed = 0;
      long wait = 0;
      for (var row : visits(db, site)) {
        var visit = object(row.get("definition"));
        switch (visit.get("state").toString()) {
          case "completed" -> {
            completed++;
            wait +=
                ((Number) visit.get("claimed")).longValue()
                    - ((Number) visit.get("arrival")).longValue();
          }
          case "no_show" -> missed++;
          default ->
              queue.add(
                  map(
                      "id",
                      row.get("subject"),
                      "state",
                      visit.get("state"),
                      "slot",
                      visit.get("slot"),
                      "arrival",
                      visit.get("arrival"),
                      "revision",
                      row.get("revision")));
        }
      }
      queue.sort(Comparator.comparingLong(v -> ((Number) object(v).get("slot")).longValue()));
      return map(
          "desk",
          desk,
          "queue",
          queue,
          "completed",
          completed,
          "no_shows",
          missed,
          "mean_wait_ms",
          completed == 0 ? 0 : wait / completed);
    }
    String visitId = id(b, "visit_id");
    var row = get(db, "walkup-visit", visitId);
    var visit = object(row.get("definition"));
    long now = System.currentTimeMillis();
    if (op.equals("join_desk")) {
      String site = text(b, "location", 80), mode = choice(b, "mode", "walk_in", "appointment");
      if (!visit.isEmpty()) {
        require(
            db.viewerId.equals(visit.get("employee"))
                && site.equals(visit.get("original_site"))
                && mode.equals(visit.get("mode"))
                && b.get("start").equals(visit.get("requested_start"))
                && b.get("note").equals(visit.get("note")),
            "Visit identity conflict");
        return row;
      }
      var desk = definition(db, "walkup-location", site);
      long slot = now;
      if (mode.equals("appointment")) {
        slot = ChangeScheduling.instant(b.get("start"));
        long step = ((Number) desk.get("interval")).longValue() * 60000;
        require(slot >= now && slot % step == 0, "Choose a future aligned appointment");
        long selected = slot;
        long booked =
            visits(db, site).stream()
                .map(v -> object(v.get("definition")))
                .filter(
                    v ->
                        "appointment".equals(v.get("mode"))
                            && !"no_show".equals(v.get("state"))
                            && selected == ((Number) v.get("slot")).longValue())
                .count();
        require(booked < ((Number) desk.get("capacity")).longValue(), "Slot fully booked");
      }
      var req =
          Requests.create(
              db,
              actor(db),
              map(
                  "short_description",
                  "Walk-up service at " + site,
                  "description",
                  text(b, "note", 1600)));
      visit =
          map(
              "employee",
              db.viewerId,
              "request",
              req.get("sys_id"),
              "location",
              site,
              "original_site",
              site,
              "state",
              mode.equals("appointment") ? "booked" : "waiting",
              "mode",
              mode,
              "requested_start",
              b.get("start"),
              "note",
              b.get("note"),
              "slot",
              slot,
              "arrival",
              now);
    } else if (op.equals("my_visit")) {
      require(db.viewerId.equals(visit.get("employee")), "Choose your own visit");
      return row;
    } else if (op.equals("desk_arrival")) {
      require(
          db.viewerId.equals(visit.get("employee")) && "booked".equals(visit.get("state")),
          "Check in for your appointment");
      visit.putAll(map("state", "waiting", "arrival", now));
    } else if (op.equals("desk_no_show")) {
      require(
          "booked".equals(visit.get("state")) && now > ((Number) visit.get("slot")).longValue(),
          "Appointment has not been missed");
      visit.put("state", "no_show");
    } else if (op.equals("desk_transfer")) {
      require("waiting".equals(visit.get("state")), "Transfer a waiting visit");
      String target = text(b, "location", 80);
      definition(db, "walkup-location", target);
      visit.put("location", target);
    } else if (op.equals("desk_claim")) {
      require("waiting".equals(visit.get("state")), "Visit already claimed or unavailable");
      String site = visit.get("location").toString();
      long busy =
          visits(db, site).stream()
              .filter(v -> "claimed".equals(object(v.get("definition")).get("state")))
              .count();
      require(
          busy < ((Number) definition(db, "walkup-location", site).get("capacity")).longValue(),
          "Desk is at service capacity");
      var request = Service.readRecord(db, "sc_request", visit.get("request").toString());
      Service.update(
          db,
          "sc_request",
          request,
          map("state", "fulfilling"),
          actor(db),
          db.viewerRole,
          request.get("revision").toString());
      visit.putAll(map("state", "claimed", "agent", db.viewerId, "claimed", now));
    } else {
      String oldAsset = id(b, "old_asset"), newAsset = id(b, "new_asset");
      require(db.viewerId.equals(visit.get("agent")), "Assigned desk agent required");
      if ("completed".equals(visit.get("state"))) {
        require(
            oldAsset.equals(visit.get("old_asset")) && newAsset.equals(visit.get("new_asset")),
            "Swap differs from original");
        return row;
      }
      require(
          "claimed".equals(visit.get("state"))
              && !oldAsset.equals(newAsset)
              && visit.get("employee").equals(AssetCustody.latest(db, oldAsset).get("assignee_id")),
          "Verify the employee's original equipment");
      String depot = id(b, "stockroom_id");
      require(
          depot.equals(AssetCustody.latest(db, newAsset).get("stockroom_id")),
          "Select replacement stock at the desk");
      DepotTransfers.move(
          db, oldAsset, "return", "stockroom_id", depot, "Walk-up return " + visitId);
      DepotTransfers.move(
          db,
          newAsset,
          "issue",
          "username",
          Service.account(db, visit.get("employee").toString()).get("name"),
          "Walk-up replacement " + visitId);
      var request = Service.readRecord(db, "sc_request", visit.get("request").toString());
      Service.update(
          db,
          "sc_request",
          request,
          map("state", "complete"),
          actor(db),
          db.viewerRole,
          request.get("revision").toString());
      Service.audit(
          db,
          request,
          actor(db),
          "walkup.asset_swap",
          map("old_asset", oldAsset, "new_asset", newAsset));
      CustomerImpact.notify(
          db,
          visit.get("employee").toString(),
          "Walk-up swap completed for request " + visit.get("request"));
      visit.putAll(
          map(
              "state",
              "completed",
              "old_asset",
              oldAsset,
              "new_asset",
              newAsset,
              "completed_at",
              now));
    }
    return put(db, "walkup-visit", visitId, op.equals("join_desk") ? 0 : b.get("revision"), visit);
  }
}
