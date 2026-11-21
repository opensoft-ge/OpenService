import contextlib
import http.client
import importlib.util
import io
import json
import os
import pathlib
import runpy
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch, MagicMock

SOURCE = pathlib.Path(__file__).with_name("service-probe.py")
SPEC = importlib.util.spec_from_file_location("service_probe", SOURCE)
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.config = dict(operation="probe_sample", objective_id="fixture", location="west",
                           endpoint="https://site.example/ready?a=b", location_health_url="https://control.example/health",
                           openservice_origin="https://openservice.example", auth_env="LOCAL_FIXTURE_PROBE")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "config.json"

    def command(self, *args):
        self.path.write_text(json.dumps(self.config))
        with patch.object(sys, "argv", ["service-probe", str(self.path), *args]):
            probe.main()

    def test_endpoint_and_health_classification(self):
        for url in ("http://example.org", "https:///bad", "https://who@example.org",
                    "https://:pw@example.org", "https://example.org/#fragment"):
            with self.assertRaises(ValueError):
                probe.https_target(url)
        with patch.object(probe.http.client, "HTTPSConnection") as factory:
            conn = factory.return_value
            for status, result in ((200,"up"),(399,"up"),(400,"down"),(101,"down")):
                conn.getresponse.return_value.status = status
                self.assertEqual(result,probe.sample(self.config["endpoint"], None))
            conn.request.assert_called_with("GET","/ready?a=b")
            conn.request.side_effect = OSError("failed")
            self.assertEqual("down",probe.sample("https://site.example", None))
            conn.close.assert_called()
        self.assertIsNone(probe.NoRedirect().redirect_request(None))

    def test_reporting_uses_only_the_application_origin(self):
        with patch.object(probe, "sample", side_effect=["up","down","down"]), patch.object(probe, "certificate", return_value="der"), patch.dict(os.environ, LOCAL_FIXTURE_PROBE="fixture-secret"), patch("urllib.request.build_opener") as build, contextlib.redirect_stdout(io.StringIO()):
            build.return_value.open.return_value.__enter__.return_value.read.return_value = b"{}"
            self.command()
            request=build.return_value.open.call_args.args[0]
            self.assertEqual("https://openservice.example/api/openservice/enterprise/mapping/global", request.full_url)
            self.assertEqual("fixture-secret",request.get_header("Authorization"))
            self.assertEqual("down",json.loads(request.data)["result"])
            self.command()
            self.assertEqual("probe_error",json.loads(build.return_value.open.call_args.args[0].data)["result"])
            self.config.update(operation="verify_certificate",fingerprint="old",revision=3)
            self.command()
            self.assertEqual("der",json.loads(build.return_value.open.call_args.args[0].data)["certificate"])
            for origin in ("https://app.example/bad","https://app.example/?unexpected"):
                self.config["openservice_origin"]=origin
                with self.assertRaises(ValueError): self.command()
            self.config["operation"]="unknown"
            with self.assertRaises(ValueError): self.command()
            with self.assertRaises(ValueError): self.command("--every-minutes","1")

    def test_recurring_probe_recovers_from_failed_publication_and_aligns_boundaries(self):
        with patch.object(probe,"report",side_effect=[OSError("unavailable"),None]) as report, patch.object(probe.time,"time",return_value=244), patch.object(probe.time,"sleep",side_effect=[None,KeyboardInterrupt]) as sleep, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(KeyboardInterrupt): self.command("--every-minutes","2")
            self.assertEqual(2,report.call_count)
            sleep.assert_called_with(116)

    def test_certificate_observation_uses_the_verified_peer(self):
        context=MagicMock()
        tls=context.wrap_socket.return_value.__enter__.return_value
        tls.getpeercert.return_value=b"leaf"
        with patch.object(probe.socket, "create_connection") as connect:
            self.assertEqual("bGVhZg==",probe.certificate("https://secure.example:9443",context))
            connect.assert_called_once_with(("secure.example",9443),timeout=10)
            self.assertEqual("secure.example",context.wrap_socket.call_args.kwargs["server_hostname"])
            tls.getpeercert.assert_called_once_with(binary_form=True)

    def test_cli_returns_failure_for_an_invalid_config(self):
        self.path.write_text("{}")
        with patch.object(sys,"argv",["service-probe",str(self.path)]):
            with self.assertRaises(SystemExit): runpy.run_path(str(SOURCE),run_name="__main__")


if __name__ == "__main__":
    unittest.main()
