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
    seen, matches = set(), {}
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
            url = page.get("@odata.nextLink")
        else:
            if page["startAt"] != offset or not isinstance(page["isLast"], bool):
                raise ValueError("Jira returned an unexpected page")
            for event in rows:
                if not (start <= instant(event["created"]) < end and event["author"].get("accountId") == config["actor"]):
                    continue
                for item in event["items"]:
                    if item.get("fieldId", item.get("field")) == "status" and item.get("from") == config["from_status"] and item.get("to") == config["to_status"]:
                        record_match(matches, event.get("id"), event["created"])
            if not rows and not page["isLast"]:
                raise ValueError("Jira returned an incomplete changelog")
            offset += len(rows)
            if "total" in page and (offset > page["total"] or page["isLast"] != (offset == page["total"])):
                raise ValueError("Jira page metadata does not establish a complete changelog")
            url = None if page["isLast"] else path + "?startAt=" + str(offset) + "&maxResults=100"
    return {"provider": provider, "start": start.isoformat(), "end": end.isoformat(), "pages_read": len(seen),
            "record_count": len(matches), "records": matches, "one_matching_effect": len(matches) == 1,
            "observation": "completed_provider_history_query"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("configuration", help="JSON observation scope and credential environment-variable name")
    args = parser.parse_args()
    with open(args.configuration, encoding="utf-8") as file:
        report = observe(json.load(file))
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["one_matching_effect"] else 1)
