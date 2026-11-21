package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static ge.opensoft.openservice.EnterpriseBrowserAcceptance.*;
import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;

final class ServiceExpansionBrowser {
  static void run(BrowserTest h) throws Exception {
    String employee = "Desk" + id().substring(0, 8),
        action = id(),
        site = "East" + id().substring(0, 8);
    try (var db = h.store.open()) {
      PlatformFoundationTest.bind(db);
      Service.createAccount(
          db, map("name", employee, "role", "requester", "password", WorkflowSupport.PASSWORD));
      db.connection.commit();
    }
    try (var playwright = Playwright.create();
        var browser = playwright.chromium().launch();
        var adminSession = browser.newContext();
        var employeeSession =
            browser.newContext(new Browser.NewContextOptions().setViewportSize(390, 844))) {
      var admin = adminSession.newPage();
      h.login(admin, "admin");
      admin.navigate(h.base() + "/enterprise/designer/global");
      var author = form(admin, "purchase_action");
      fill(
          author,
          map(
              "action_id",
              action,
              "supplier",
              "Browser supplier",
              "currency",
              "USD",
              "bind_quantity",
              "units"));
      assertThat(admin.locator("main")).containsText("Browser supplier");
      admin.navigate(h.base() + "/enterprise/walkup/global");
      fill(
          form(admin, "open_desk"),
          map("location", site, "capacity", 2, "minutes", 30, "revision", 0));
      var self = employeeSession.newPage();
      self.navigate(h.base() + "/login");
      self.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(employee);
      self.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
          .fill(WorkflowSupport.PASSWORD);
      self.getByRole(
              com.microsoft.playwright.options.AriaRole.BUTTON,
              new Page.GetByRoleOptions().setName("Enter your workspace"))
          .click();
      self.navigate(h.base() + "/enterprise/walkup/global");
      var join = form(self, "join_desk");
      String visit = join.locator("[name=visit_id]").inputValue();
      fill(join, map("location", site, "note", "Replace my laptop"));
      assertThat(self.locator("main")).containsText("waiting");
      fill(form(admin, "desk_status"), map("location", site));
      assertThat(admin.locator("main")).containsText(visit);
      fill(form(admin, "desk_claim"), map("visit_id", visit, "revision", 1));
      fill(form(self, "my_visit"), map("visit_id", visit));
      assertThat(self.locator("main")).containsText("claimed");
    }
  }
}
