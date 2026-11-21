#!/usr/bin/env python3
"""Download a single assigned repair and later sync the edited completion file."""
import argparse
import json
import os
import uuid
from pathlib import Path
from urllib import request
from urllib.parse import urlsplit


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def submit(config, payload):
    origin = urlsplit(config["origin"])
    if origin.scheme != "https" or not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in ("", "/"):
        raise ValueError("Use the OpenService HTTPS origin")
    req = request.Request(config["origin"].rstrip("/") + "/api/openservice/enterprise/routing/global",
                          data=json.dumps(payload).encode(), headers={"Authorization":os.environ[config["auth_env"]],
                          "Content-Type":"application/json"})
    with request.build_opener(NoRedirect).open(req, timeout=30) as response:
        return json.load(response)["result"]["outcome"]


def download(config, task, path, send=submit):
    packet = send(config, dict(operation="repair_packet", task_id=task))
    job = packet["job"]
    if job["state"] != "assigned":
        raise ValueError("Download an assigned visit")
    data = dict(instructions=job["instructions"], parts=job["parts"], origin=config["origin"], completion=dict(
        operation="repair_sync", task_id=task, revision=packet["revision"], asset_id=job["returned_asset"],
        new_id=uuid.uuid4().hex, amount="", note=""))
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as out:
        json.dump(data, out, indent=2)


def sync(config, path, send=submit):
    packet = json.loads(path.read_text())
    if packet["origin"] != config["origin"] or packet["completion"]["operation"] != "repair_sync":
        raise ValueError("Choose a repair packet for this instance")
    return send(config, packet["completion"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("file", type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--download", metavar="TASK_ID")
    group.add_argument("--sync", action="store_true")
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.download:
        download(config, args.download, args.file)
    else:
        print(json.dumps(sync(config, args.file)))
