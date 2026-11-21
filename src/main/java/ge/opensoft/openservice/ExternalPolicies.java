package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/**
 * One connector document augments the matching native citation. Source ACL groups map to local
 * groups.
 */
final class ExternalPolicies {
  private ExternalPolicies() {}

  static Object refresh(Store store, Store.Db db, Map<String, Object> b) throws Exception {
    String intentId = id(b, "intent_id"),
        alias = text(b, "alias", 80),
        path = Connectors.path(text(b, "path", 500));
    var intent = definition(db, "employee-intent", intentId);
    var binding = key(intent.get("article"), intent.get("offering"));
    var response = Connectors.fetch(store, db, map("alias", alias, "path", path));
    var doc = object(JSON.readValue((byte[]) response.get("content"), Object.class));
    var mappings = object(b.get("bindings"));
    var allowed = new TreeSet<String>();
    for (var external : batch(doc.get("allowed_groups"), 100)) {
      if (mappings.containsKey(external)) {
        String group = EnterpriseData.id(mappings.get(external));
        CatalogueRouting.group(db, group);
        allowed.add(group);
      }
    }
    return put(
        db,
        "external-policy",
        binding,
        b.get("revision"),
        map(
            "source",
            alias,
            "path",
            path,
            "origin",
            ConnectorConsole.page(db, alias).get("origin"),
            "mappings",
            mappings,
            "title",
            text(doc, "title", 200),
            "body",
            text(doc, "body", 12000),
            "version",
            text(doc, "version", 120),
            "groups",
            allowed));
  }

  static Failure accessFailure(Exception failure) {
    if (failure instanceof ServiceNowSource.SourceResponseFailure source
        && Set.of(403, 404, 410).contains(source.sourceStatus))
      return new Failure(403, "Policy access required");
    if (failure instanceof Failure local && local.status < 500)
      return new Failure(403, "Policy access required");
    return new Failure(503, "Policy source unavailable. Retry to confirm current access.");
  }

  static void cite(
      Store store, Store.Db db, Map<String, Object> intent, Map<String, Object> citation)
      throws Exception {
    var row = get(db, "external-policy", key(intent.get("article"), intent.get("offering")));
    var doc = object(row.get("definition"));
    if (doc.isEmpty()) return;
    if ("confluence".equals(doc.get("kind"))) {
      ConfluencePolicies.cite(store,db,doc,citation,row.get("revision"));
      return;
    }
    boolean allowed = false;
    for (var group : (List<?>) doc.get("groups"))
      if (db.count(
              "SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?",
              group,
              db.viewerId)
          > 0) {
        allowed = true;
        break;
      }
    if (!allowed) throw new Failure(403, "Policy access required");
    try {
      String alias = doc.get("source").toString();
      var connector = db.one("SELECT origin FROM os_connector WHERE alias=?", alias);
      require(
          connector != null && doc.get("origin").equals(connector.get("origin")),
          "External origin changed");
      var latest = Connectors.fetch(store, db, map("alias", alias, "path", doc.get("path")));
      var policy = object(JSON.readValue((byte[]) latest.get("content"), Object.class));
      require(
          doc.get("version").equals(policy.get("version"))
              && doc.get("title").equals(policy.get("title"))
              && doc.get("body").equals(policy.get("body")),
          "Refresh the changed document");
      boolean stillAllowed = false;
      for (Object external : batch(policy.get("allowed_groups"), 100)) {
        Object group = object(doc.get("mappings")).get(external);
        if (group != null
            && db.count(
                    "SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?",
                    group,
                    db.viewerId)
                > 0) {
          stillAllowed = true;
          break;
        }
      }
      require(stillAllowed, "Source access revoked");
    } catch (Exception unavailable) {
      throw accessFailure(unavailable);
    }
    citation.put(
        "external",
        map(
            "source",
            doc.get("source"),
            "path",
            doc.get("path"),
            "title",
            doc.get("title"),
            "body",
            doc.get("body"),
            "version",
            doc.get("version"),
            "index_revision",
            row.get("revision")));
  }
}
