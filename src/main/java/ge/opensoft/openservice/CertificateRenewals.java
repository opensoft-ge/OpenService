package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

final class CertificateRenewals {
  private CertificateRenewals() {}

  static Map<String, Object> certificate(Map<String, Object> body) throws Exception {
    try {
      byte[] der = Base64.getDecoder().decode(text(body, "certificate", 24000));
      var input = new ByteArrayInputStream(der);
      var cert =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      require(input.available() == 0, "Provide one DER certificate");
      return map(
          "sha256",
          DigestUtils.sha256Hex(cert.getEncoded()),
          "expires",
          cert.getNotAfter().toInstant().toString(),
          "begins",
          cert.getNotBefore().toInstant().toString(),
          "subject",
          cert.getSubjectX500Principal().getName());
    } catch (java.security.cert.CertificateException | IllegalArgumentException invalid) {
      throw new Failure(400, "Invalid DER certificate");
    }
  }

  static void openRenewal(Store.Db db, Map<String, Object> data, String fingerprint)
      throws Exception {
    var task =
        Requests.create(
            db,
            actor(db),
            map(
                "short_description",
                "Renew service certificate",
                "description",
                "Verify the replacement at every registered endpoint for " + fingerprint));
    data.put("request", task.get("sys_id"));
    CustomerImpact.notify(
        db,
        data.get("owner").toString(),
        "Certificate renewal requires verification: " + fingerprint);
  }

  static void reopen(Store.Db db, Map<String, Object> data, String fingerprint) throws Exception {
    var history = new ArrayList<Object>((List<?>) data.getOrDefault("prior_requests", List.of()));
    history.add(map("request", data.get("request"), "observations", data.get("last_verified")));
    var task =
        Requests.create(
            db,
            actor(db),
            map(
                "short_description",
                "Verify renewed certificate again",
                "description",
                fingerprint));
    Service.update(
        db,
        "sc_request",
        task,
        map("state", "fulfilling"),
        actor(db),
        db.viewerRole,
        task.get("revision").toString());
    data.putAll(
        map("prior_requests", history, "request", task.get("sys_id"), "state", "verifying"));
  }

  static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("discover_certificate")) {
      var cert = certificate(b);
      String fingerprint = cert.get("sha256").toString(),
          endpoint = text(b, "endpoint", 255),
          source = text(b, "source", 120);
      String ci = id(b, "service_ci"), owner = DutyRosters.responder(db, b.get("owner_id"));
      Service.readRecord(db, "cmdb_ci", ci);
      var row = get(db, "certificate-renewal", fingerprint);
      var data = object(row.get("definition"));
      if (data.isEmpty())
        data = map("certificate", cert, "owner", owner, "state", "inventory", "endpoints", map());
      if (!data.containsKey("request")
          && !java.time.Instant.parse(cert.get("expires").toString())
              .isAfter(java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS)))
        openRenewal(db, data, fingerprint);
      require(owner.equals(data.get("owner")), "Existing certificate owner differs");
      var endpoints = object(data.get("endpoints"));
      var target =
          object(endpoints.getOrDefault(endpoint, map("ci", ci, "sources", new ArrayList<>())));
      require(ci.equals(target.get("ci")), "Endpoint already maps to another service");
      if (!((List<?>) target.get("sources")).contains(source)) {
        var sources = new ArrayList<Object>((List<?>) target.get("sources"));
        sources.add(source);
        target.put("sources", sources);
        if ("verified".equals(data.get("state")) && !endpoints.containsKey(endpoint))
          reopen(db, data, fingerprint);
        endpoints.put(endpoint, target);
        return put(db, "certificate-renewal", fingerprint, row.get("revision"), data);
      }
      if (!data.equals(object(get(db, "certificate-renewal", fingerprint).get("definition"))))
        return put(db, "certificate-renewal", fingerprint, row.get("revision"), data);
      return row;
    }
    String fingerprint = text(b, "fingerprint", 64);
    var row = get(db, "certificate-renewal", fingerprint);
    var data = definition(db, "certificate-renewal", fingerprint);
    if (op.equals("certificate_status")) return row;
    if (op.equals("renew_certificate")) {
      require(
          db.viewerId.equals(data.get("owner")) && "inventory".equals(data.get("state")),
          "Certificate owner must start the renewal");
      var cert = certificate(b);
      var now = java.time.Instant.now();
      require(
          !cert.get("sha256").equals(fingerprint)
              && !java.time.Instant.parse(cert.get("begins").toString()).isAfter(now)
              && java.time.Instant.parse(cert.get("expires").toString()).isAfter(now),
          "Provide a currently valid replacement");
      if (!data.containsKey("request")) openRenewal(db, data, fingerprint);
      var task = Service.readRecord(db, "sc_request", data.get("request").toString());
      Service.update(
          db,
          "sc_request",
          task,
          map("state", "fulfilling"),
          actor(db),
          db.viewerRole,
          task.get("revision").toString());
      data.putAll(
          map(
              "replacement",
              cert,
              "state",
              "verifying",
              "observations",
              map(),
              "started",
              now.toString()));
    } else {
      require(
          !db.viewerId.equals(data.get("owner"))
              && Set.of("verifying", "verified").contains(data.get("state")),
          "Independent verification required");
      String endpoint = text(b, "endpoint", 255);
      require(object(data.get("endpoints")).containsKey(endpoint), "Unknown endpoint");
      long at = ServiceOutages.time(b.get("observed_at"), System.currentTimeMillis());
      require(
          at >= java.time.Instant.parse(data.get("started").toString()).toEpochMilli(),
          "Observation predates renewal");
      var observed = object(data.get("observations"));
      if (observed.containsKey(endpoint))
        require(
            at > ((Number) object(observed.get(endpoint)).get("at")).longValue(),
            "Supply a newer observation");
      var cert = certificate(b);
      observed.put(endpoint, map("fingerprint", cert.get("sha256"), "by", db.viewerId, "at", at));
      boolean complete =
          object(data.get("endpoints")).keySet().stream()
              .allMatch(
                  site ->
                      object(data.get("replacement"))
                          .get("sha256")
                          .equals(object(observed.getOrDefault(site, map())).get("fingerprint")));
      if (!complete && "verified".equals(data.get("state"))) reopen(db, data, fingerprint);
      var ticket = Service.readRecord(db, "sc_request", data.get("request").toString());
      String state = complete ? "complete" : "fulfilling";
      if (!state.equals(object(ticket.get("data")).get("state")))
        Service.update(
            db,
            "sc_request",
            ticket,
            map("state", state),
            actor(db),
            db.viewerRole,
            ticket.get("revision").toString());
      if (complete) data.put("last_verified", new LinkedHashMap<>(observed));
      data.put("state", complete ? "verified" : "verifying");
    }
    return put(db, "certificate-renewal", fingerprint, b.get("revision"), data);
  }
}
