#!/usr/bin/env python3
"""Report local endpoint inventory using a dedicated OpenService identity."""
import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import re
import uuid
from pathlib import Path
from urllib import request
from urllib.parse import urlsplit
import time


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def windows_registration():
    """Return only the machine's Entra join identifiers."""
    if platform.system() != "Windows":
        raise ValueError("Run identity collection on the enrolled Windows device")
    output = subprocess.check_output(["dsregcmd.exe", "/status"], text=True, timeout=30)
    def field(name):
        matches = re.findall(r"(?im)^[ \t]*" + name + r"[ \t]*:[ \t]*([^\r\n]+?)[ \t]*\r?$", output)
        if len(set(matches)) != 1:
            raise ValueError("Missing or ambiguous Windows join field " + name)
        return matches[0]
    if field("AzureAdJoined").upper() != "YES":
        raise ValueError("Device is not Entra joined")
    return {"device_id": str(uuid.UUID(field("DeviceId"))),
            "tenant_id": str(uuid.UUID(field("TenantId")))}


def inventory():
    system = platform.system()
    if system == "Windows":
        import winreg
        products = set()
        for view in (winreg.KEY_WOW64_32KEY, winreg.KEY_WOW64_64KEY):
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                                r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
                                0, winreg.KEY_READ | view) as key:
                for index in range(winreg.QueryInfoKey(key)[0]):
                    with winreg.OpenKey(key, winreg.EnumKey(key, index)) as product:
                        try:
                            products.add(winreg.QueryValueEx(product, "DisplayName")[0])
                        except FileNotFoundError:
                            continue
    elif system == "Linux":
        command = ["dpkg-query", "-W", "-f=${binary:Package}\n"] if shutil.which("dpkg-query") else ["rpm", "-qa", "--qf", "%{NAME}\n"]
        products = set(subprocess.check_output(command, text=True, timeout=60).splitlines())
    else:
        raise ValueError("Choose an enrolled Windows or Linux machine")
    return dict(platform=system, hostname=platform.node(), machine=platform.machine(), software=sorted(products))


def report(config):
    origin = urlsplit(config["origin"])
    if origin.scheme != "https" or not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in ("", "/"):
        raise ValueError("Use the OpenService HTTPS origin")
    facts = inventory()
    names = set(config["tracked_software"])
    if len(names) > 512:
        raise ValueError("Choose up to 512 tracked software products")
    facts["software"] = sorted(names.intersection(facts["software"]))
    body = dict(operation="endpoint_report", collector_id=config["collector_id"], sequence=int(time.time()), manifest=facts)
    url = config["origin"].rstrip("/") + "/api/openservice/enterprise/mapping/global"
    req = request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type":"application/json","Authorization":os.environ[config["auth_env"]]})
    with request.build_opener(NoRedirect).open(req, timeout=30) as response:
        response.read()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("configuration", type=Path, nargs="?")
    parser.add_argument("--every-minutes", type=int, choices=range(1, 10081), metavar="minutes")
    parser.add_argument("--windows-registration", action="store_true", help="Print the local join IDs for a designated fleet qualification")
    args = parser.parse_args()
    if args.windows_registration:
        print(json.dumps(windows_registration()))
        raise SystemExit(0)
    if args.configuration is None:
        parser.error("Provide the collector configuration")
    config = json.loads(args.configuration.read_text())
    while True:
        try:
            report(config)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            if args.every_minutes is None:
                raise
            print(f"Inventory report failed: {error}", file=sys.stderr)
        if args.every_minutes is None:
            break
        time.sleep(args.every_minutes * 60)
