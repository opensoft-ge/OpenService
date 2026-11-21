"""Exercise the attended robot against the packaged application on a private loopback TLS port."""
import base64
import hashlib
import io
import json
import os
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
            def post(path, body):
                req = urllib.request.Request(origin + path, data=json.dumps(body).encode(),
                      headers={"Content-Type":"application/json", "Authorization":auth})
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
                browser.close()
        finally:
            server.terminate()
            server.wait(timeout=30)


if __name__ == "__main__":
    jar = next((ROOT / "target").glob("openservice-*.jar"))
    with tempfile.TemporaryDirectory() as directory:
        exercise(jar, Path(directory))
