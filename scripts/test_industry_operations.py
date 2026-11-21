"""Transport and Git evidence for the industry workflows."""
import contextlib
import io
import json
import os
import subprocess
import pathlib
import runpy
import sys
import tempfile
import types
import unittest
from unittest.mock import MagicMock, patch
import importlib.util

HERE = pathlib.Path(__file__).resolve().parent


def module(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class IndustryTransports(unittest.TestCase):
    def test_cli_transport_and_collector_restart(self):
        def run(name, *args):
            with patch.object(sys, "argv", [name, *map(str, args)]):
                runpy.run_path(str(HERE / (name + ".py")), run_name="__main__")

        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, FIXTURE="local"), patch("urllib.request.build_opener") as opener, contextlib.redirect_stdout(io.StringIO()):
            root = pathlib.Path(directory)
            cfg, packet = root / "config.json", root / "packet.json"
            cfg.write_text(json.dumps(dict(origin="https://local.example", auth_env="FIXTURE", tracked_software=["tool"], collector_id="test")))
            with patch("platform.system", return_value="Linux"), patch("subprocess.check_output", return_value="tool"):
                run("endpoint-collector", cfg)
                opener.return_value.open.side_effect = OSError("connection unavailable")
                with self.assertRaises(OSError):
                    run("endpoint-collector", cfg)
                with patch("time.sleep", side_effect=KeyboardInterrupt), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(KeyboardInterrupt):
                    run("endpoint-collector", cfg, "--every-minutes", "1")
                opener.return_value.open.side_effect = None

            response = opener.return_value.open.return_value.__enter__.return_value
            response.read.return_value = json.dumps({"result":{"outcome":{"revision":5,"job":{
                "state":"assigned","instructions":"East gate","parts":[],"returned_asset":"old"}}}}).encode()
            run("contractor-visit", cfg, packet, "--download", "sample")
            job = json.loads(packet.read_text())
            job["completion"].update(amount="100", note="Finished")
            packet.write_text(json.dumps(job))
            run("contractor-visit", cfg, packet, "--sync")
            self.assertEqual("repair_sync", json.loads(opener.return_value.open.call_args.args[0].data)["operation"])
            release_cfg = dict(development=dict(origin="https://dev.example", reader_env="FIXTURE",app_id="sample"))
            cfg.write_text(json.dumps(release_cfg))
            response.read.return_value = b'{"result":{"outcome":{"fields":["price"]}}}'
            artifact = root / "source.json"
            run("promote-intake", cfg, "--export", artifact)
            self.assertEqual(["price"], json.loads(artifact.read_text())["fields"])
            release_cfg.update(test=dict(origin="https://test.example"), production=dict(origin="https://test.example"))
            cfg.write_text(json.dumps(release_cfg))
            with self.assertRaisesRegex(ValueError,"independent"):
                run("promote-intake", cfg)
            driver=module("promote-intake")
            self.assertIsNone(driver.NoRedirect().redirect_request(None))
            with patch("subprocess.check_output",side_effect=[b"commit", b"1048577"]):
                with self.assertRaisesRegex(ValueError, "1 MiB"):
                    driver.source_package(dict(git=dict(commit="f"*40,path="app.json",repository=directory)))

    def test_promotion_rejects_equivalent_origins_before_contacting_an_instance(self):
        driver = module("promote-intake")
        self.assertEqual("https://[::1]", driver.origin("https://[0:0:0:0:0:0:0:1]:443"))
        self.assertEqual("https://app.example:8443", driver.origin("https://APP.example:8443/"))
        targets = dict(development={"origin": "https://dev.example"},
                       test={"origin": "https://app.example"},
                       production={"origin": "https://APP.EXAMPLE.:443/"})
        send = MagicMock()
        with self.assertRaisesRegex(ValueError, "independent"):
            driver.promote(targets, send)
        send.assert_not_called()

    def test_git_conflict_and_reviewed_promotion(self):
        driver = module("promote-intake")
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)

            def git(*args, check=True):
                return subprocess.run(["git", "-C", directory, *args], capture_output=True,
                                      check=check, text=True)

            git("init", "-b", "main")
            git("config", "user.name", "Local Release Fixture")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "commit.gpgsign", "false")
            git("config", "core.hooksPath", str(repo / "no-hooks"))
            package = {"title":"Supplier exceptions","fields":[{"name":"memo","visibility":"requester"}]}
            path = repo / "application.json"
            config = dict(development=dict(origin="https://dev.example", reader_env="READ", app_id="app"))

            def export(*args, **kwargs):
                return package
            with contextlib.redirect_stdout(io.StringIO()):
                driver.export(config, path, export)
            with self.assertRaises(FileExistsError):
                driver.export(config, path, export)
            git("add", path.name)
            git("commit", "-m", "Create fixture app")
            git("switch", "-c", "field-review")
            path.write_text(path.read_text().replace("requester", "confidential"))
            git("commit", "-am", "Protect internal memo")
            git("switch", "main")
            path.write_text(path.read_text().replace("requester", "reviewer"))
            git("commit", "-am", "Edit form visibility")
            self.assertNotEqual(0,git("merge","field-review",check=False).returncode)
            self.assertIn("<<<<<<<",path.read_text())
            package["fields"][0]["visibility"]="confidential"
            path.write_text(json.dumps(package))
            git("add", path.name)
            git("commit", "-m", "Resolve field visibility")
            commit = git("rev-parse","HEAD").stdout.strip()
            config.update(git=dict(repository=directory,commit=commit,path=path.name),sha256=driver.digest(package))
            for target in ("test","production"):
                config[target]=dict(origin=f"https://{target}.example",author_env="AUTHOR",publisher_env="REVIEWER",
                                    app_id=target,group_id="team",design_id="flow",bindings={},draft_revision=0,release_revision=0)
            self.assertEqual(package,driver.source_package(config))
            path.write_text('{"dirty":"working tree"}')
            self.assertEqual(package,driver.source_package(config))
            for bad in (dict(commit="main"),dict(path="../application.json"),dict(commit=git("rev-parse","HEAD:application.json").stdout.strip())):
                with self.assertRaises(ValueError):
                    driver.source_package(dict(config,git=dict(config["git"],**bad)))
            receipts = {}
            stages = []
            def send(url, actor, op, **fields):
                if op == "package_app": return package
                if op == "git_status": return dict(receipt=receipts.get(url,{}),release_revision=1 if url in receipts else 0)
                if op == "compare_package": return dict(changes={},draft_revision=0,release_revision=0)
                if op == "git_stage":
                    self.assertEqual(fields["manifest"],package)
                    stages.append(url)
                    receipts[url]=dict(state="staged")
                    return receipts[url]
                self.assertEqual("git_publish",op)
                self.assertEqual("REVIEWER",actor)
                receipts[url]=dict(state="published",release_revision=1)
                return dict(definition=receipts[url])
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(ValueError,"differs"):
                    driver.promote(config,lambda *args,**kwargs: {"different":True})
                self.assertEqual(2,len(driver.promote(config,send)))
                self.assertEqual(2,len(driver.promote(config,send)))
                receipts["https://test.example"]={"state":"staged"}
                self.assertEqual(2,len(driver.promote(config,send)))
                receipts["https://test.example"]={"state":"published","release_revision":0}
                with self.assertRaisesRegex(ValueError,"superseded"): driver.promote(config,send)
            self.assertEqual(2,len(stages))

    def test_offline_packet_replays_same_identity_and_restricts_origin(self):
        driver=module("contractor-visit")
        cfg={"origin":"https://dispatch.example","auth_env":"EMPLOYEE"}
        job={"state":"assigned","instructions":"Side entrance","parts":["spare"],"returned_asset":"serial"}
        with tempfile.TemporaryDirectory() as directory:
            path=pathlib.Path(directory)/"visit.json"
            driver.download(cfg,"visit1",path,lambda *args: dict(job=job,revision=4))
            self.assertEqual(0o600,path.stat().st_mode & 0o777)
            data=json.loads(path.read_text());data["completion"].update(amount="80.25",note="Returned unit")
            path.write_text(json.dumps(data));send=MagicMock(return_value={"state":"complete"})
            for _ in range(2): driver.sync(cfg,path,send)
            self.assertEqual(send.call_args_list[0],send.call_args_list[1])
            with self.assertRaises(ValueError): driver.sync({"origin":"https://other.example"},path,send)
            with self.assertRaises(ValueError): driver.download(cfg,"visit2",path,lambda *args: dict(job={"state":"offered"}))
            with self.assertRaises(ValueError): driver.submit(dict(cfg,origin="http://bad"),{})
            with patch.dict(os.environ,EMPLOYEE="fixture"),patch.object(driver.request,"build_opener") as opener:
                opener.return_value.open.return_value.__enter__.return_value.read.return_value=b'{"result":{"outcome":{"state":"submitted"}}}'
                self.assertEqual("submitted",driver.submit(cfg,data["completion"])["state"])
                self.assertEqual("https://dispatch.example/api/openservice/enterprise/routing/global",opener.return_value.open.call_args.args[0].full_url)
            self.assertIsNone(driver.NoRedirect().redirect_request(None))

    def test_endpoint_operating_system_inventory_and_minimized_report(self):
        driver=module("endpoint-collector")
        with patch.object(driver.platform,"system",return_value="Linux"),patch.object(driver.subprocess,"check_output",return_value="Editor\nEditor\nCompiler\n") as command:
            for path in (None,"/bin/dpkg-query"):
                with patch.object(driver.shutil,"which",return_value=path):
                    self.assertEqual(["Compiler","Editor"],driver.inventory()["software"])
                    self.assertEqual("dpkg-query" if path else "rpm",command.call_args.args[0][0])
        reg=MagicMock()
        reg.KEY_READ=1;reg.KEY_WOW64_32KEY=2;reg.KEY_WOW64_64KEY=4
        reg.QueryInfoKey.return_value=(1,0,0)
        reg.QueryValueEx.side_effect=[FileNotFoundError(),("CAD",1)]
        with patch.object(driver.platform,"system",return_value="Windows"),patch.dict("sys.modules",winreg=reg):
            self.assertEqual(["CAD"],driver.inventory()["software"])
        with patch.object(driver.platform,"system",return_value="Other"):
            with self.assertRaises(ValueError): driver.inventory()
        cfg=dict(origin="https://server.example",auth_env="COLLECTOR",tracked_software=["CAD"],collector_id="one")
        with patch.dict(os.environ,COLLECTOR="fixture"),patch.object(driver,"inventory",return_value=dict(software=["CAD","Unrelated"])),patch.object(driver.request,"build_opener") as opener:
            driver.report(cfg)
            body=json.loads(opener.return_value.open.call_args.args[0].data)
            self.assertEqual(["CAD"],body["manifest"]["software"])
            with self.assertRaises(ValueError):driver.report(dict(cfg,origin="http://server.example"))
            with self.assertRaises(ValueError):driver.report(dict(cfg,tracked_software=list(range(513))))
        self.assertIsNone(driver.NoRedirect().redirect_request(None))

    def test_measured_response_content_and_latency(self):
        driver=module("service-probe")
        with patch.object(driver.http.client,"HTTPSConnection") as factory,patch.object(driver.time,"monotonic",side_effect=[100,112]*4):
            conn=factory.return_value
            response=conn.getresponse.return_value;response.status=200
            for value,expected in ((b"Order confirmed",True),(b"Try later",False),(b"x"*1048577,False)):
                response.read.return_value=value
                data=driver.transaction("https://portal.example/checkout",None,"Order confirmed")
                self.assertEqual((expected,12000),(data["matched"],data["latency_ms"]))
                response.read.assert_called_with(1048577)
            conn.request.side_effect=OSError()
            self.assertEqual(0,driver.transaction("https://portal.example",None,"Order confirmed")["status"])
            with self.assertRaises(ValueError): driver.transaction("https://portal.example",None,"")
        cfg=dict(operation="transaction_sample",endpoint="https://portal.example",openservice_origin="https://server.example",
                 auth_env="PROBE",expected="OK",location_health_url="https://health.example",objective_id="test",location="west")
        with patch.dict(os.environ,PROBE="fixture"),patch.object(driver,"sample",side_effect=["up","down"]),patch.object(driver,"transaction",return_value={"healthy":True}),patch.object(driver.urllib.request,"build_opener") as opener,contextlib.redirect_stdout(io.StringIO()):
            for healthy in (True,False):
                driver.report(cfg)
                self.assertEqual(healthy,json.loads(opener.return_value.open.call_args.args[0].data)["manifest"]["healthy"])


if __name__=="__main__":
    unittest.main()
