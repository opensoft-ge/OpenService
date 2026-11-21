#!/usr/bin/env python3
"""Promote one reviewed intake release through independent test and production instances."""
import argparse
import hashlib
import ipaddress
import json
import os
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def origin(value):
    url = urllib.parse.urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username is not None or url.path not in ("", "/") or url.query or url.fragment:
        raise ValueError("Each instance requires an HTTPS origin")
    host = url.hostname.rstrip(".").encode("idna").decode("ascii").lower()
    if ":" in host:
        host = "[" + ipaddress.IPv6Address(host).compressed + "]"
    return "https://" + host + ("" if url.port in (None, 443) else ":" + str(url.port))


def post(instance, credential, operation, **fields):
    target = origin(instance) + "/api/openservice/enterprise/apps/global"
    body = json.dumps(dict(fields, operation=operation)).encode()
    headers = {"Authorization": os.environ[credential], "Content-Type": "application/json"}
    request = urllib.request.Request(target, data=body, headers=headers)
    with urllib.request.build_opener(NoRedirect).open(request, timeout=45) as response:
        return json.load(response)["result"]["outcome"]


def digest(package):
    return hashlib.sha256(json.dumps(package, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def source_package(config):
    source = config["git"]
    commit, path = source["commit"], source["path"]
    if not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", commit):
        raise ValueError("Pin the full reviewed Git commit")
    if not all(re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9._-]*", part) for part in path.split("/")):
        raise ValueError("Choose a repository-relative package path")
    def git(*args):
        return subprocess.check_output(["git", "-C", source["repository"], *args], timeout=15)
    if git("cat-file", "-t", commit).strip() != b"commit":
        raise ValueError("Pin a Git commit")
    spec = commit + ":" + path
    if int(git("cat-file", "-s", spec)) > 1048576:
        raise ValueError("Application package exceeds 1 MiB")
    return json.loads(git("cat-file", "blob", spec))


def export(config, path, send=post):
    dev = config["development"]
    package = send(dev["origin"], dev["reader_env"], "package_app", app_id=dev["app_id"])
    with path.open("x", encoding="utf-8") as output:
        json.dump(package, output, ensure_ascii=False, indent=2, sort_keys=True)
        output.write(chr(10))
    print(digest(package))


def promote(config, send=post):
    dev = config["development"]
    targets = [config["test"], config["production"]]
    if len({origin(x["origin"]) for x in [dev, *targets]}) != 3:
        raise ValueError("Choose three independent instance origins")
    package = send(dev["origin"], dev["reader_env"], "package_app", app_id=dev["app_id"])
    if "git" in config:
        reviewed = source_package(config)
        if digest(reviewed) != digest(package):
            raise ValueError("Development differs from the reviewed Git artifact")
        package = reviewed
    if digest(package) != config["sha256"]:
        raise ValueError("Published source differs from the reviewed digest")
    receipts = []
    for target in targets:
        url = target["origin"]
        author = target["author_env"]
        values = {k: target[k] for k in ("app_id", "group_id", "design_id", "bindings")}
        values["manifest"] = package
        identity = dict(app_id=target["app_id"], commit=config["git"]["commit"], digest=config["sha256"]) if "git" in config else None
        if identity:
            status = send(url, author, "git_status", **identity)
            receipt = status["receipt"]
            if receipt.get("state") == "published":
                if receipt["release_revision"] != status["release_revision"]:
                    raise ValueError("Published artifact was superseded at " + origin(url))
                receipts.append(dict(origin=origin(url), **receipt))
                continue
            if receipt.get("state") == "staged":
                result = send(url, target["publisher_env"], "git_publish", **identity)
                receipts.append(dict(origin=origin(url), **result["definition"]))
                continue
        review = send(url, author, "compare_package", **values)
        if (review["release_revision"], review["draft_revision"]) != (target["release_revision"], target["draft_revision"]):
            raise ValueError("Destination changed since review: " + origin(url))
        if "workflow_dependency" in review["changes"]:
            send(url, author, "install_dependency", **values)
        if identity:
            send(url, author, "git_stage", **dict(values, **identity), revision=review["draft_revision"], release_revision=review["release_revision"])
            result = send(url, target["publisher_env"], "git_publish", **identity)
            receipts.append(dict(origin=origin(url), **result["definition"]))
            print(json.dumps(receipts[-1]), flush=True)
            continue
        staged = send(url, author, "stage_package", **values, revision=review["draft_revision"], release_revision=review["release_revision"])
        promoted = send(url, target["publisher_env"], "tested_promote", app_id=target["app_id"], revision=staged["revision"], release_revision=review["release_revision"])
        receipts.append({"origin": origin(url), "release_revision": promoted["revision"], "sha256": config["sha256"]})
        print(json.dumps(receipts[-1]), flush=True)
    return receipts


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("configuration", type=Path)
    parser.add_argument("--export", type=Path, help="export a development release to a new Git source file")
    args = parser.parse_args()
    config = json.loads(args.configuration.read_text())
    if args.export:
        export(config, args.export)
    else:
        promote(config)
