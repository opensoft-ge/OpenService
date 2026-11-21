#!/usr/bin/env python3
"""Read provider histories for an authorized, time-bounded connector qualification."""
import argparse
import datetime as dt
import json
import os
import re
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def instant(value):
    date = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if date.utcoffset() is None:
        raise ValueError("Include a UTC offset in each timestamp")
    return date.astimezone(dt.timezone.utc)


def fetch(url, auth):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "Authorization": auth})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
        data = response.read(8_000_001)
        if len(data) > 8_000_000:
            raise ValueError("Provider response exceeded the observation limit")
        return json.loads(data)


def record_match(matches, event_id, timestamp):
    if not isinstance(event_id, str) or not event_id.strip():
        raise ValueError("Matching provider events must have an ID")
    if matches.setdefault(event_id, timestamp) != timestamp:
        raise ValueError("Provider event changed between pages")


def qualification(config, read=fetch):
    """Read only the designated device or page. Return evidence without page or device contents."""
    provider = config["provider"]
    if provider == "intune":
        managed = str(uuid.UUID(config["managed_device_id"]))
        directory = str(uuid.UUID(config["entra_device_id"]))
        url = "https://graph.microsoft.com/v1.0/deviceManagement/managedDevices/" + managed
        device = read(url + "?$select=id,azureADDeviceId,operatingSystem,lastSyncDateTime", os.environ[config["auth_env"]])
        if (str(uuid.UUID(device["id"])) != managed or str(uuid.UUID(device["azureADDeviceId"])) != directory
                or device["operatingSystem"] != "Windows"):
            raise ValueError("Designated Windows device identities did not match")
        if "max_sync_age_hours" in config:
            hours = config["max_sync_age_hours"]
            age = dt.datetime.now(dt.timezone.utc) - instant(device["lastSyncDateTime"])
            if type(hours) is not int or not 1 <= hours <= 720 or not dt.timedelta(0) <= age <= dt.timedelta(hours=hours):
                raise ValueError("Intune inventory is outside the designated freshness window")
        registration = config.get("windows_registration")
        if registration is not None:
            if (str(uuid.UUID(registration["device_id"])) != directory or
                    str(uuid.UUID(registration["tenant_id"])) != str(uuid.UUID(config["tenant_id"]))):
                raise ValueError("Windows registration differs from the designated device or tenant")
        return {"provider": provider, "managed_device_id": managed, "entra_device_id": directory,
                "matched_supplied_windows_registration": registration is not None,
                "last_sync": instant(device["lastSyncDateTime"]).isoformat(), "observation": "designated_device_read"}
    if provider == "confluence":
        site = str(uuid.UUID(config["cloud_id"]))
        page = config["page_id"]
        if not isinstance(page, str) or not re.fullmatch(r"[1-9][0-9]*", page):
            raise ValueError("Choose a numeric page ID")
        origin = "https://api.atlassian.com/ex/confluence/" + site + "/wiki"
        reader = os.environ[config["reader_env"]]
        who = read(origin + "/rest/api/user/current", reader)
        if not config["reader_account"] or who.get("accountId") != config["reader_account"]:
            raise ValueError("Delegated reader account did not match")
        if "control_page_id" in config:
            control = str(config["control_page_id"])
            if control == page or not re.fullmatch(r"[1-9][0-9]*", control):
                raise ValueError("Choose a separate control page")
            accessible = read(origin + "/api/v2/pages/" + control, reader)
            if accessible.get("id") != control or accessible.get("status") != "current":
                raise ValueError("Control page is unavailable to the delegated reader")
        url = origin + "/api/v2/pages/" + page
        source = read(url, os.environ[config["publisher_env"]])
        if source["id"] != page or source["status"] != "current":
            raise ValueError("Publisher could not establish a current page")
        try:
            visible = read(url, reader)
        except urllib.error.HTTPError as error:
            if error.code not in (403, 404):
                raise
            allowed = False
        else:
            if (visible["id"],visible["status"],visible["version"]["number"]) != (page,"current",source["version"]["number"]):
                raise ValueError("Page changed during the permission check")
            allowed = True
        if config["expected_reader"] != ("allowed" if allowed else "denied"):
            raise ValueError("Reader visibility differs from the expected phase")
        return {"provider": provider, "site": site, "page": page, "version": source["version"]["number"],
                "reader_account": who["accountId"], "reader_access": config["expected_reader"],
                "control_page": config.get("control_page_id"), "observation": "delegated_page_read"}
    if "attempt_journal" in config:
        return verify_recovery(config, read)
    return observe(config, read)


def verify_recovery(config, read):
    """Join a native journal to a fresh, independently queried provider history."""
    job = config["attempt_journal"]
    recovery, uncertain = job["readback"], job["uncertain_write"]
    provider = config["provider"]
    if provider == "jira":
        origin = "https://" + config["site"]
        target = ("jira_transition", config["issue"], config["to_status"])
        actual = (job["kind"], job["issue"], job["to_status"])
    elif provider == "entra":
        origin = "https://graph.microsoft.com"
        target = ("entra_add", config["group"].lower(), config["member"].lower())
        actual = (job["kind"], job["group"].lower(), job["member"].lower())
    else:
        raise ValueError("Choose a native Jira transition or Entra membership addition")
    if actual != target or {job["origin"], recovery["origin"]} != {origin}:
        raise ValueError("Journal target or origin differs from the designated provider")
    correlation = str(uuid.UUID(job["correlation_id"]))
    if {uncertain.get("correlation_id"), recovery.get("correlation_id"), config.get("correlation_id", correlation)} != {correlation}:
        raise ValueError("Write, uncertainty and readback must share the provider correlation")
    start, end = instant(config["start"]), instant(config["end"])
    before, interrupted, after = (instant(job["recorded_at"]), instant(uncertain["recorded_at"]), instant(recovery["observed_at"]))
    if not start <= before <= interrupted <= after < end:
        raise ValueError("Observation window must contain the ordered write, lost response and readback")
    processes = [uuid.UUID(entry["process_id"]) for entry in (job, uncertain, recovery)]
    if processes[0] != processes[1] or processes[0] == processes[2] or recovery["satisfied"] is not True:
        raise ValueError("Recovery must be a successful read from a different process")
    tags = [entry["credential_tag"] for entry in (job, recovery)]
    if (not all(re.fullmatch(r"[a-f0-9]{64}", tag) for tag in tags) or tags[0] == tags[1]
            or not job["write_alias"] == recovery["write_alias"] or not job["credential_source"] == recovery["credential_source"]):
        raise ValueError("Require distinct presented credentials on the same bound connection")
    report = observe(dict(config, correlation_id=correlation), read)
    if report["verified_attempt"]:
        when = instant(next(iter(report["records"].values())))
        if not before <= when <= after:
            raise ValueError("Provider change falls outside the attempted write and successful readback")
    report.update(write_process=str(processes[0]), recovery_process=str(processes[2]),
                  changed_presented_credential=True, connection=job["write_alias"],
                  write_binding_revision=job["credential_revision"], recovery_binding_revision=recovery["credential_revision"],
                  observation="provider_history_and_native_process_recovery")
    return report


def observe(config, read=fetch):
    start, end = instant(config["start"]), instant(config["end"])
    if not dt.timedelta(0) < end - start <= dt.timedelta(days=1) or end > dt.datetime.now(dt.timezone.utc):
        raise ValueError("Choose a completed observation window of at most one day")
    provider = config["provider"]
    if provider == "entra":
        group, member, principal = [str(uuid.UUID(config[name])) for name in ("group", "member", "service_principal")]
        path = "https://graph.microsoft.com/v1.0/auditLogs/directoryAudits"
        window = "activityDateTime ge " + start.isoformat().replace("+00:00","Z") + " and activityDateTime lt " + end.isoformat().replace("+00:00","Z")
        url = path + "?" + urllib.parse.urlencode({"$filter": window, "$top": 100})
    elif provider == "jira":
        site, issue = config["site"], config["issue"]
        if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.atlassian\.net", site) or not re.fullmatch(r"[A-Z][A-Z0-9_]*-[1-9][0-9]*", issue):
            raise ValueError("Choose a Jira Cloud site and issue key")
        if not all(isinstance(config.get(k), str) and config[k] for k in ("actor", "from_status", "to_status")) or config["from_status"] == config["to_status"]:
            raise ValueError("Choose distinct status IDs and a provider actor")
        path = "https://" + site + "/rest/api/3/issue/" + issue + "/changelog"
        offset = 0
        url = path + "?startAt=0&maxResults=100"
    else:
        raise ValueError("Choose entra or jira")
    correlation = str(uuid.UUID(config["correlation_id"])) if "correlation_id" in config else None
    correlated = set()
    seen, matches = set(), {}
    page_total = None
    correlation_by_event = {}
    while url:
        if url in seen or len(seen) >= 100 or urllib.parse.urlsplit(url)[:3] != urllib.parse.urlsplit(path)[:3]:
            raise ValueError("Provider pagination is incomplete or changed its destination")
        seen.add(url)
        page = read(url, os.environ[config["auth_env"]])
        rows = page["value" if provider == "entra" else "values"]
        if not isinstance(rows, list):
            raise ValueError("Provider history must contain an array of events")
        if provider == "entra":
            for event in rows:
                if not (start <= instant(event["activityDateTime"]) < end and event["result"] == "success" and event["activityDisplayName"] == "Add member to group"):
                    continue
                app = event.get("initiatedBy", {}).get("app") or {}
                if str(app.get("servicePrincipalId", "")).lower() != principal:
                    continue
                targets = event["targetResources"]
                users = {str(t.get("id")).lower() for t in targets if t.get("type") == "User"}
                groups = {str(t.get("id")).lower() for t in targets if t.get("type") == "Group"}
                for target in targets:
                    if str(target.get("id")).lower() == member:
                        for prop in target.get("modifiedProperties", []):
                            if prop["displayName"] == "Group.ObjectID":
                                groups.add(str(prop["newValue"]).strip('"').lower())
                if member in users and group in groups:
                    record_match(matches, event.get("id"), event["activityDateTime"])
                    marker = str(event.get("correlationId", "")).lower()
                    record_match(correlation_by_event, event["id"], marker)
                    if correlation == marker:
                        correlated.add(event["id"])
            url = page.get("@odata.nextLink")
            if "@odata.nextLink" in page and (not isinstance(url, str) or not url.strip()):
                raise ValueError("Invalid Graph continuation")
        else:
            if page["startAt"] != offset or not isinstance(page["isLast"], bool):
                raise ValueError("Jira returned an unexpected page")
            for event in rows:
                if not (start <= instant(event["created"]) < end and event["author"].get("accountId") == config["actor"]):
                    continue
                for item in event["items"]:
                    if item.get("fieldId", item.get("field")) == "status" and item.get("from") == config["from_status"] and item.get("to") == config["to_status"]:
                        record_match(matches, event.get("id"), event["created"])
                        metadata = event.get("historyMetadata") or {}
                        marker = (metadata.get("type"), str((metadata.get("extraData") or {}).get("correlation_id", "")).lower())
                        record_match(correlation_by_event, event["id"], marker)
                        if marker == ("opensoft.openservice", correlation):
                            correlated.add(event["id"])
            if not rows and not page["isLast"]:
                raise ValueError("Jira returned an incomplete changelog")
            if "total" in page:
                if page_total is not None and page["total"] != page_total:
                    raise ValueError("Changelog changed during observation. Repeat the read.")
                page_total = page["total"]
            offset += len(rows)
            if "total" in page and (offset > page["total"] or page["isLast"] != (offset == page["total"])):
                raise ValueError("Jira page metadata does not establish a complete changelog")
            url = None if page["isLast"] else path + "?startAt=" + str(offset) + "&maxResults=100"
    return {"provider": provider, "start": start.isoformat(), "end": end.isoformat(), "pages_read": len(seen),
            "record_count": len(matches), "records": matches, "one_matching_effect": len(matches) == 1,
            "correlation_id": correlation, "correlated_event_ids": sorted(correlated),
            "verified_attempt": len(matches) == 1 and set(matches) == correlated,
            "observation": "completed_provider_history_query"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("configuration", help="JSON observation scope and credential environment-variable name")
    args = parser.parse_args()
    with open(args.configuration, encoding="utf-8") as file:
        report = qualification(json.load(file))
    print(json.dumps(report, indent=2))
    success = report.get("verified_attempt") if report.get("correlation_id") else report.get("one_matching_effect", True)
    raise SystemExit(0 if success else 1)
