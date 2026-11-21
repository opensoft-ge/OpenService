#!/usr/bin/env python3
"""Report local endpoint inventory using a dedicated OpenService identity."""
import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path
from urllib import request
from urllib.parse import urlsplit
import time


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


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
    parser.add_argument("configuration", type=Path)
    parser.add_argument("--every-minutes", type=int, choices=range(1, 10081), metavar="minutes")
    args = parser.parse_args()
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
