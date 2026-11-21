"""Exercise the attended robot against the packaged application on a private loopback TLS port."""
import base64
import hashlib
import io
import json
import os
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from pathlib import Path
import runpy
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
DRIVER = runpy.run_path(str(ROOT / "scripts/attended-case-entry.py"))


def exercise(jar, work):
    key, cert, p12 = [work / name for name in ("test.key", "test.pem", "server.p12")]
    password = secrets.token_urlsafe(24)
    env = dict(os.environ, ADMIN_PASSWORD=password, SECRET_KEY=secrets.token_hex(32),
               DATABASE_URL="jdbc:sqlite:" + str(work / "service.db"),
               SERVER_ADDRESS="127.0.0.1", SERVER_SSL_ENABLED="true",
               SERVER_SSL_KEY_STORE=str(p12), SERVER_SSL_KEY_STORE_PASSWORD=password)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                    "-keyout", key, "-out", cert], check=True, capture_output=True)
    subprocess.run(["openssl", "pkcs12", "-export", "-inkey", key, "-in", cert, "-out", p12,
                    "-passout", "env:SERVER_SSL_KEY_STORE_PASSWORD"], env=env, check=True)
    pub = subprocess.check_output(["openssl", "x509", "-in", cert, "-pubkey", "-noout"])
    der = subprocess.check_output(["openssl", "pkey", "-pubin", "-outform", "DER"], input=pub)
    pin = base64.b64encode(hashlib.sha256(der).digest()).decode()
    tls = ssl.create_default_context(cafile=str(cert))
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = str(sock.getsockname()[1])
    env["SERVER_PORT"] = port
    origin = "https://127.0.0.1:" + port
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    with (work / "server.log").open("w") as log:
        server = subprocess.Popen([java, "-jar", jar], env=env, cwd=work, stdout=log, stderr=log)
        try:
            for _ in range(120):
                try:
                    urllib.request.urlopen(origin + "/login", context=tls, timeout=2).close()
                    break
                except OSError:
                    if server.poll() is not None:
                        raise RuntimeError("Local application exited")
                    time.sleep(0.25)
            auth = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
            def post(path, body, method="POST"):
                req = urllib.request.Request(origin + path, data=json.dumps(body).encode(),
                      method=method,headers={"Content-Type":"application/json", "Authorization":auth})
                with urllib.request.urlopen(req, context=tls, timeout=10) as response:
                    return json.load(response)["result"]
            group = post("/api/now/table/sys_user_group", {"short_description":"Permit examiners"})["sys_id"]
            post("/api/openservice/enterprise/apps/global", {"operation":"case_program","kind":"permit","group_id":group,"revision":0})
            url=DRIVER["destination"](origin)
            row=DRIVER["read_batch"](io.StringIO('kind,reference,details\npermit,N1,"{""site"":""North"",""activity"":""Event""}"\n'))[0]
            with sync_playwright() as pw:
                browser=pw.chromium.launch(args=["--ignore-certificate-errors-spki-list="+pin])
                page=browser.new_page()
                page.goto(origin+"/login")
                page.get_by_label("Username",exact=True).fill("admin")
                page.get_by_label("Password",exact=True).fill(password)
                page.get_by_role("button",name="Enter your workspace").click()
                page.goto(url)
                import contextlib
                def batch():
                    with contextlib.redirect_stdout(io.StringIO()) as result:
                        DRIVER["run"](page,url,[row],lambda _:"SUBMIT")
                    return json.loads(result.getvalue())
                first_batch=batch()
                replay=batch()
                assert first_batch["case"]==replay["case"] and replay["status"]=="received"
                first=DRIVER["enter"](page,url,row)
                repeat=DRIVER["enter"](page,url,row)
                assert first==repeat and first["state"]=="submitted"
                stored=post("/api/openservice/enterprise/apps/global",{"operation":"industry_case","new_id":first["case"]})["outcome"]
                assert stored["revision"]==1 and stored["definition"]["details"]==row[2]
                print("Verified duplicate browser entry returned one unchanged permit case over TLS.")
                robot=runpy.run_path(str(ROOT/"scripts/permit-robot.py"))
                pool, job = secrets.token_hex(16), secrets.token_hex(16)
                api="/api/openservice/enterprise/apps/global"
                def command(operation, **values):
                    return post(api, dict(operation=operation, **values))["outcome"]
                command("robot_pool",pool_id=pool,group_id=group,revision=0)
                for name in ("PermitRobotA","PermitRobotB"):
                    post("/api/openservice/users",dict(name=name,password=password,role="agent"))
                    identity = base64.b64encode((name + ":" + password).encode()).decode()
                    me = urllib.request.Request(origin + "/api/openservice/me", headers={"Authorization": "Basic " + identity})
                    with urllib.request.urlopen(me, context=tls, timeout=10) as response:
                        user = json.load(response)["result"]
                    post("/api/openservice/groups/"+group+"/members",dict(user_id=user["user_id"]),method="PUT")
                command("queue_robot_permit",pool_id=pool,job_id=job,reference="R1",manifest=dict(site="West",activity="Repair"))
                result=robot["run"](browser.new_page(),url,pool,"PermitRobotA",password)
                assert result["state"]=="done" and result["job"]==job
                assert robot["run"](browser.new_page(),url,pool,"PermitRobotB",password)["state"]=="idle"
                recorded=command("industry_case",new_id=result["case"])
                assert recorded["definition"]["reference"]=="R1" and recorded["revision"]==1
                # The two clients sign in independently and compete for the same second permit.
                second = secrets.token_hex(16)
                command("queue_robot_permit", pool_id=pool, job_id=second, reference="R2",
                        manifest=dict(site="East", activity="Repair"))
                start_claim = Barrier(2)
                def compete(name):
                    with sync_playwright() as client:
                        worker = client.chromium.launch(args=["--ignore-certificate-errors-spki-list="+pin])
                        try:
                            page = worker.new_page()
                            page.goto(origin+"/login")
                            page.get_by_label("Username",exact=True).fill(name)
                            page.get_by_label("Password",exact=True).fill(password)
                            page.get_by_role("button",name="Enter your workspace").click()
                            start_claim.wait(timeout=45)
                            return robot["execute"](page,url,pool)
                        finally:
                            worker.close()
                with ThreadPoolExecutor(max_workers=2) as clients:
                    results = list(clients.map(compete, ("PermitRobotA", "PermitRobotB")))
                assert sorted(item["state"] for item in results) == ["done","idle"], results
                winner = next(item for item in results if item["state"] == "done")
                assert winner["job"] == second
                assert command("industry_case", new_id=winner["case"])["revision"] == 1
                print("Two unattended browser accounts raced for one queued permit and produced one filing.")
                browser.close()
        finally:
            server.terminate()
            server.wait(timeout=30)


if __name__ == "__main__":
    jar = next((ROOT / "target").glob("openservice-*.jar"))
    with tempfile.TemporaryDirectory() as directory:
        exercise(jar, Path(directory))
