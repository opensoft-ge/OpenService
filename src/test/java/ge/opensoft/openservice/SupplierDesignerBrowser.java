package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseBrowserAcceptance.*;
import static ge.opensoft.openservice.JourneyForms.definition;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import java.util.List;

final class SupplierDesignerBrowser {
  private SupplierDesignerBrowser() {}

  static void run(BrowserTest h) throws Exception {
    String team,
        securityGroup,
        request,
        employee,
        legal = id(),
        security = id(),
        standard = id(),
        high = id(),
        design = id(),
        action = id(),
        run = id();
    try (var db = h.store.open()) {
      employee = PlatformFoundationTest.bind(db);
      Service.createAccount(
          db, map("name", "HrDesigner", "role", "admin", "password", WorkflowSupport.PASSWORD));
      team = PlatformFoundationTest.record(db, "sys_user_group", "Legal review");
      securityGroup = PlatformFoundationTest.record(db, "sys_user_group", "Security review");
      request = PlatformFoundationTest.record(db, "sc_request", "Supplier equipment purchase");
      db.connection.commit();
    }
    try (var pw = Playwright.create();
        var browser = pw.chromium().launch();
        var session = browser.newContext()) {
      var page = session.newPage();
      h.login(page, "admin");
      page.navigate(h.base() + "/enterprise/access/global");
      for (String procedure : List.of(legal, security))
        fill(
            form(page, "procedure"),
            map(
                "procedure_id",
                procedure,
                "title",
                procedure.equals(legal) ? "Legal review" : "Security review",
                "group_id",
                procedure.equals(legal) ? team : securityGroup,
                "kind",
                "team_approval",
                "revision",
                0));
      page.navigate(h.base() + "/enterprise/designer/global");
      for (String sub : List.of(standard, high)) {
        String path = id();
        var inputs =
            map(
                "design_id",
                sub,
                "title",
                sub.equals(high) ? "High-risk review" : "Standard review",
                "row_id",
                path,
                "condition",
                "default",
                "operand",
                "default",
                "item_1",
                legal,
                "stage_1",
                1,
                "days",
                2,
                "revision",
                0);
        var pathForm = form(page, "path");
        if (sub.equals(high)) {
          pathForm.locator("details").nth(1).locator("summary").click();
          inputs.putAll(map("item_2", security, "stage_2", 1));
        }
        fill(pathForm, inputs);
        fill(
            form(page, "test"),
            map(
                "design_id",
                sub,
                "row_id",
                path,
                "amount",
                "100",
                "risk",
                "high",
                "location",
                "Tbilisi",
                "revision",
                1));
        fill(form(page, "publish"), map("design_id", sub, "revision", 2));
      }
      fill(
          form(page, "purchase_action"),
          map("action_id", action, "supplier", "Parts Ltd", "currency", "USD", "revision", 0));
      var author = form(page, "exception_design");
      for (String risk : List.of("low", "medium", "high"))
        author
            .locator("[name=route_" + risk + "]")
            .selectOption(risk.equals("high") ? high : standard);
      fill(author, map("design_id", design, "action_id", action, "revision", 0));
      fill(
          form(page, "exception_requisition"),
          map(
              "record_id",
              request,
              "supplier",
              "Parts Ltd",
              "revision",
              0,
              "manifest",
              JSON.writeValueAsString(
                  map(
                      "Cable",
                      map("quantity", 2, "unit_cost", "10.00", "months", 24, "salvage", "0")))));
      fill(
          form(page, "start_exception"),
          map(
              "design_id",
              design,
              "new_id",
              run,
              "user_id",
              employee,
              "record_id",
              request,
              "amount",
              "20",
              "risk",
              "high",
              "location",
              "Tbilisi"));
      try (var db = h.store.open()) {
        var flow = definition(db, "workflow-run", run);
        assertEquals(high, flow.get("template"));
        var steps = objects(flow.get("steps"));
        assertEquals(
            List.of(legal, security), steps.stream().map(s -> s.get("procedure")).toList());
        assertEquals(List.of(1, 1), steps.stream().map(s -> s.get("stage")).toList());
        assertEquals(request, definition(db, "exception-run", run).get("source"));
      }
      try (var hrSession = browser.newContext()) {
        var hr = hrSession.newPage();
        h.login(hr, "HrDesigner");
        hr.navigate(h.base() + "/enterprise/designer/global");
        String hrFlow = id(), transfer = id();
        fill(
            form(hr, "path"),
            map(
                "design_id",
                hrFlow,
                "title",
                "HR transfer exception",
                "row_id",
                "standard",
                "condition",
                "default",
                "operand",
                "default",
                "item_1",
                legal,
                "stage_1",
                1,
                "days",
                2,
                "revision",
                0));
        fill(
            form(hr, "test"),
            map(
                "design_id",
                hrFlow,
                "row_id",
                "standard",
                "amount",
                "0",
                "risk",
                "low",
                "location",
                "HQ",
                "revision",
                1));
        fill(form(hr, "publish"), map("design_id", hrFlow, "revision", 2));
        fill(
            form(hr, "start"),
            map(
                "design_id",
                hrFlow,
                "user_id",
                employee,
                "new_id",
                transfer,
                "amount",
                "0",
                "risk",
                "low",
                "location",
                "HQ",
                "revision",
                1));
        try (var db = h.store.open()) {
          assertEquals(
              legal,
              objects(definition(db, "workflow-run", transfer).get("steps"))
                  .getFirst()
                  .get("procedure"));
        }
      }
    }
  }
}
