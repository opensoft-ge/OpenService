package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static ge.opensoft.openservice.EnterpriseBrowserAcceptance.*;
import static ge.opensoft.openservice.EnterpriseData.save;
import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import java.util.List;

final class IndustryWorkspaceBrowser {
  private IndustryWorkspaceBrowser() {}

  static void run(BrowserTest h) throws Exception {
    String app = id(), other = id(), stage = id(), ticket = id(), team, admin;
    try (var db = h.store.open()) {
      admin = PlatformFoundationTest.bind(db);
      team = PlatformFoundationTest.record(db, "sys_user_group", "Regional review");
      db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", team, admin);
      var fields = map("reason", map("visibility", "requester"));
      for (String id : List.of(app, other))
        save(db, "intake-release", id, 0, map("title", "Intake", "fields", fields));
      save(
          db,
          "intake-case",
          ticket,
          0,
          map(
              "requester",
              admin,
              "group",
              team,
              "app",
              app,
              "fields",
              fields,
              "answers",
              map("reason", "Review supplier terms"),
              "private_answers",
              map()));
      String asset = id();
      save(
          db,
          "warranty-claim",
          DeliveryWorkspace.key(asset, "W-005"),
          0,
          map(
              "asset",
              asset,
              "dealer",
              admin,
              "reference",
              "W-005",
              "terms",
              map("dealer", admin, "serial", "Serial-100"),
              "failure",
              "2026-05-01",
              "state",
              "filed"));
      db.connection.commit();
    }
    try (var pw = Playwright.create();
        var browser = pw.chromium().launch();
        var session = browser.newContext()) {
      var page = session.newPage();
      h.login(page, "admin");
      page.navigate(h.base() + "/enterprise/apps/global");
      fill(
          form(page, "review_stage"),
          map(
              "stage_id",
              stage,
              "group_id",
              team,
              "title",
              "Independent legal review",
              "note",
              "Review date and terms",
              "revision",
              0));
      for (String id : List.of(app, other))
        fill(
            form(page, "compose_workspace"),
            map(
                "app_id",
                id,
                "title",
                id.equals(app) ? "Supplier exception" : "HR review",
                "fields",
                "[\"reason\"]",
                "steps",
                JSON.writeValueAsString(List.of(stage)),
                "revision",
                0));
      fill(form(page, "guide_case"), map("case_id", ticket));
      assertThat(
              page.getByRole(
                  com.microsoft.playwright.options.AriaRole.HEADING,
                  new Page.GetByRoleOptions().setName("Independent legal review")))
          .isVisible();
      fill(form(page, "guided_queue"), map("app_id", app));
      page.getByRole(
              com.microsoft.playwright.options.AriaRole.BUTTON,
              new Page.GetByRoleOptions().setName("Independent legal review · review"))
          .click();
      assertThat(
              page.locator("dd")
                  .filter(new Locator.FilterOptions().setHasText("Review supplier terms")))
          .isVisible();
      for (var decision : List.of("Pause for a response", "Resume review", "Complete this stage")) {
        var panel = page.locator("form[aria-label='Update case review']");
        panel.locator("textarea").fill("Confirmation from legal");
        panel.getByText(decision, new Locator.GetByTextOptions().setExact(true)).click();
        if (!decision.equals("Complete this stage")) {
          fill(form(page, "guide_case"), map("case_id", ticket));
          assertThat(
                  page.locator("ol")
                      .filter(new Locator.FilterOptions().setHasText("Confirmation from legal"))
                      .first())
              .isVisible();
        }
      }
      fill(form(page, "guide_case"), map("case_id", ticket));
      assertThat(
              page.locator("section")
                  .filter(
                      new Locator.FilterOptions()
                          .setHas(
                              page.getByText(
                                  "Case review", new Page.GetByTextOptions().setExact(true)))))
          .containsText("complete");
      page.navigate(h.base() + "/enterprise/sales/global");
      fill(form(page, "my_claims"), map());
      assertThat(page.getByText("Serial-100 · 2026-05-01 · filed")).isVisible();
      page.getByRole(
              com.microsoft.playwright.options.AriaRole.BUTTON,
              new Page.GetByRoleOptions().setName("W-005"))
          .click();
      page.navigate(h.base() + "/enterprise/routing/global");
      var company = form(page, "contract_company");
      company.locator("[name=active]").selectOption("true");
      fill(company, map("group_id", team, "user_id", admin, "revision", 0));
      assertThat(page.locator("h1")).containsText("Territory");
      fill(form(page, "contract_queue"), map());
      assertThat(
              page.getByRole(
                  com.microsoft.playwright.options.AriaRole.HEADING,
                  new Page.GetByRoleOptions().setName("My company’s repair queue")))
          .isVisible();
    }
  }
}
