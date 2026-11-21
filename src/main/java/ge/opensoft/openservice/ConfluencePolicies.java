package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ConfluencePolicies {
  private ConfluencePolicies() {}

  static String cloud(Map<String, Object> b) {
    String cloud = text(b, "cloud_id", 36);
    require(
        cloud.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"),
        "Choose a Confluence cloud ID");
    return cloud;
  }

  static Map<String, Object> read(Store store, Store.Db db, String alias, String cloud, String path)
      throws Exception {
    var conn = db.one("SELECT origin FROM os_connector WHERE alias=?", alias);
    require(
        conn != null && "https://api.atlassian.com".equals(conn.get("origin")),
        "Use an Atlassian OAuth connection");
    var response =
        Connectors.fetch(
            store,
            db,
            map("alias", alias, "path", "/ex/confluence/" + cloud + "/wiki/rest/api/" + path));
    return object(JSON.readValue((byte[]) response.get("content"), Object.class));
  }

  static Map<String, Object> page(Store store, Store.Db db, String alias, String cloud, String page)
      throws Exception {
    var doc =
        read(
            store,
            db,
            alias,
            cloud,
            "content/" + page + "?expand=body.storage,version,space,ancestors");
    require(page.equals(doc.get("id")) && "current".equals(doc.get("status")), "Page unavailable");
    String html = text(object(object(doc.get("body")).get("storage")), "value", 50000);
    var plain = new StringBuilder();
    new javax.swing.text.html.parser.ParserDelegator()
        .parse(
            new java.io.StringReader(html),
            new javax.swing.text.html.HTMLEditorKit.ParserCallback() {
              @Override
              public void handleText(char[] data, int pos) {
                plain.append(data).append(' ');
              }
            },
            true);
    return map(
        "title",
        text(doc, "title", 200),
        "body",
        WorkJobs.text(plain.toString(), 12000),
        "space",
        text(object(doc.get("space")), "key", 160),
        "ancestors",
        objects(doc.get("ancestors")).stream().map(x -> x.get("id")).toList(),
        "version",
        object(doc.get("version")).get("number"));
  }

  static List<String> readGroups(Store store, Store.Db db, String alias, String cloud, String page)
      throws Exception {
    var ids = new TreeSet<String>();
    int start = 0;
    while (start < 1000) {
      var result =
          read(
              store,
              db,
              alias,
              cloud,
              "content/"
                  + page
                  + "/restriction/byOperation/read?expand=restrictions.group&limit=100&start="
                  + start);
      var groups = object(object(result.get("restrictions")).get("group"));
      require(
          "read".equals(result.get("operation"))
              && groups.get("results") instanceof List<?>
              && Integer.valueOf(start).equals(groups.get("start")),
          "Incomplete page restrictions");
      var items = objects(groups.get("results"));
      for (var group : items) ids.add(text(group, "id", 160));
      if (items.isEmpty()
          || (groups.get("limit") instanceof Number n && items.size() < n.intValue()))
        return List.copyOf(ids);
      start += items.size();
    }
    throw new Failure(422, "Page group restrictions exceed the supported index size");
  }

  static Object configure(Store store, Store.Db db, String op, Map<String, Object> b)
      throws Exception {
    if (op.equals("refresh_confluence")) {
      var intent = definition(db, "employee-intent", id(b, "intent_id"));
      String binding = key(intent.get("article"), intent.get("offering"));
      var prior = get(db, "external-policy", binding);
      var data = object(prior.get("definition"));
      require(
          "confluence".equals(data.get("kind")) && data.containsKey("publisher"),
          "Configure this page with its publisher first");
      String alias = data.get("publisher").toString(),
          cloud = data.get("cloud").toString(),
          page = data.get("page").toString();
      var current = page(store, db, alias, cloud, page);
      var groups = readGroups(store, db, alias, cloud, page);
      if (current.equals(data.get("document")) && groups.equals(data.get("read_groups")))
        return prior;
      data.put("document", current);
      data.put("read_groups", groups);
      return put(db, "external-policy", binding, prior.get("revision"), data);
    }
    String cloud = cloud(b), alias = text(b, "alias", 80);
    if (op.equals("confluence_reader")) {
      String user = id(b, "user_id"), account = text(b, "account_id", 160);
      AgentPlans.account(db, user);
      require(
          account.equals(read(store, db, alias, cloud, "user/current").get("accountId")),
          "Delegated credential belongs to another account");
      return put(
          db,
          "confluence-reader",
          key(user, cloud),
          b.get("revision"),
          map("alias", alias, "account", account));
    }
    String page = text(b, "page_id", 80);
    require(page.matches("[0-9]+"), "Choose a numeric page ID");
    var intent = definition(db, "employee-intent", id(b, "intent_id"));
    String group = id(b, "group_id");
    CatalogueRouting.group(db, group);
    return put(
        db,
        "external-policy",
        key(intent.get("article"), intent.get("offering")),
        b.get("revision"),
        map(
            "kind",
            "confluence",
            "cloud",
            cloud,
            "page",
            page,
            "group",
            group,
            "publisher",
            alias,
            "read_groups",
            readGroups(store, db, alias, cloud, page),
            "document",
            page(store, db, alias, cloud, page)));
  }

  static void cite(
      Store store,
      Store.Db db,
      Map<String, Object> index,
      Map<String, Object> citation,
      Object revision)
      throws Exception {
    try {
      String cloud = index.get("cloud").toString(), page = index.get("page").toString();
      require(
          ContractorRepairs.member(db, index.get("group").toString(), db.viewerId),
          "Policy access required");
      var reader = definition(db, "confluence-reader", key(db.viewerId, cloud));
      String alias = reader.get("alias").toString();
      require(
          reader
              .get("account")
              .equals(read(store, db, alias, cloud, "user/current").get("accountId")),
          "Delegated identity changed");
      var current = page(store, db, alias, cloud, page);
      require(current.equals(index.get("document")), "Refresh the changed page");
      current.putAll(map("source", "Confluence", "page_id", page, "index_revision", revision));
      citation.put("external", current);
    } catch (Exception inaccessible) {
      throw ExternalPolicies.accessFailure(inaccessible);
    }
  }
}
