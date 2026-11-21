"""Offline contract checks for CUR conversion and the promotion driver."""
import contextlib
import io
import json
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


def script(name):
    return runpy.run_path(str(Path(__file__).with_name(name)))


class AttendedEntryTest(unittest.TestCase):
    def test_batch_validation_and_operator_confirmation(self):
        from unittest.mock import MagicMock
        driver=script("attended-case-entry.py")
        read=driver["read_batch"]
        header="kind,reference,details\n"
        row='permit,A,"{}"\n'
        rows=read(io.StringIO(header+row))
        for content in ("wrong\n",header+row+row,header+row.replace("permit","archive"),
                        header+row.replace(",A,",",,"),header+row.replace("{}","[]"),
                        header+"".join(f'permit,{i},"{{}}"\n' for i in range(101))):
            with self.assertRaises(ValueError): read(io.StringIO(content))
        url=driver["destination"]("https://service.example/")
        for invalid in ("http://service.example","https://","https://name@service.example","https://service.example/a",
                        "https://service.example/?a","https://service.example/#frag"):
            with self.assertRaises(ValueError): driver["destination"](invalid)
        page=MagicMock()
        page.url=url
        receipt=page.locator.return_value.inner_text
        receipt.return_value=json.dumps({"definition":{"program":"permit","reference":"A","case":"abc","state":"submitted"}})
        driver["run"](page,url,rows,lambda _:"cancel")
        page.expect_navigation.assert_not_called()
        acknowledgement=receipt.return_value
        pending={"rows":[{"status":"pending"}],"conflicts":0}
        received={"rows":[{"status":"received","case":"abc"}],"conflicts":0}
        receipt.side_effect=[json.dumps(pending),acknowledgement,'{"received":1}']
        with contextlib.redirect_stdout(io.StringIO()) as output:
            driver["run"](page,url,rows,lambda _:"SUBMIT")
        self.assertEqual("abc",json.loads(output.getvalue())["case"])
        with contextlib.redirect_stdout(io.StringIO()):
            receipt.side_effect=[json.dumps(received),'{"received":1}']
            driver["run"](page,url,rows,lambda _:"SUBMIT")
            for responses in ([json.dumps(dict(received,conflicts=1))],[json.dumps(received),'{"received":0}']):
                receipt.side_effect=responses
                with self.assertRaises(ValueError):driver["run"](page,url,rows,lambda _:"SUBMIT")
        receipt.side_effect=None
        receipt.return_value=json.dumps({"definition":{"program":"permit","reference":"wrong"}})
        with self.assertRaises(ValueError):driver["enter"](page,url,rows[0])
        page.url="https://different.example"
        with self.assertRaises(ValueError):driver["enter"](page,url,rows[0])
        page.url=url
        page.expect_navigation.return_value.__exit__.side_effect=lambda *_:setattr(page,"url","https://elsewhere.example")
        with self.assertRaises(ValueError):driver["enter"](page,url,rows[0])

    def test_attended_entry_cli_uses_a_fresh_browser(self):
        from unittest.mock import MagicMock
        from types import ModuleType
        api=ModuleType("playwright.sync_api"); api.sync_playwright=MagicMock()
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"rows.csv"; path.write_text("kind,reference,details\n")
            tool=Path(__file__).with_name("attended-case-entry.py")
            with patch.dict(sys.modules,{"playwright.sync_api":api}),patch.object(sys,"argv",[str(tool),"https://desk.example",str(path)]),patch("builtins.input",return_value="cancel"):
                runpy.run_path(str(tool),run_name="__main__")
        api.sync_playwright.return_value.__enter__.return_value.chromium.launch.assert_called_once_with(headless=False)


class BillingImportTest(unittest.TestCase):
    def test_account_period_currency_and_batch_boundary(self):
        batch = script("aws-billing-import.py")["batches"]
        account = "123456789012"
        base = {"lineItem/UsageAccountId": account, "bill/BillingPeriodStartDate": "2027-01-01",
                "lineItem/CurrencyCode": "USD", "lineItem/ResourceId": "i-123",
                "lineItem/UnblendedCost": "1.00000004"}
        rows = [dict(base, **{"identity/LineItemId": str(i)}) for i in range(251)]
        rows.extend([dict(rows[0], **{"lineItem/UsageAccountId": "999999999999"}),
                     dict(rows[0], **{"bill/BillingPeriodStartDate": "2026-12-01"}),
                     dict(rows[0], **{"identity/LineItemId": "credit", "lineItem/CurrencyCode": "EUR",
                                     "lineItem/UnblendedCost": "-0.003"})])
        result = list(batch(rows, account, "2027-01", "aws"))
        self.assertEqual([250, 1, 1], [len(page["entries"]) for page in result])
        self.assertEqual("-0.003", result[2]["entries"][0]["cost"])
        self.assertEqual("EUR", result[2]["currency"])
        with self.assertRaises(ValueError):
            list(batch([], account, "2027-13", "aws"))


    def test_cli_emits_only_the_completed_full_batch(self):
        names = "lineItem/UsageAccountId,bill/BillingPeriodStartDate,lineItem/CurrencyCode,lineItem/ResourceId,lineItem/UnblendedCost,identity/LineItemId"
        rows = [f"123456789012,2025-02-01,USD,vm,1,{n}" for n in range(250)]
        output = io.StringIO()
        path = Path(__file__).with_name("aws-billing-import.py")
        args = [str(path), "--account", "123456789012", "--month", "2025-02", "--source", "aws"]
        with patch.object(sys, "argv", args), patch.object(sys, "stdin", io.StringIO("\n".join([names, *rows]))), contextlib.redirect_stdout(output):
            runpy.run_path(str(path), run_name="__main__")
        self.assertEqual(250, len(json.loads(output.getvalue())["entries"]))


class PromotionTest(unittest.TestCase):
    def setUp(self):
        self.tools = script("promote-intake.py")
        self.package = {"fields": [], "title": "Vendor exception", "workflow": {}}
        destination = {"app_id": "app", "group_id": "group", "design_id": "design", "bindings": {},
                       "release_revision": 0, "draft_revision": 0, "author_env": "AUTHOR",
                       "publisher_env": "PUBLISHER"}
        self.config = {"sha256": self.tools["digest"](self.package),
                       "development": {"origin": "https://dev.example", "app_id": "src", "reader_env": "READER"},
                       "test": dict(destination, origin="https://test.example"),
                       "production": dict(destination, origin="https://production.example")}
        self.calls = []

    def send(self, origin, credential, op, **fields):
        self.calls.append((origin, credential, op, fields))
        return {"package_app": self.package, "compare_package": {"changes": {"workflow_dependency": {}},
                "release_revision": 0, "draft_revision": 0}, "install_dependency": {},
                "stage_package": {"revision": 1}, "tested_promote": {"revision": 1}}[op]

    def run_driver(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return self.tools["promote"](self.config, self.send)

    def test_sequence(self):
        receipts = self.run_driver()
        self.assertEqual(2, len(receipts))
        for index in (1, 5):
            calls = self.calls[index:index+4]
            self.assertEqual(["compare_package", "install_dependency", "stage_package", "tested_promote"],
                             [call[2] for call in calls])
            self.assertEqual(self.package, calls[2][3]["manifest"])
            self.assertEqual("PUBLISHER", calls[3][1])

    def test_source_drift_and_destination_conflict(self):
        self.config["test"]["draft_revision"] = 1
        with self.assertRaisesRegex(ValueError, "Destination changed"):
            self.run_driver()
        self.assertEqual(["package_app", "compare_package"], [call[2] for call in self.calls])
        self.config["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "source differs"):
            self.run_driver()

    def test_origins(self):
        for value in ("http://dev.example", "https://user@dev.example", "https://dev.example/a"):
            with self.assertRaises(ValueError):
                self.tools["origin"](value)
        self.config["production"]["origin"] = "https://TEST.example/"
        with self.assertRaisesRegex(ValueError, "independent"):
            self.run_driver()
        self.assertEqual([], self.calls)


class ProviderEffectsTest(unittest.TestCase):
    def setUp(self):
        self.observe = script("check-provider-effects.py")["observe"]
        credentials = patch.dict("os.environ", {"QUALIFICATION_TEST_AUTH": "Bearer fixture"})
        credentials.start()
        self.addCleanup(credentials.stop)
        self.base = {"start": "2020-01-01T09:00Z", "end": "2020-01-01T10:00Z", "auth_env": "QUALIFICATION_TEST_AUTH"}

    def test_correlated_native_evidence(self):
        import uuid
        correlation, group, member, principal = [str(uuid.uuid4()) for _ in range(4)]
        cfg=dict(self.base, provider="jira", site="qa.atlassian.net", issue="QA-3",
                 actor="staff",from_status="1",to_status="2",correlation_id=correlation)
        event=dict(id="jira-event",created="2020-01-01T09:15Z",author={"accountId":"staff"},
                   items=[{"field":"status","from":"1","to":"2"}],
                   historyMetadata={"type":"opensoft.openservice","extraData":{"correlation_id":correlation}})
        def verify(rows):
            return self.observe(cfg, lambda *_:dict(values=rows,startAt=0,isLast=True))
        self.assertTrue(verify([event])["verified_attempt"])
        self.assertFalse(verify([event,dict(event,id="competing",historyMetadata={})])["verified_attempt"])
        self.assertFalse(verify([dict(event,historyMetadata={"type":"opensoft.openservice"})])["verified_attempt"])
        pages=iter([dict(values=[event],startAt=0,isLast=False,total=2),dict(values=[],startAt=1,isLast=True,total=1)])
        with self.assertRaisesRegex(ValueError,"changed"):self.observe(cfg,lambda *_:next(pages))
        with self.assertRaisesRegex(ValueError,"changed"):verify([event,dict(event,historyMetadata={})])
        cfg.update(provider="entra",group=group,member=member,service_principal=principal)
        for continuation in ("", None):
            with self.assertRaisesRegex(ValueError,"continuation"): self.observe(cfg,lambda *_:{"value":[],"@odata.nextLink":continuation})
        entry=dict(id="directory-event",activityDateTime="2020-01-01T09:15Z",result="success",activityDisplayName="Add member to group",
                   initiatedBy={"app":{"servicePrincipalId":principal}},targetResources=[{"type":"Group","id":group},{"type":"User","id":member}],correlationId=correlation)
        self.assertTrue(self.observe(cfg,lambda *_:{"value":[entry]})["verified_attempt"])
        self.assertFalse(self.observe(cfg,lambda *_:{"value":[dict(entry,correlationId=group)]})["verified_attempt"])

    def test_windows_device_registration_uses_only_join_fields(self):
        import uuid
        who, tenant=str(uuid.uuid4()),str(uuid.uuid4())
        text=f"AzureAdJoined : YES\n DeviceId : {who}\n TenantId : {tenant}\n"
        path=Path(__file__).with_name("endpoint-collector.py")
        with patch("platform.system",return_value="Linux"), self.assertRaises(ValueError):
            script(path.name)["windows_registration"]()
        with patch("platform.system",return_value="Windows"), patch("subprocess.check_output",return_value=text) as output:
            with patch.object(sys,"argv",[str(path),"--windows-registration"]),contextlib.redirect_stdout(io.StringIO()) as capture,self.assertRaises(SystemExit):
                runpy.run_path(str(path),run_name="__main__")
            self.assertEqual({"device_id":who,"tenant_id":tenant},json.loads(capture.getvalue()))
            for bad in (text.replace("YES","NO"),text+"DeviceId : "+tenant):
                output.return_value=bad
                with self.assertRaises(ValueError):script(path.name)["windows_registration"]()
        with patch.object(sys,"argv",[str(path)]),contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):
            runpy.run_path(str(path),run_name="__main__")

    def test_designated_fleet_and_repository(self):
        import uuid
        from urllib.error import HTTPError
        inspect=script("check-provider-effects.py")["qualification"]
        managed, directory, site = [str(uuid.uuid4()) for _ in range(3)]
        cfg=dict(provider="intune",auth_env=self.base["auth_env"],managed_device_id=managed,entra_device_id=directory)
        device=dict(id=managed,azureADDeviceId=directory,operatingSystem="Windows",lastSyncDateTime="2020-01-02T12:00Z")
        self.assertEqual(directory,inspect(cfg,lambda *_:device)["entra_device_id"])
        registered=dict(cfg,tenant_id=site,windows_registration={"tenant_id":site,"device_id":directory})
        self.assertTrue(inspect(registered,lambda *_:device)["matched_supplied_windows_registration"])
        for key in ("tenant_id","device_id"):
            with self.assertRaises(ValueError):inspect(dict(registered,windows_registration=dict(registered["windows_registration"],**{key:managed})),lambda *_:device)
        for key,val in (("id",directory),("azureADDeviceId",managed),("operatingSystem","Linux")):
            with self.assertRaises(ValueError): inspect(cfg,lambda *_:dict(device,**{key:val}))
        cfg=dict(provider="confluence",cloud_id=site,page_id="42",reader_account="employee",expected_reader="allowed",
                 reader_env=self.base["auth_env"],publisher_env=self.base["auth_env"])
        who={"accountId":"employee"}
        page=dict(id="42",status="current",version={"number":3})
        from unittest.mock import Mock
        for expected, result in (("allowed",page),("denied",HTTPError("uri",404,"missing",None,None))):
            cfg["expected_reader"]=expected
            self.assertEqual(expected,inspect(cfg,Mock(side_effect=[who,page,result]))["reader_access"])
        for responses in ([dict(accountId="other")],[who,dict(page,id="44")],[who,dict(page,status="archived")],
                          [who,page,dict(page,id="33")],[who,page,page]):
            with self.assertRaises(ValueError): inspect(cfg,Mock(side_effect=responses))
        with self.assertRaises(HTTPError): inspect(cfg,Mock(side_effect=[who,page,HTTPError("uri",503,"down",None,None)]))
        for wrong in (4,"/wrong"):
            with self.assertRaises(ValueError): inspect(dict(cfg,page_id=wrong),Mock())
        with self.assertRaises(ValueError): inspect(dict(cfg,reader_account=""),Mock(return_value=who))

    def test_jira_paginates_and_counts_two_effects(self):
        config = dict(self.base, provider="jira", site="qa.atlassian.net", issue="QA-3",
                      actor="test-operator", from_status="10", to_status="20")
        event = {"author": {"accountId": "test-operator"}, "created": "2020-01-01T09:30:00.000+0000",
                 "items": [{"fieldId": "status", "from": "10", "to": "20"}]}
        pages = iter([{"startAt": 0, "isLast": False, "values": [dict(event, id="a")]},
                      {"startAt": 1, "isLast": True, "values": [dict(event, id="b")]}])
        result = self.observe(config, lambda *_: next(pages))
        self.assertEqual(2, result["record_count"])
        self.assertFalse(result["one_matching_effect"])
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.observe(config, lambda *_: {"startAt": 0, "isLast": False, "values": []})

    def test_graph_matches_both_resources_and_the_initiating_principal(self):
        import uuid
        group, member, principal = [str(uuid.uuid4()) for _ in range(3)]
        config = dict(self.base, provider="entra", group=group, member=member, service_principal=principal)
        event = {"id": "audit-123", "activityDateTime": "2020-01-01T09:05Z", "result": "success",
                 "activityDisplayName": "Add member to group", "initiatedBy": {"app": {"servicePrincipalId": principal}},
                 "targetResources": [{"type": "User", "id": member, "modifiedProperties": [
                     {"displayName": "Group.ObjectID", "newValue": '"' + group + '"'}]}]}
        report = self.observe(config, lambda *_: {"value": [event, dict(event, id="other", initiatedBy={})]})
        self.assertTrue(report["one_matching_effect"])
        with self.assertRaisesRegex(ValueError, "destination"):
            self.observe(config, lambda *_: {"value": [event], "@odata.nextLink": "https://other.example/page2"})



    def test_history_rejects_incomplete_or_ambiguous_evidence(self):
        config = dict(self.base, provider="jira", site="qa.atlassian.net", issue="QA-3",
                      actor="operator", from_status="1", to_status="2")
        for change in ({"provider": "unknown"}, {"site": "example.org"}, {"actor": ""},
                       {"end": self.base["start"]}, {"start": "2020-01-01T09:00"}):
            with self.assertRaises(ValueError):
                self.observe(dict(config, **change), lambda *_: self.fail("invalid query was sent"))
        event = dict(author={"accountId": "operator"}, id="e", created="2020-01-01T09:30Z",
                     items=[{"field": "status", "from": "1", "to": "2"}])
        page = dict(startAt=0, isLast=True, values=[event], total=1)
        for change in (dict(startAt=1), dict(isLast=1), dict(values={}),dict(total=2),dict(total=0),
                       dict(values=[dict(event,id=None)]),dict(values=[event,dict(event,id="e",created="2020-01-01T09:40Z")])):
            with self.assertRaises(ValueError):
                self.observe(config, lambda *_, change=change: dict(page, **change))
        rows = [event, dict(event,created="2019-01-02T00:00Z"),dict(event,author={"accountId":"other"}),
                dict(event,items=[{"field":"description"}])]
        result = self.observe(config, lambda *_: dict(page, values=rows, total=len(rows)))
        self.assertEqual({"e":"2020-01-01T09:30Z"},result["records"])

    def test_graph_ignores_unrelated_activity_and_preserves_matching_event_ids(self):
        import uuid
        group, member, principal = [str(uuid.uuid4()) for _ in range(3)]
        config = dict(self.base, provider="entra", group=group, member=member, service_principal=principal)
        targets = [dict(type="Group",id=group),dict(type="User",id=member,modifiedProperties=[{"displayName":"other"}])]
        event = dict(id="one",activityDateTime="2020-01-01T09:10Z",result="success", activityDisplayName="Add member to group",
                     initiatedBy={"app":{"servicePrincipalId":principal}},targetResources=targets)
        rows=[event, dict(event,result="failure"),dict(event,targetResources=[])]
        self.assertEqual(1,self.observe(config,lambda *_:{"value":rows})["record_count"])

    def test_qualification_cli_uses_bounded_provider_responses(self):
        path = Path(__file__).with_name("check-provider-effects.py")
        tools = script(path.name)
        self.assertIsNone(tools["NoRedirect"]().redirect_request())
        config = dict(self.base, provider="jira", site="qa.atlassian.net", issue="QA-3",actor="operator",from_status="1",to_status="2")
        with tempfile.TemporaryDirectory() as directory, patch("urllib.request.build_opener") as opener:
            configuration = Path(directory)/"scope.json"
            configuration.write_text(json.dumps(config))
            read = opener.return_value.open.return_value.__enter__.return_value.read
            read.return_value = b"x"*8000001
            with self.assertRaisesRegex(ValueError,"observation limit"):
                tools["fetch"]("https://qa.atlassian.net","Bearer fixture")
            read.return_value = b'{"startAt":0,"isLast":true,"values":[]}'
            output=io.StringIO()
            with patch.object(sys,"argv",[str(path),str(configuration)]), contextlib.redirect_stdout(output), self.assertRaises(SystemExit) as result:
                runpy.run_path(str(path),run_name="__main__")
            self.assertEqual(1,result.exception.code)
            self.assertFalse(json.loads(output.getvalue())["one_matching_effect"])


if __name__ == "__main__":
    unittest.main()
