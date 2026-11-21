package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;

import java.util.*;

final class ServiceDeliveryBrowser {
    static void listWorkflow(BrowserTest h) throws Exception {
        String policy = id(),
                run = id(),
                purchase = id(),
                name = "Reviewer" + id().substring(0, 8),
                reviewer,
                offering;
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            Service.createAccount(
                    db, map("name", name, "role", "agent", "password", WorkflowSupport.PASSWORD));
            reviewer = RecordAccess.owner(db, name);
            offering =
                    Catalogue.save(
                                    db,
                                    admin,
                                    "",
                                    CatalogueAcceptance.offering("Browser purchase", 0, true))
                            .get("offering_id")
                            .toString();
            EnterpriseData.save(
                    db, "purchase", purchase, 0, map("lines", map("display", map("quantity", 1))));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var ownerContext = browser.newContext()) {
            var admin = adminContext.newPage();
            h.login(admin, "admin");
            admin.navigate(h.base() + "/enterprise/designer/global");
            EnterpriseBrowserAcceptance.fill(
                    EnterpriseBrowserAcceptance.form(admin, "list_policy"),
                    map(
                            "policy_id",
                            policy,
                            "title",
                            "Purchasing approvals",
                            "item_1",
                            "display",
                            "owner_1",
                            reviewer,
                            "offering_1",
                            offering));
            assertThat(admin.locator("main")).containsText("Purchasing approvals");
            EnterpriseBrowserAcceptance.fill(
                    EnterpriseBrowserAcceptance.form(admin, "start_list"),
                    map(
                            "new_id",
                            run,
                            "policy_id",
                            policy,
                            "source",
                            "purchase",
                            "record_id",
                            purchase));
            var owner = ownerContext.newPage();
            h.login(owner, name);
            owner.navigate(h.base() + "/enterprise/designer/global");
            owner.getByRole(
                            com.microsoft.playwright.options.AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Review purchase " + run))
                    .click();
            assertThat(
                            owner.locator("section")
                                    .filter(
                                            new Locator.FilterOptions()
                                                    .setHasText("Assigned items"))
                                    .first())
                    .containsText("display");
            owner.getByRole(
                            com.microsoft.playwright.options.AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Approve my items"))
                    .click();
            EnterpriseBrowserAcceptance.fill(
                    EnterpriseBrowserAcceptance.form(admin, "fulfill_line"),
                    map("new_id", run, "item", "display", "revision", 2));
            assertThat(admin.locator("main pre").first()).containsText("receipt");
        }
    }

    static void run(BrowserTest h) throws Exception {
        String employee = "Guide" + id().substring(0, 8),
                guide = id(),
                policy,
                offering,
                beneficiary;
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            Service.createAccount(
                    db,
                    map(
                            "name",
                            employee,
                            "role",
                            "requester",
                            "password",
                            WorkflowSupport.PASSWORD));
            String managed = "Hire" + id().substring(0, 8);
            Service.createAccount(
                    db,
                    map(
                            "name",
                            managed,
                            "role",
                            "requester",
                            "password",
                            WorkflowSupport.PASSWORD));
            beneficiary =
                    db.one("SELECT user_id FROM os_user WHERE name=?", managed)
                            .get("user_id")
                            .toString();
            String manager =
                    db.one("SELECT user_id FROM os_user WHERE name=?", employee)
                            .get("user_id")
                            .toString();
            EnterpriseData.save(db, "workflow-identity", beneficiary, 0, map("manager", manager));
            String groupOnly =
                    PlatformFoundationTest.record(db, "sys_user_group", "Manager catalogue");
            db.update(
                    "INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)",
                    groupOnly,
                    manager);
            var leaderItem =
                    Catalogue.save(
                            db,
                            admin,
                            "",
                            CatalogueAcceptance.offering("Manager approval console", 0, true));
            CatalogueEligibility.save(
                    db,
                    admin,
                    leaderItem.get("offering_id").toString(),
                    map("revision", 1, "group_id", groupOnly));
            var item =
                    Catalogue.save(
                            db,
                            admin,
                            "",
                            CatalogueAcceptance.offering("Engineering VPN", 0, true));
            offering = item.get("offering_id").toString();
            CatalogueQuestions.save(
                    db,
                    admin,
                    offering,
                    map(
                            "revision",
                            1,
                            "questions",
                            List.of(
                                    map(
                                            "key",
                                            "device",
                                            "label",
                                            "Device name",
                                            "type",
                                            "string",
                                            "required",
                                            true,
                                            "choices",
                                            List.of()))));
            policy =
                    KnowledgeSearchAcceptance.article(
                            db, admin, "Remote working policy", "Request VPN for the work device.");
            var group = PlatformFoundationTest.record(db, "sys_user_group", "Service desk");
            EmployeeAssistant.command(
                    h.store,
                    db,
                    "intent",
                    map(
                            "new_id",
                            id(),
                            "terms",
                            "work remotely",
                            "article_id",
                            policy,
                            "offering_id",
                            offering,
                            "group_id",
                            group,
                            "revision",
                            0));
            OrderGuides.command(
                    h.store,
                    db,
                    "define",
                    map(
                            "guide_id",
                            guide,
                            "title",
                            "New hire",
                            "revision",
                            0,
                            "entries",
                            List.of(
                                    map(
                                            "offering_id",
                                            offering,
                                            "role",
                                            "engineer",
                                            "location",
                                            "*"),
                                    map(
                                            "offering_id",
                                            leaderItem.get("offering_id"),
                                            "role",
                                            "*",
                                            "location",
                                            "*"))));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            page.navigate(h.base() + "/login");
            page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(employee);
            page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                    .fill(WorkflowSupport.PASSWORD);
            page.getByRole(
                            com.microsoft.playwright.options.AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Enter your workspace"))
                    .click();
            page.navigate(h.base() + "/enterprise/guides/global");
            EnterpriseBrowserAcceptance.fill(
                    EnterpriseBrowserAcceptance.form(page, "preview"),
                    map("guide_id", guide, "job_role", "engineer", "location", "HQ"));
            var checkout = page.locator("form:has(input[name=checkout][value=guide])");
            assertThat(checkout).containsText("Manager approval console");
            checkout.getByLabel("Device name").fill("My laptop");
            checkout.getByLabel("Order details").fill("Replace my laptop");
            checkout.locator("button").click();
            assertThat(page.locator("main pre").first()).containsText("reserved");
            EnterpriseBrowserAcceptance.fill(
                    EnterpriseBrowserAcceptance.form(page, "preview_for"),
                    map(
                            "guide_id",
                            guide,
                            "job_role",
                            "engineer",
                            "location",
                            "HQ",
                            "employee_id",
                            beneficiary));
            assertThat(checkout.locator("fieldset")).hasCount(1);
            assertThat(checkout.getByLabel("Requested for employee")).hasValue(beneficiary);
            checkout.getByLabel("Device name").fill("Work laptop");
            checkout.getByLabel("Order details").fill("New hire");
            checkout.locator("button").click();
            assertThat(page.locator("main pre").first()).containsText("reserved");
            page.navigate(h.base() + "/enterprise/assistant/global");
            EnterpriseBrowserAcceptance.fill(
                    EnterpriseBrowserAcceptance.form(page, "ask"),
                    map("question", "Can I work remotely tomorrow?"));
            var confirm = page.locator("form:has(input[name=checkout][value=conversation])");
            assertThat(confirm.locator("a").first()).hasAttribute("href", "/knowledge/" + policy);
            page.navigate(h.base() + "/enterprise/assistant/global");
            EnterpriseBrowserAcceptance.fill(
                    EnterpriseBrowserAcceptance.form(page, "search_services"),
                    map("question", "Working remotely at home"));
            var suggestion =
                    page.locator(
                            "form:has(input[name=operation][value=choose_service]):has(input[type=hidden][name=intent_id])");
            assertThat(suggestion.locator("a")).hasAttribute("href", "/knowledge/" + policy);
            suggestion.locator("button").click();
            assertThat(confirm.locator("a").first()).hasAttribute("href", "/knowledge/" + policy);
            confirm.getByLabel("Device name").fill("Remote laptop");
            confirm.getByLabel("Request details").fill("Working from home");
            confirm.locator("button").click();
            assertThat(page.locator("main pre").first()).containsText("submitted");
        }
    }
}
