package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class GitReleases {
  private GitReleases() {}

  static Object canonical(Object value) {
    if (value instanceof Map<?, ?> entries) {
      var copy = new TreeMap<String, Object>();
      entries.forEach((k, v) -> copy.put(k.toString(), canonical(v)));
      return copy;
    }
    if (value instanceof List<?> entries)
      return entries.stream().map(GitReleases::canonical).toList();
    return value;
  }

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    String app = id(b, "app_id"), commit = text(b, "commit", 64), digest = text(b, "digest", 64);
    require(
        commit.matches("[a-f0-9]{40}|[a-f0-9]{64}") && digest.matches("[a-f0-9]{64}"),
        "Use immutable commit and artifact hashes");
    String key = key(app, commit, digest);
    var row = get(db, "git-app-release", key);
    var receipt = object(row.get("definition"));
    if (op.equals("git_status")) {
      return map(
          "receipt",
          receipt,
          "release_revision",
          get(db, "intake-release", app).get("revision"),
          "draft_revision",
          get(db, "intake-draft", app).get("revision"));
    }
    if (op.equals("git_stage")) {
      Object manifest = b.get("manifest");
      require(
          digest.equals(
              org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                  JSON.writeValueAsBytes(canonical(manifest)))),
          "Artifact hash differs from Git review");
      require(receipt.isEmpty(), "This commit was already staged");
      var staged = object(PortableApps.command(store, db, "stage_package", b));
      return put(
          db,
          "git-app-release",
          key,
          0,
          map(
              "state",
              "staged",
              "commit",
              commit,
              "digest",
              digest,
              "author",
              db.viewerId,
              "draft_revision",
              staged.get("revision"),
              "base",
              b.get("release_revision")));
    }
    require("staged".equals(receipt.get("state")), "No matching Git artifact is staged");
    var published =
        object(
            AppReleaseTests.promote(
                db,
                map(
                    "app_id",
                    app,
                    "revision",
                    receipt.get("draft_revision"),
                    "release_revision",
                    receipt.get("base"))));
    receipt.putAll(
        map(
            "state",
            "published",
            "publisher",
            db.viewerId,
            "release_revision",
            published.get("revision"),
            "at",
            now()));
    return put(db, "git-app-release", key, row.get("revision"), receipt);
  }
}
