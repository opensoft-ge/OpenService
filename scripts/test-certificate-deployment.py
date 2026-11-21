"""Exercise the real certificate collector against two independent loopback TLS services."""
import http.server
import importlib.util
import json
import pathlib
import ssl
import subprocess
import tempfile
import threading

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("service_probe", HERE / "service-probe.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def run(directory, *args):
    subprocess.run(["openssl", *args], cwd=directory, capture_output=True, check=True)


def collect(directory):
    run(directory, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=OpenService Test CA",
        "-keyout", "ca.key", "-out", "ca.pem")
    directory.joinpath("ext.cnf").write_text("subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n")
    for name in ("old", "renewed"):
        run(directory, "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=127.0.0.1",
            "-keyout", name + ".key", "-out", name + ".csr")
        run(directory, "x509", "-req", "-in", name + ".csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial",
            "-days", "1", "-extfile", "ext.cnf", "-out", name + ".pem")

    servers = []
    client = ssl.create_default_context(cafile=directory / "ca.pem")
    output = {}
    try:
        for label in ("first", "second"):
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), http.server.BaseHTTPRequestHandler)
            site_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            site_context.load_cert_chain(directory / "old.pem", directory / "old.key")
            server.socket = site_context.wrap_socket(server.socket, server_side=True)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            servers.append(server)
        urls = ["https://127.0.0.1:" + str(server.server_port) for server in servers]
        output["endpoints"] = urls
        output["initial"] = [probe.certificate(url, client) for url in urls]
        servers[0].socket.context.load_cert_chain(directory / "renewed.pem", directory / "renewed.key")
        output["partial"] = [probe.certificate(url, client) for url in urls]
        servers[1].socket.context.load_cert_chain(directory / "renewed.pem", directory / "renewed.key")
        output["complete"] = [probe.certificate(url, client) for url in urls]
        assert output["initial"][0] == output["partial"][1]
        assert output["partial"][0] != output["partial"][1]
        assert output["complete"] == [output["partial"][0]] * 2
        return output
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as directory:
        print(json.dumps(collect(pathlib.Path(directory))))
