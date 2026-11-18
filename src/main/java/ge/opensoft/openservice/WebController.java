package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import jakarta.servlet.http.*;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Controller
public class WebController {
    private final Store store;

    public WebController(Store store) {
        this.store = store;
    }

    record Request(
            Store.Db db,
            HttpServletRequest http,
            HttpServletResponse response,
            HttpSession session,
            Map<String, Object> user) {
        String name() {
            return (String) user.get("name");
        }

        String role() {
            return (String) user.get("role");
        }

        String parameter(String key, String fallback) {
            if (http.getRequestURI().equals("/api/now/attachment/file"))
                return queryArguments(this).getOrDefault(key, fallback);
            return Objects.toString(http.getParameter(key), fallback);
        }

        Map<String, String> arguments() {
            if (http.getRequestURI().equals("/api/now/attachment/file"))
                return queryArguments(this);
            var values = new LinkedHashMap<String, String>();
            http.getParameterMap().forEach((key, value) -> values.put(key, value[0]));
            return values;
        }

        void admin() {
            if (!role().equals("admin"))
                throw new Failure(403, "Only administrators can perform this action");
        }

        void noQuery() {
            if (http.getQueryString() != null && !http.getQueryString().isEmpty())
                throw new Failure(400, "Write query parameters are not yet supported");
        }

        void uniqueQuery() {
            if (http.getRequestURI().equals("/api/now/attachment/file")) {
                queryArguments(this);
                return;
            }
            if (http.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
                throw new Failure(400, "Repeated query parameter");
        }

        String base() {
            String url = http.getRequestURL().toString();
            return url.substring(0, url.length() - http.getRequestURI().length());
        }
    }

    @RequestMapping("/**")
    public ModelAndView dispatch(HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors"
                        + " 'none'; form-action 'self'; base-uri 'self'");
        if(Set.of("/static/app.css","/static/theme.js").contains(request.getRequestURI())) {
            if(!Set.of("GET","HEAD").contains(request.getMethod()))return new ModelAndView("error",map("errorTitle","Method Not Allowed","errorDetail","GET or HEAD required"),HttpStatus.METHOD_NOT_ALLOWED);
            boolean css=request.getRequestURI().equals("/static/app.css");
            if(css)response.setHeader("Content-Disposition","inline; filename=app.css");
            try(var input=WebController.class.getResourceAsStream(request.getRequestURI())) {
                return binary(Objects.requireNonNull(input).readAllBytes(),css?"text/css; charset=utf-8":"text/javascript; charset=utf-8",200);
            }
        }
        if (request.getRequestURI().equals("/readyz")) {
            if (!request.getMethod().equals("GET")) return json(map("error", "GET required"), 405);
            if (request.getQueryString() != null && !request.getQueryString().isEmpty())
                return json(map("error", "Query parameters are not supported"), 400);
            boolean ready = Readiness.ready(store);
            return json(map("status", ready ? "ready" : "not_ready"), ready ? 200 : 503);
        }
        Store.Db acquired;
        try { acquired=store.open(); }
        catch(java.sql.SQLException unavailable) {
            if(store.postgres || !Set.of(5,6).contains(unavailable.getErrorCode() & 255))throw unavailable;
            response.setHeader("Retry-After","1");
            String detail="The database is busy. This request did not start. Try again shortly.";
            if(request.getRequestURI().startsWith("/api/"))return json(map("error",map("message","Service Unavailable","detail",detail),"status","failure"),503);
            return new ModelAndView("error",map("errorTitle","Service Unavailable","errorDetail",detail),HttpStatus.SERVICE_UNAVAILABLE);
        }
        try (var db = acquired) { return dispatch(db, request, response); }
    }

    private ModelAndView dispatch(
            Store.Db db, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        HttpSession session = request.getSession();
        if (session.getAttribute("csrf") == null) session.setAttribute("csrf", csrf());
        try {
            if (request.getContentLengthLong() > 6 * 1024 * 1024)
                throw new Failure(413, "Request exceeds 6 MB");
            String path = request.getRequestURI(), method = request.getMethod();
            boolean publicPath =
                    Set.of("/healthz", "/login", "/forgot")
                                    .contains(path)
                            || path.matches("/(?:join|recover|verify-email)/[A-Za-z0-9_-]{32}");
            var user =
                    session.getAttribute("user") == null
                            ? null
                            : db.one(
                                    "SELECT * FROM os_user WHERE user_id=? AND active=1",
                                    session.getAttribute("user"));
            if (user != null
                    && (!Objects.equals(
                                    session.getAttribute("auth_revision"),
                                    ((Number) user.get("auth_revision")).longValue())
                            || !SessionLifetime.valid(
                                    session.getAttribute("authenticated_at"),
                                    System.currentTimeMillis())
                            || !BrowserSessions.touch(
                                    db, user, session.getId(), System.currentTimeMillis()))) {
                session.invalidate();
                session = request.getSession(true);
                session.setAttribute("csrf", csrf());
                user = null;
            }
            String authorization = request.getHeader("Authorization");
            if (!publicPath && authorization != null && !authorization.isEmpty())
                user = authorization.startsWith("Bearer ")
                        ? ApiKeys.authenticate(db, authorization, path, method)
                        : authenticate(db, authorization);
            if (!publicPath && user == null) {
                if (path.startsWith("/api/")) throw new Failure(401, "Authentication required");
                return redirect("/login");
            }
            if (!publicPath && AccessMode.apiOnly(user) && !path.startsWith("/api/"))
                throw new Failure(403, "This account permits API access only");
            if (!publicPath
                    && PasswordReset.required(user)
                    && !Set.of(
                                    "/password-required",
                                    "/api/openservice/me/password",
                                    "/api/openservice/me/csrf",
                                    "/logout")
                            .contains(path)) {
                if (path.startsWith("/api/"))
                    throw new Failure(
                            403,
                            "Password replacement required; change your password before accessing"
                                + " the workspace");
                return redirect("/password-required");
            }
            db.viewerId = user == null ? "" : user.get("user_id").toString();
            db.viewerRole = user == null ? "" : user.get("role").toString();
            var context = new Request(db, request, response, session, user);
            if (path.startsWith("/api/")) context.uniqueQuery();
            if (!publicPath && !Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
                if (BrowserWrites.requiresCsrf(request, path, authorization))
                    checkCsrf(
                            context,
                            Objects.toString(
                                    request.getHeader("X-CSRF-Token"),
                                    context.parameter("csrf", "")));
                if (Set.of("reader", "requester").contains(context.role())
                        && !Set.of(
                                        "/logout",
                                        "/password-required",
                                        "/account/sessions/revoke",
                                        "/account/password",
                                        "/account/email",
                                        "/api/openservice/me/email",
                                        "/api/openservice/me/notifications",
                                        "/account/notifications",
                                        "/account/profile",
                                        "/api/openservice/me/profile",
                                        "/api/openservice/me/password",
                                        "/api/openservice/me/sessions/revoke")
                                .contains(path)
                        && !path.matches(
                                "/(?:account|api/openservice/me)/sessions/[0-9a-f]{32}/revoke")
                        && !(context.role().equals("requester")
                                && (Set.of("/requests", "/api/openservice/requests").contains(path) || path.matches("/(?:customer-service|api/openservice/customer-service)/entitlements/[0-9a-f]{32}") || path.matches("/(?:catalogue/[0-9a-f]{32}(?:/assistant)?|api/openservice/catalogue/[0-9a-f]{32}/(?:order|assistant))")))
                        && !path.matches("/(?:knowledge|api/openservice/knowledge)/[0-9a-f]{32}/(?:feedback|assistant)")
                        && !path.matches("/(?:support|api/openservice/support)(?:/[0-9a-f]{32})?")
                        && !path.matches(
                                "/(?:api/openservice/comments/[^/]+/[0-9a-f]{32}|record/[^/]+/[0-9a-f]{32}/comment)"))
                    throw new Failure(403, "Read-only account");
                // Configuration promotion, record writes and relationship deletion share a lock.
                if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
                Authentication.requireCurrent(db, user);
                if (authorization != null && authorization.startsWith("Bearer "))
                    ApiKeys.authenticate(db, authorization, path, method);
                if (authorization == null || authorization.isEmpty())
                    BrowserSessions.requireCurrent(
                            db, user, session.getId(), System.currentTimeMillis());
            }
            var result = route(context, path, method.equals("HEAD") ? "GET" : method);
            if (result.getStatus() != null && result.getStatus().value() >= 400)
                db.connection.rollback();
            else {
                if (authorization != null && authorization.startsWith("Bearer ") && !publicPath)
                    ApiKeyUsage.accepted(db, user.get("api_key_id").toString());
                db.connection.commit();
            }
            return result;
        } catch (Failure error) {
            db.connection.rollback();
            response.setStatus(error.status);
            if (error.status == 429) response.setHeader("Retry-After", "300");
            if (error.status == 401)
                response.setHeader("WWW-Authenticate", "Basic realm=\"OpenService\"");
            if (request.getRequestURI().startsWith("/api/"))
                return json(
                        map(
                                "error",
                                map("message", error.title(), "detail", error.getMessage()),
                                "status",
                                "failure"),
                        error.status);
            return new ModelAndView(
                    "error",
                    map("errorTitle", error.title(), "errorDetail", error.getMessage()),
                    HttpStatus.valueOf(error.status));
        }
    }

    private Map<String, Object> authenticate(Store.Db db, String header) throws Exception {
        try {
            if (!header.regionMatches(true, 0, "Basic ", 0, 6))
                throw new IllegalArgumentException();
            String[] pair =
                    new String(
                                    Base64.getDecoder().decode(header.substring(6)),
                                    StandardCharsets.UTF_8)
                            .split(":", 2);
            if (pair.length != 2) throw new Failure(401, "Invalid credentials");
            var user = Authentication.verify(db, pair[0], pair[1], "basic");
            if (user == null) throw new Failure(401, "Invalid credentials");
            return user;
        } catch (IllegalArgumentException error) {
            throw new Failure(401, "Invalid credentials");
        }
    }

    private String csrf() throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(store.secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal(Passwords.token().getBytes(StandardCharsets.UTF_8)));
    }

    private static void checkCsrf(Request context, String supplied) {
        if (!MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                context.session.getAttribute("csrf").toString().getBytes(StandardCharsets.UTF_8)))
            throw new Failure(403, "Invalid CSRF token");
    }

    private Object body(Request context) throws Exception {
        if (context.http.getContentType() == null
                || !context.http
                        .getContentType()
                        .split(";", 2)[0]
                        .strip()
                        .toLowerCase(Locale.ROOT)
                        .matches("application/(json|[^/; ]+\\+json)"))
            throw new Failure(415, "JSON content type is required");
        byte[] bytes = context.http.getInputStream().readNBytes(6 * 1024 * 1024 + 1);
        if (bytes.length > 6 * 1024 * 1024) throw new Failure(413, "Request exceeds 6 MB");
        try {
            return JSON.readValue(bytes, Object.class);
        } catch (tools.jackson.core.JacksonException error) {
            throw new Failure(400, "Invalid JSON body");
        }
    }

    private ModelAndView route(Request c, String path, String method) throws Exception {
        if(path.equals("/work-tasks") || path.equals("/api/openservice/work-tasks")){
            method(method,"GET");c.uniqueQuery();var result=WorkTasks.queue(c.db,c.arguments());return path.startsWith("/api/")?json(map("result",result),200):view(c,"work-task-queue",map("table","work","queue",result));
        }
        Matcher workTasks=Pattern.compile("/(?:record|api/openservice/records)/([a-z][a-z0-9_]{0,79})/([0-9a-f]{32})/work-tasks").matcher(path);
        if(workTasks.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String table=workTasks.group(1),id=workTasks.group(2);boolean api=path.startsWith("/api/");Map<String,Object> result;
            if(method.equals("POST")){
                Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");for(String key:List.of("revision","record_revision"))form.put(key,Service.number(Objects.toString(form.get(key),""),0,Integer.MAX_VALUE));if(Set.of("create","revise").contains(Objects.toString(form.get("operation"),""))){var predecessors=new ArrayList<String>();for(String key:new ArrayList<>(form.keySet()))if(key.startsWith("predecessor_")){TypedFields.require(form.remove(key).equals("true"),"Choose predecessor tasks");predecessors.add(key.substring(12));}form.put("predecessors",predecessors);}if(form.containsKey("required")){TypedFields.require(Set.of("true","false").contains(form.get("required")),"Choose whether the task is required");form.put("required",Boolean.parseBoolean(form.get("required").toString()));}input=form;}
                result=WorkTasks.command(c.db,table,id,input);if(!api)return redirect(path);
            }else result=WorkTasks.page(c.db,table,id);
            return api?json(map("result",result),200):view(c,"work-tasks",map("table",table,"work",result,"workPath","/record/"+table+"/"+id+"/work-tasks"));
        }
        Matcher recordScope=Pattern.compile("/(?:admin|api/openservice)/record-scopes/([a-z][a-z0-9_]{0,79})/([0-9a-f]{32})").matcher(path);
        if(recordScope.matches()){
            method(method,"GET","POST");c.admin();c.uniqueQuery();String table=recordScope.group(1),id=recordScope.group(2);boolean api=path.startsWith("/api/");Map<String,Object> result;
            if(method.equals("POST")){
                c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");for(String key:List.of("revision","record_revision"))form.put(key,Service.number(Objects.toString(form.get(key),""),0,Integer.MAX_VALUE));if(form.containsKey("parent_revision"))form.put("parent_revision",Service.number(form.get("parent_revision").toString(),0,Integer.MAX_VALUE));input=form;}
                result=RecordScopes.save(c.db,table,id,input);if(!api)return redirect(path);
            }else result=RecordScopes.view(c.db,table,id,c.arguments());
            return api?json(map("result",result),200):view(c,"record-scope",map("table",table,"boundary",result,"query",c.arguments().getOrDefault("q",""),"scopePath","/admin/record-scopes/"+table+"/"+id));
        }
        Matcher changePlan=Pattern.compile("/(?:record|api/openservice)/change_request/([0-9a-f]{32})/plan").matcher(path);
        if(changePlan.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String id=changePlan.group(1);boolean api=path.startsWith("/api/");Map<String,Object> result;
            if(method.equals("POST")){
                Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");for(String key:List.of("revision","record_revision","impact_score","likelihood"))form.put(key,Service.number(Objects.toString(form.get(key),""),0,Integer.MAX_VALUE));TypedFields.require(Set.of("true","false").contains(Objects.toString(form.get("ready"),"")),"Choose draft or ready");form.put("ready",Boolean.parseBoolean(form.get("ready").toString()));input=form;}
                result=ChangePlanning.save(c.db,id,input);if(!api)return redirect(path);
            }else result=ChangePlanning.detail(c.db,id);
            return api?json(map("result",result),200):view(c,"change-plan",map("table","change_request","planning",result));
        }
        Matcher problemOptions=Pattern.compile("/(?:record|api/openservice)/incident/([0-9a-f]{32})/problem-options").matcher(path);
        if(problemOptions.matches()){
            method(method,"GET");c.uniqueQuery();String id=problemOptions.group(1);var source=ProblemInvestigations.record(c.db,"incident",id);RecordPolicy.require(Service.schema(c.db,"incident"),"update",c.db.viewerRole);var search=ReferenceSearch.search(c.db,"problem",c.arguments());return path.startsWith("/api/")?json(map("result",search),200):view(c,"problem-options",map("table","incident","incident",FieldAccess.serialize(c.db,source),"search",search,"query",c.arguments().getOrDefault("q",""),"pickerPath","/record/incident/"+id+"/problem-options"));
        }
        Matcher investigation=Pattern.compile("/(?:record|api/openservice)/(incident|problem)/([0-9a-f]{32})/investigation").matcher(path);
        if(investigation.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String table=investigation.group(1),id=investigation.group(2);boolean api=path.startsWith("/api/"),incident=table.equals("incident");Map<String,Object> result;
            if(method.equals("POST")){
                Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),0,Integer.MAX_VALUE));if(!incident){form.put("record_revision",Service.number(Objects.toString(form.get("record_revision"),""),0,Integer.MAX_VALUE));TypedFields.require(Set.of("true","false").contains(Objects.toString(form.get("publish"),"")),"Choose draft or published");form.put("publish",Boolean.parseBoolean(form.get("publish").toString()));}input=form;}
                result=incident?ProblemInvestigations.link(c.db,id,input):ProblemInvestigations.save(c.db,id,input);if(!api)return redirect(path);
            }else result=incident?ProblemInvestigations.incident(c.db,id):ProblemInvestigations.problem(c.db,id);
            return api?json(map("result",result),200):view(c,incident?"incident-problem":"problem-investigation",map("table",table,"investigation",result));
        }
        Matcher configurationHistory=Pattern.compile("/(admin|api/openservice)/configuration/history(?:/([0-9a-f]{32})(/download)?)?").matcher(path);
        if(configurationHistory.matches()){
            method(method,"GET");c.admin();c.uniqueQuery();boolean api=path.startsWith("/api/");String id=configurationHistory.group(2);
            if(id==null){var result=ConfigurationHistory.page(c.db,c.arguments());return api?json(result,200):view(c,"configuration-history",map("table","configuration","history",result));}
            c.noQuery();var result=ConfigurationHistory.detail(c.db,id);boolean download=configurationHistory.group(3)!=null;
            if(download)disposition(c,"configuration-installation-"+id+".json");
            return api || download?json(map("result",result),200):view(c,"configuration-installation",map("table","configuration","installation",result,"receipt",JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result.get("detail"))));
        }
        Matcher knowledgeAssistant=Pattern.compile("/(?:knowledge|api/openservice/knowledge)/([0-9a-f]{32})/assistant").matcher(path);
        if(knowledgeAssistant.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String id=knowledgeAssistant.group(1);boolean api=path.startsWith("/api/");Map<String,Object> result;
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");if(form.containsKey("allow_external")){TypedFields.require(form.get("allow_external").equals("true"),"Confirm the external request");form.put("allow_external",true);}input=form;}result=KnowledgeQuestions.submit(store,c.db,id,input);}else result=KnowledgeQuestions.context(store,c.db,id);
            return api?json(map("result",result),200):view(c,"knowledge-assistant",map("table","knowledge","articleId",id,"assistant",result,"assistantPath","/knowledge/"+id+"/assistant"));
        }

        Matcher migrationFetch=Pattern.compile("/(?:admin/migrations/([0-9a-f]{32})/fetch|api/openservice/migrations/([0-9a-f]{32})/fetch-jobs)").matcher(path);
        if(migrationFetch.matches()){
            c.admin();c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");method(method,api?new String[]{"POST"}:new String[]{"GET","POST"});String id=api?migrationFetch.group(2):migrationFetch.group(1);
            if(method.equals("POST")){
                Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}
                var job=MigrationFetch.queue(store,c.db,c.db.viewerId,id,input);return api?json(map("result",job),201):redirect("/admin/jobs/"+job.get("job_id"));
            }
            return view(c,"migration-fetch",map("table","migration","migration",TransferJobs.describe(c.db,id),"queueKey",id(),"workerEnabled",store.environment.getProperty("WORK_ENABLED",Boolean.class,false)));
        }
        if(path.equals("/admin/migration-stops")){method(method,"GET");c.admin();c.noQuery();return view(c,"migration-stops",map("table","migration","directory",MigrationStops.directory(c.db)));}
        Matcher stopMigration=Pattern.compile("/(?:admin/migrations|api/openservice/migrations)/([0-9a-f]{32})/cancel").matcher(path);
        if(stopMigration.matches()){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();String id=stopMigration.group(1);boolean api=path.startsWith("/api/");Map<String,Object> result;
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}result=MigrationStops.cancel(c.db,id,input);if(!api)return redirect(path);}else result=TransferJobs.describe(c.db,id);
            return api?json(map("result",result),200):view(c,"migration-stop",map("table","migration","migration",result));
        }

        if(Set.of("/admin/events/case","/api/openservice/event-cases").contains(path)){
            method(method,"GET","POST");c.admin();c.uniqueQuery();boolean api=path.startsWith("/api/");
            if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}var result=EventCases.create(c.db,input);return api?json(map("result",result),201):redirect("/record/incident/"+result.get("sys_id"));}
            TypedFields.require(c.arguments().keySet().equals(Set.of("key")),"Provide key only");var result=EventCases.page(c.db,c.parameter("key",""));return api?json(map("result",result),200):view(c,"event-case",map("table","events","escalation",result));
        }

        if(Set.of("/support","/api/openservice/support").contains(path)){
            method(method,"GET","POST");c.uniqueQuery();boolean api=path.startsWith("/api/");if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}var result=SupportConversations.create(store,c.db,input);return api?json(map("result",result),201):redirect("/support/"+result.get("conversation_id"));}TypedFields.require(Set.of("article_id").containsAll(c.arguments().keySet()),"Use article_id only");String article=c.parameter("article_id","");Map<String,Object> source=article.isEmpty()?null:object(KnowledgeQuestions.context(store,c.db,article).get("source"));var conversations=SupportConversations.list(c.db);return api?json(map("result",conversations),200):view(c,"support-list",map("table","support","conversations",conversations,"source",source,"conversationKey",id()));
        }
        Matcher supportConversation=Pattern.compile("/(?:support|api/openservice/support)/([0-9a-f]{32})(/export)?").matcher(path);
        if(supportConversation.matches()){
            boolean api=path.startsWith("/api/"),export=supportConversation.group(2)!=null;method(method,export?new String[]{"GET"}:new String[]{"GET","POST"});c.noQuery();c.uniqueQuery();String id=supportConversation.group(1);Map<String,Object> result;if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));for(String flag:List.of("confirm","allow_external")){if(form.containsKey(flag)){TypedFields.require(Set.of("true","false").contains(form.get(flag)),"Use a boolean confirmation");form.put(flag,Boolean.parseBoolean(form.get(flag).toString()));}}input=form;}result=SupportConversations.submit(store,c.db,id,input);if(Boolean.TRUE.equals(result.get("deleted")))return api?json(map("result",result),200):redirect("/support");if(!api && !Set.of("preview","review_handoff").contains(object(input).get("operation")))return redirect("/support/"+id);}else result=SupportConversations.page(store,c.db,id);
            if(export){disposition(c,"support-"+id+".json");return binary(JSON.writeValueAsBytes(map("conversation_id",id,"source",result.get("source"),"state",result.get("state"),"request_id",result.get("request_id"),"turns",result.get("turns"))),"application/json",200);}return api?json(map("result",result),200):view(c,"support-conversation",map("table","support","conversation",result));
        }
        Matcher knowledgeFeedback=Pattern.compile("/(knowledge|api/openservice/knowledge)/([0-9a-f]{32})(/feedback)?").matcher(path);
        if(knowledgeFeedback.matches()){
            boolean api=path.startsWith("/api/"),write=knowledgeFeedback.group(3)!=null;method(method,write?"POST":"GET");c.noQuery();c.uniqueQuery();String id=knowledgeFeedback.group(2);Map<String,Object> result;
            if(write){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");String helpful=Objects.toString(form.get("helpful"),"");TypedFields.require(Set.of("true","false").contains(helpful),"Choose whether the article was helpful");form.put("helpful",Boolean.parseBoolean(helpful));form.put("version",Service.number(Objects.toString(form.get("version"),""),1,Integer.MAX_VALUE));input=form;}result=KnowledgeFeedback.submit(c.db,id,input);if(!api)return redirect("/knowledge/"+id);}
            else result=KnowledgeFeedback.page(c.db,id);
            return api?json(map("result",result),200):view(c,"knowledge-article",map("table","knowledge","article",result.get("article"),"feedback",result.get("feedback")));
        }
        Matcher knowledgeReview=Pattern.compile("/(?:admin/knowledge/([0-9a-f]{32})/feedback|api/openservice/knowledge-feedback/([0-9a-f]{32}))").matcher(path);
        if(knowledgeReview.matches()){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");String id=Objects.toString(knowledgeReview.group(1),knowledgeReview.group(2));
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));input=form;}KnowledgeFeedback.review(c.db,id,input);if(!api)return redirect(path);}
            var result=KnowledgeFeedback.reviewPage(c.db,id);return api?json(map("result",result),200):view(c,"knowledge-feedback-review",map("table","knowledge","review",result));
        }

        Matcher reportExport=Pattern.compile("/(?:reports|api/openservice/reports)/([0-9a-f]{32})/export\\.csv").matcher(path);
        if(reportExport.matches()){method(method,"GET");c.noQuery();String id=reportExport.group(1);byte[] csv=ReportExport.csv(c.db,c.db.viewerId,id);disposition(c,"report-"+id+".csv");return binary(csv,"text/csv; charset=UTF-8",200);}

        Matcher attachmentScan=Pattern.compile("/(?:admin|api/openservice)/attachments/([0-9a-f]{32})/scan").matcher(path);
        if(attachmentScan.matches()){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();String id=attachmentScan.group(1);boolean api=path.startsWith("/api/");if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}var result=AttachmentScanning.rescan(c.db,id,input);return api?json(map("result",result),200):redirect(path);}var result=AttachmentScanning.page(c.db,id);return api?json(map("result",result),200):view(c,"attachment-scan",map("table",result.get("table"),"file",result));
        }
        Matcher attachmentAccess=Pattern.compile("/(admin/attachments|api/openservice/attachment-access)/([0-9a-f]{32})").matcher(path);
        if(attachmentAccess.matches()){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();String id=attachmentAccess.group(2);boolean api=path.startsWith("/api/");
            if(method.equals("POST")){
                Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");String held=Objects.toString(form.get("held"),"");TypedFields.require(Set.of("true","false").contains(held),"Choose download access");form.put("held",Boolean.parseBoolean(held));input=form;}
                var result=AttachmentHolds.change(c.db,id,input);return api?json(map("result",result),200):redirect(path);
            }
            var result=AttachmentHolds.inspect(c.db,id);return api?json(map("result",result),200):view(c,"attachment-access",map("table",result.get("table"),"file",result));
        }

        if(Set.of("/admin/connector-actions","/api/openservice/connector-actions").contains(path)){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");
            if(method.equals("POST")){var saved=ConnectorActions.save(c.db,"",body(c));return json(map("result",saved),201);}
            var actions=ConnectorActions.list(c.db);return api?json(map("result",actions),200):view(c,"connector-actions",map("table","operations","actions",actions));
        }
        Matcher connectorAction=Pattern.compile("/(?:admin|api/openservice)/connector-actions/(new|[0-9a-f]{32})").matcher(path);
        if(connectorAction.matches()){
            boolean api=path.startsWith("/api/"),fresh=connectorAction.group(1).equals("new");method(method,"GET",api?"PUT":"POST");c.admin();c.noQuery();c.uniqueQuery();String id=fresh?"":connectorAction.group(1);
            if(!method.equals("GET")){
                Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),0,Integer.MAX_VALUE));TypedFields.require(Set.of("true","false").contains(form.get("active")),"Choose active or inactive");form.put("active",Boolean.parseBoolean(form.get("active").toString()));form.put("inputs",JSON.readValue(Objects.toString(form.get("inputs"),""),Object.class));input=form;}
                var result=ConnectorActions.save(c.db,id,input);return api?json(map("result",result),200):redirect("/admin/connector-actions/"+result.get("action_id"));
            }
            var action=fresh?map("action_id","","name","","alias","","method","POST","path","/","inputs",List.of(),"active",1,"revision",0):ConnectorActions.find(c.db,id);
            return api?json(map("result",action),200):view(c,"connector-action",map("table","operations","action",action,"inputsJson",JSON.writeValueAsString(action.get("inputs"))));
        }
        Matcher connectorActionRun=Pattern.compile("/(?:admin|api/openservice)/connector-actions/([0-9a-f]{32})/run").matcher(path);
        if(connectorActionRun.matches()){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();String id=connectorActionRun.group(1);boolean api=path.startsWith("/api/");Map<String,Object> preview=null;
            if(method.equals("POST")){
                Map<String,Object> input;if(api)input=new LinkedHashMap<>(object(body(c)));else{input=new LinkedHashMap<>(c.arguments());input.remove("csrf");CatalogueQuestions.form(input);input.put("values",input.getOrDefault("answers",map()));input.remove("answers");if(input.containsKey("confirm")){TypedFields.require(input.get("confirm").equals("true"),"Confirm the reviewed external write");input.put("confirm",true);}}
                String operation=WorkJobs.text(input.remove("operation"),12);TypedFields.require(Set.of("preview","queue").contains(operation),"Choose preview or queue");
                if(operation.equals("queue")){var job=ConnectorActions.queue(store,c.db,id,input);return api?json(map("result",job),201):redirect("/admin/jobs/"+job.get("job_id"));}
                preview=ConnectorActions.preview(store,c.db,id,input);
            }
            var result=map("action",ConnectorActions.find(c.db,id),"preview",preview,"key",id());return api?json(map("result",result),200):view(c,"connector-action-run",map("table","operations","run",result,"workEnabled",store.environment.getProperty("WORK_ENABLED",Boolean.class,false)));
        }
        Matcher connectorReconcile=Pattern.compile("/(?:admin|api/openservice/operations)/jobs/([0-9a-f]{32})/reconcile").matcher(path);
        if(connectorReconcile.matches()){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();String id=connectorReconcile.group(1);boolean api=path.startsWith("/api/");
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}ConnectorWrites.reconcile(c.db,id,input);if(!api)return redirect(path);}
            var result=ConnectorWrites.reconciliation(c.db,id);return api?json(map("result",result),200):view(c,"connector-reconcile",map("table","operations","review",result));
        }

        Matcher connectorConsole=Pattern.compile("/admin/connectors/([a-z][a-z0-9_-]{0,79})").matcher(path);
        if(connectorConsole.matches()){
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();String alias=connectorConsole.group(1);
            if(method.equals("POST"))return redirect(ConnectorConsole.submit(store,c.db,alias,c.arguments()));
            return view(c,"connector-console",map("table","operations","connector",ConnectorConsole.page(c.db,alias),"probeKey",id(),"workEnabled",store.environment.getProperty("WORK_ENABLED",Boolean.class,false)));
        }

        Matcher recordClocks=Pattern.compile("/record/([a-z][a-z0-9_]*)/([0-9a-f]{32})/clocks").matcher(path);
        if(recordClocks.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String table=recordClocks.group(1),id=recordClocks.group(2);
            if(method.equals("POST")){RecordClocks.submit(c.db,table,id,c.arguments());return redirect(path);}
            return view(c,"record-clocks",map("table",table,"clockView",RecordClocks.page(c.db,table,id),"recordId",id,"clockWorker",store.environment.getProperty("WORK_ENABLED",Boolean.class,false)));
        }

        if(Set.of("/fulfilment","/api/openservice/fulfilment").contains(path)){
            method(method,"GET");c.uniqueQuery();var page=Fulfilment.page(c.db,c.arguments());
            return path.startsWith("/api/")?json(page,200):view(c,"fulfilment-queue",map("table","sc_request","work",page));
        }
        Matcher fulfilment=Pattern.compile("/(requests|api/openservice/requests)/([0-9a-f]{32})/fulfilment").matcher(path);
        if(fulfilment.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");String id=fulfilment.group(2);
            if(method.equals("POST")){
                Object input;
                if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));form.put("request_revision",Service.number(Objects.toString(form.get("request_revision"),""),0,Integer.MAX_VALUE));input=form;}
                var result=Fulfilment.control(c.db,id,input);return api?json(map("result",result),200):redirect("/requests/"+id+"/fulfilment");
            }
            var result=Fulfilment.detail(c.db,id);return api?json(map("result",result),200):view(c,"fulfilment",map("table","sc_request","task",result,"groups",c.role().equals("admin")?RecordAccess.groups(c.db):List.of()));
        }
        Matcher submittedAnswers=Pattern.compile("/api/openservice/requests/([0-9a-f]{32})/answers").matcher(path);
        if(submittedAnswers.matches()){
            method(method,"GET");c.noQuery();return json(map("result",CatalogueQuestions.answers(c.db,submittedAnswers.group(1))),200);
        }
        Matcher catalogueQuestions=Pattern.compile("/(admin|api/openservice)/catalogue/([0-9a-f]{32})/questions").matcher(path);
        if(catalogueQuestions.matches()){
            c.admin();c.uniqueQuery();boolean api=path.startsWith("/api/");method(method,api?new String[]{"PUT"}:new String[]{"GET","POST"});String id=catalogueQuestions.group(2);
            if(method.equals("GET")){
                TypedFields.require(Set.of("question").containsAll(c.arguments().keySet()),"Use question only");var offering=Catalogue.find(c.db,id,true);return view(c,"catalogue-questions",map("table","catalogue","offering",offering,"question",CatalogueQuestions.editor(offering,c.arguments().getOrDefault("question",""))));
            }
            c.noQuery();Map<String,Object> result;
            if(api)result=CatalogueQuestions.save(c.db,c.db.viewerId,id,body(c));
            else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));result=CatalogueQuestions.edit(c.db,c.db.viewerId,id,form);}
            return api?json(map("result",result),200):redirect("/admin/catalogue/"+id+"/questions");
        }
        Matcher customerAccount=Pattern.compile("/(admin|api/openservice)/customer-accounts(?:/([0-9a-f]{32}))?").matcher(path);
        if(customerAccount.matches()){
            c.admin();method(method,"GET","POST");c.uniqueQuery();boolean api=path.startsWith("/api/");String id=Objects.toString(customerAccount.group(2),"");Map<String,Object> result;
            if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");for(String field:List.of("revision","case_limit"))if(form.containsKey(field))form.put(field,Service.number(form.get(field).toString(),0,Integer.MAX_VALUE));String active=Objects.toString(form.get("active"),"");TypedFields.require(Set.of("true","false").contains(active),"Choose active or inactive");form.put("active",Boolean.parseBoolean(active));input=form;}result=id.isEmpty()?CustomerAccounts.save(c.db,id,input):CustomerAccounts.manage(c.db,id,input);if(!api)return redirect("/admin/customer-accounts/"+(id.isEmpty()?result.get("account_id"):id));}
            else if(id.isEmpty()){c.noQuery();result=map("accounts",c.db.all("SELECT * FROM os_customer_account ORDER BY name,account_id LIMIT 200"),"groups",Service.page(c.db,"sys_user_group",Map.of("sysparm_limit","100"),"").rows());}
            else{TypedFields.require(Set.of("username").containsAll(c.arguments().keySet()),"Use username only for contact lookup");result=CustomerAccounts.page(c.db,id);result.put("selected_contact",CustomerAccounts.lookup(c.db,c.arguments().getOrDefault("username","")));}
            return api?json(map("result",result),200):view(c,id.isEmpty()?"customer-accounts":"customer-account",map("table","customer_case","definition",map("label","Customer service"),"page",result));
        }
        if(Set.of("/customer-service","/api/openservice/customer-service").contains(path)){
            method(method,"GET");c.uniqueQuery();var result=CustomerCases.portal(c.db,c.arguments());return path.startsWith("/api/")?json(map("result",result),200):view(c,"customer-service",map("table","customer_case","definition",map("label","Customer service"),"page",result));
        }
        Matcher customerOffering=Pattern.compile("/(customer-service|api/openservice/customer-service)/entitlements/([0-9a-f]{32})").matcher(path);
        if(customerOffering.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String id=customerOffering.group(2);boolean api=path.startsWith("/api/");
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");for(String field:List.of("account_revision","contact_revision","entitlement_revision"))form.put(field,Service.number(Objects.toString(form.get(field),""),0,Integer.MAX_VALUE));input=form;}var item=CustomerCases.create(c.db,id,input);return api?json(map("result",FieldAccess.serialize(c.db,item)),201):redirect("/record/customer_case/"+item.get("sys_id"));}
            var result=CustomerCases.offering(c.db,id);return api?json(map("result",result),200):view(c,"customer-intake",map("table","customer_case","definition",map("label","Customer service"),"page",result,"intakeKey",Values.id()));
        }
        Matcher customerSnapshot=Pattern.compile("/api/openservice/customer-cases/([0-9a-f]{32})/intake").matcher(path);
        if(customerSnapshot.matches()){method(method,"GET");c.noQuery();var result=CustomerCases.snapshot(c.db,customerSnapshot.group(1));return json(map("result",result),200);}
        if(Set.of("/major-incidents","/api/openservice/major-incidents").contains(path)){
            method(method,"GET");c.uniqueQuery();var result=MajorIncidents.queue(c.db,c.arguments());return path.startsWith("/api/")?json(map("result",result),200):view(c,"major-incident-queue",map("table","incident","queue",result));
        }
        Matcher majorIncident=Pattern.compile("/(record/incident|api/openservice/incidents)/([0-9a-f]{32})/major").matcher(path);
        if(majorIncident.matches()){
            method(method,"GET","POST");c.uniqueQuery();boolean api=path.startsWith("/api/");String id=majorIncident.group(2);Map<String,Object> result;
            if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");for(String field:List.of("revision","record_revision"))form.put(field,Service.number(Objects.toString(form.get(field),""),0,Integer.MAX_VALUE));if(form.containsKey("child_revision"))form.put("child_revision",Service.number(form.get("child_revision").toString(),0,Integer.MAX_VALUE));input=form;}result=MajorIncidents.command(c.db,id,input);if(!api)return redirect(path);}
            else result=MajorIncidents.page(c.db,id,c.arguments());return api?json(map("result",result),200):view(c,"major-incident",map("table","incident","page",result,"query",c.arguments().getOrDefault("q","")));
        }
        Matcher changeWindows=Pattern.compile("/(admin|api/openservice)/change-windows/([0-9a-f]{32})").matcher(path);
        if(changeWindows.matches()){
            c.admin();method(method,"GET","POST");c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");String ci=changeWindows.group(2);Map<String,Object> result;
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),0,Integer.MAX_VALUE));if(!"remove".equals(form.get("operation"))){String active=Objects.toString(form.get("active"),"");TypedFields.require(Set.of("true","false").contains(active),"Choose active or inactive");form.put("active",Boolean.parseBoolean(active));}input=form;}result=ChangeWindows.save(c.db,ci,input);if(!api)return redirect(path);}
            else result=ChangeWindows.page(c.db,ci);return api?json(map("result",result),200):view(c,"change-windows",map("table","cmdb_ci","page",result));
        }
        Matcher changeSchedule=Pattern.compile("/(record|api/openservice)/change_request/([0-9a-f]{32})/schedule").matcher(path);
        if(changeSchedule.matches()){
            method(method,"GET","POST");c.uniqueQuery();boolean api=path.startsWith("/api/");String id=changeSchedule.group(2);Map<String,Object> result;
            if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");for(String field:List.of("revision","record_revision"))form.put(field,Service.number(Objects.toString(form.get(field),""),0,Integer.MAX_VALUE));input=form;}result=ChangeScheduling.save(c.db,id,input);if(!api)return redirect(path);}
            else result=ChangeScheduling.page(c.db,id,c.arguments());return api?json(map("result",result),200):view(c,"change-schedule",map("table","change_request","page",result,"query",c.arguments().getOrDefault("q","")));
        }
        Matcher catalogueWorkflow=Pattern.compile("/(admin|api/openservice)/catalogue/([0-9a-f]{32})/workflow").matcher(path);
        if(catalogueWorkflow.matches()){
            c.admin();method(method,"GET","POST");c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");String id=catalogueWorkflow.group(2);Map<String,Object> result;
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));input=form;}result=CatalogueWorkflows.save(c.db,id,input);if(!api)return redirect(path);}
            else result=CatalogueWorkflows.settings(c.db,id);
            return api?json(map("result",result),200):view(c,"catalogue-workflow",map("table","catalogue","offering",result.get("offering"),"workflow",result.get("workflow"),"groups",RecordAccess.groups(c.db)));
        }
        Matcher requestWorkflow=Pattern.compile("/(requests|api/openservice/requests)/([0-9a-f]{32})/workflow").matcher(path);
        if(requestWorkflow.matches()){
            boolean api=path.startsWith("/api/");method(method,api?new String[]{"GET","POST"}:new String[]{"POST"});c.noQuery();c.uniqueQuery();String id=requestWorkflow.group(2);Map<String,Object> result;
            if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("record_revision",Service.number(Objects.toString(form.get("record_revision"),""),0,Integer.MAX_VALUE));input=form;}result=CatalogueWorkflows.reconcile(c.db,id,input);if(!api)return redirect("/record/sc_request/"+id);}
            else result=CatalogueWorkflows.progress(c.db,id);return json(map("result",result),200);
        }
        Matcher catalogueRouting=Pattern.compile("/(admin|api/openservice)/catalogue/([0-9a-f]{32})/routing").matcher(path);
        if(catalogueRouting.matches()){
            boolean api=path.startsWith("/api/");c.admin();method(method,api?"PUT":"POST");c.noQuery();c.uniqueQuery();String id=catalogueRouting.group(2);Object input;
            if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));input=form;}
            var result=CatalogueRouting.save(c.db,c.db.viewerId,id,input);return api?json(map("result",result),200):redirect("/admin/catalogue/"+id);
        }
        Matcher catalogueEligibility=Pattern.compile("/(admin|api/openservice)/catalogue/([0-9a-f]{32})/eligibility").matcher(path);
        if(catalogueEligibility.matches()){
            boolean api=path.startsWith("/api/");c.admin();method(method,api?"PUT":"POST");c.noQuery();c.uniqueQuery();String id=catalogueEligibility.group(2);
            Object input;
            if(api)input=body(c);
            else {var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));input=form;}
            var result=CatalogueEligibility.save(c.db,c.db.viewerId,id,input);
            return api?json(map("result",result),200):redirect("/admin/catalogue/"+id);
        }
        Matcher catalogueAdmin=Pattern.compile("/admin/catalogue(?:/([0-9a-f]{32}))?").matcher(path);
        if(catalogueAdmin.matches()) {
            c.admin();method(method,"GET","POST");c.noQuery();c.uniqueQuery();String id=Objects.toString(catalogueAdmin.group(1),"");
            if(method.equals("POST")) {
                var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");String active=Objects.toString(form.get("active"),"");TypedFields.require(Set.of("true","false").contains(active),"Choose active or inactive");form.put("active",Boolean.parseBoolean(active));form.put("revision",Service.number(Objects.toString(form.get("revision"),""),0,Integer.MAX_VALUE));
                var saved=Catalogue.save(c.db,c.db.viewerId,id,form);return redirect("/admin/catalogue/"+saved.get("offering_id"));
            }
            var offering=id.isEmpty()?map("offering_id","","name","","description","","active",0,"revision",0,"group_id","","fulfilment_group_id","","questions",List.of()):Catalogue.find(c.db,id,true);
            return view(c,"catalogue-admin",map("table","catalogue","offerings",Catalogue.list(c.db,true),"offering",offering,"groups",RecordAccess.groups(c.db),"eligibleGroupName",CatalogueEligibility.groupName(c.db,offering.get("group_id").toString()),"routingGroupName",CatalogueRouting.name(c.db,offering.get("fulfilment_group_id").toString())));
        }
        if(path.equals("/catalogue") || path.equals("/api/openservice/catalogue")) {
            boolean api=path.startsWith("/api/");method(method,api?new String[]{"GET","POST"}:new String[]{"GET"});c.noQuery();c.uniqueQuery();
            if(method.equals("POST"))return json(map("result",Catalogue.save(c.db,c.db.viewerId,"",body(c))),201);
            var rows=Catalogue.list(c.db,false);return api?json(map("result",rows),200):view(c,"catalogue",map("table","catalogue","offerings",rows));
        }
        Matcher catalogueAssist=Pattern.compile("/(?:catalogue|api/openservice/catalogue)/([0-9a-f]{32})/assistant").matcher(path);
        if(catalogueAssist.matches()){
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String id=catalogueAssist.group(1);boolean api=path.startsWith("/api/");Map<String,Object> result;
            if(method.equals("POST")){
                Object input;if(api)input=body(c);else{
                    var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");String operation=Objects.toString(form.get("operation"),"");
                    for(String flag:List.of("allow_external","confirm"))if(form.containsKey(flag)){TypedFields.require(form.get(flag).equals("true"),"Confirm the reviewed action");form.put(flag,true);}
                    if(Set.of("review","edit","submit").contains(operation)){
                        var envelope=map("operation",form.remove("operation"));for(String key:List.of("action_token","confirm"))if(form.containsKey(key))envelope.put(key,form.remove(key));
                        form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));CatalogueQuestions.form(form);form.putIfAbsent("answers",map());envelope.put("order",form);input=envelope;
                    }else input=form;
                }
                result=CatalogueAssist.submit(store,c.db,id,input);
            }else result=CatalogueAssist.context(store,c.db,id);
            if(result.get("request")!=null)return api?json(map("result",result.get("request")),201):redirect("/record/sc_request/"+object(result.get("request")).get("sys_id"));
            return api?json(map("result",result),200):view(c,"catalogue-assist",map("table","catalogue","assistance",result,"orderKey",result.get("order_key")));
        }
        Matcher catalogueItem=Pattern.compile("/(catalogue|api/openservice/catalogue)/([0-9a-f]{32})(/order)?").matcher(path);
        if(catalogueItem.matches()) {
            boolean api=path.startsWith("/api/"),order=catalogueItem.group(3)!=null;c.noQuery();c.uniqueQuery();String id=catalogueItem.group(2);
            if(order){TypedFields.require(api,"Use the offering form");method(method,"POST");}
            else method(method,api?new String[]{"GET","PUT"}:new String[]{"GET","POST"});
            if(method.equals("PUT"))return json(map("result",Catalogue.save(c.db,c.db.viewerId,id,body(c))),200);
            if(method.equals("POST")) {
                Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");form.put("revision",Service.number(Objects.toString(form.get("revision"),""),1,Integer.MAX_VALUE));input=CatalogueQuestions.form(form);}
                var item=Catalogue.order(c.db,id,input);return api?json(map("result",FieldAccess.serialize(c.db,item)),201):redirect("/record/sc_request/"+item.get("sys_id"));
            }
            var offering=Catalogue.find(c.db,id,c.role().equals("admin"));return api?json(map("result",offering),200):view(c,"catalogue-item",map("table","catalogue","offering",offering,"orderKey",id(),"eligible",CatalogueEligibility.eligible(c.db,id)));
        }

        Matcher jobAttempts=Pattern.compile("/(?:admin/jobs|api/openservice/operations/jobs)/([0-9a-f]{32})/attempts").matcher(path);
        if(jobAttempts.matches()){
            method(method,"GET");c.uniqueQuery();var history=WorkAttempts.page(c.db,c.db.viewerId,jobAttempts.group(1),c.arguments());
            return path.startsWith("/api/")?json(history,200):view(c,"work-attempts",map("table","operations","history",history));
        }
        Matcher jobReceipt=Pattern.compile("/admin/jobs/([0-9a-f]{32})").matcher(path);
        if(jobReceipt.matches()){method(method,"GET");c.noQuery();return view(c,"job-receipt",map("table","operations","job",WorkJobs.detail(store,c.db,c.db.viewerId,jobReceipt.group(1))));}
        if(path.equals("/admin/jobs") || path.equals("/api/openservice/job-search")) {
            boolean api=path.startsWith("/api/");method(method,api?new String[]{"GET"}:new String[]{"GET","POST"});c.admin();c.uniqueQuery();
            var args=new LinkedHashMap<String,String>(c.arguments());
            if(method.equals("POST")){c.noQuery();args.remove("csrf");args.remove("job_id");args.remove("operation");TypedFields.require(Set.of("retry","cancel").contains(c.parameter("operation","")),"Choose retry or cancel");WorkSearch.page(c.db,c.db.viewerId,args);WorkJobs.control(c.db,c.db.viewerId,c.parameter("job_id",""),c.parameter("operation",""));}
            var result=WorkSearch.page(c.db,c.db.viewerId,args);
            return api?json(result,200):view(c,"work-search",map("table","operations","search",result));
        }
        if(Set.of("/admin/diagnostics","/admin/diagnostics/export","/api/openservice/diagnostics").contains(path)){
            method(method,"GET");c.admin();c.noQuery();var result=OperatorDiagnostics.inspect(store,c.db);if(path.endsWith("/export")){disposition(c,"openservice-diagnostics.json");return binary(JSON.writeValueAsBytes(result),"application/json",200);}return path.startsWith("/api/")?json(map("result",result),200):view(c,"operator-diagnostics",map("table","operations","diagnostics",result));
        }
        Matcher migrationRows=Pattern.compile("/(?:admin|api/openservice)/migrations/([0-9a-f]{32})/rows(?:/([a-z][a-z0-9_]{0,79})/([0-9a-f]{32}))?").matcher(path);
        if(migrationRows.matches()){
            method(method,"GET");c.admin();c.uniqueQuery();boolean detail=migrationRows.group(2)!=null,api=path.startsWith("/api/");if(detail)c.noQuery();var result=detail?MigrationRows.inspect(c.db,migrationRows.group(1),migrationRows.group(2),migrationRows.group(3)):MigrationRows.page(c.db,migrationRows.group(1),c.arguments());return api?json(map("result",result),200):view(c,detail?"migration-row":"migration-rows",map("table","migration","inspection",result));
        }
        Matcher measurements=Pattern.compile("/(?:reports|api/openservice/reports)/([0-9a-f]{32})/measurements").matcher(path);
        if(measurements.matches()){
            method(method,"GET","POST");c.admin();c.uniqueQuery();boolean api=path.startsWith("/api/");String id=measurements.group(1);
            if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}var result=ReportMeasurements.capture(c.db,id,input);return api?json(map("result",result),201):redirect("/reports/"+id+"/measurements");}
            var result=ReportMeasurements.page(c.db,id,c.arguments());return api?json(map("result",result),200):view(c,"report-measurements",map("table","reports","measurements",result,"captureKey",id()));
        }
        Matcher reportCopy=Pattern.compile("/(?:reports|api/openservice/reports)/([0-9a-f]{32})/copy").matcher(path);
        if(reportCopy.matches()) {
            method(method,"POST");c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");
            var input=api?body(c):new LinkedHashMap<String,Object>(c.arguments());if(!api)object(input).remove("csrf");
            var result=SavedReports.copy(c.db,c.db.viewerId,reportCopy.group(1),input);
            return api?json(map("result",result),201):redirect("/reports/"+result.get("report_id"));
        }
        Matcher scheduledReminder=Pattern.compile("/(admin|api/openservice)/approvals/([0-9a-f]{32})/reminder-jobs").matcher(path);
        if(scheduledReminder.matches()){
            c.admin();method(method,"POST");c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");Object input;
            if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");input=form;}
            var result=ScheduledApprovalReminders.schedule(store,c.db,c.db.viewerId,scheduledReminder.group(2),input);
            return api?json(map("result",result),201):redirect("/admin/jobs/"+result.get("job_id"));
        }
        Matcher reviewDiagnostic=Pattern.compile("/admin/approvals/([0-9a-f]{32})/diagnostics").matcher(path);
        if(reviewDiagnostic.matches()) {
            method(method,"GET","POST");c.admin();c.noQuery();c.uniqueQuery();String id=reviewDiagnostic.group(1);
            var result=ApprovalDiagnostics.inspect(c.db,c.db.viewerId,id);Map<String,Object> reminder=null;
            if(method.equals("POST")){TypedFields.require(Set.of("csrf").containsAll(c.arguments().keySet()),"Reminder accepts no extra fields");reminder=ApprovalReminders.send(c.db,c.db.viewerId,id,map());}
            return view(c,"approval-diagnostics",map("table",result.get("table_name"),"diagnostic",result,"reminder",reminder,"diagnosticPath",path,"reminderKey",id(),"reminderTime",java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),"workEnabled",store.environment.getProperty("WORK_ENABLED",Boolean.class,false),
                "reasons",Map.of("proposal_changed","The record or configuration changed. Request a fresh review.","reviewer_unavailable","The assigned reviewer is inactive, must replace their password or no longer has a staff role.","reviewer_credentials_changed","The reviewer’s authority changed after approval. Request a fresh decision.","reviewer_permission_changed","The reviewer no longer has the required record or transition access.","awaiting_decision","The current reviewer is eligible and has not recorded a decision.","review_closed","This review is closed without a qualifying approval.","same_as_actor","Your own approval cannot authorize your transition.","qualifying","This approval currently qualifies for your approval count. Other transition rules still apply.")));
        }
        Matcher assetRelationships=Pattern.compile("/(?:record/cmdb_ci|api/openservice/assets)/([0-9a-f]{32})/relationships(/choose)?").matcher(path);
        if(assetRelationships.matches()){
            method(method,"GET","POST");c.uniqueQuery();boolean api=path.startsWith("/api/"),choosing=assetRelationships.group(2)!=null;String id=assetRelationships.group(1);Map<String,Object> result;
            if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");TypedFields.require(Set.of("true","false").contains(form.getOrDefault("remove","")),"Remove must be true or false");form.put("remove",Boolean.parseBoolean(form.get("remove").toString()));input=form;}AssetRelationships.change(c.db,id,input);String direction=object(input).get("direction").toString();if(!api)return redirect("/record/cmdb_ci/"+id+"/relationships?direction="+direction);result=AssetRelationships.page(c.db,id,Map.of("direction",direction),false);}
            else result=AssetRelationships.page(c.db,id,c.arguments(),choosing);
            return api?json(map("result",result),200):view(c,choosing?"asset-relationship-choose":"asset-relationships",map("table","cmdb_ci","relations",result));
        }
        Matcher assetImpact=Pattern.compile("/record/cmdb_ci/([0-9a-f]{32})/impact").matcher(path);
        if(assetImpact.matches()) {
            method(method,"GET");c.noQuery();String id=assetImpact.group(1);var result=AssetOperations.impact(c.db,id);
            return view(c,"asset-impact",map("table","cmdb_ci","impact",result,"source",FieldAccess.serialize(c.db,Service.record(c.db,"cmdb_ci",id))));
        }
        if(path.equals("/admin/calendars/preview") || path.equals("/api/openservice/calendar-preview")) {
            method(method,"GET");c.admin();c.uniqueQuery();boolean api=path.startsWith("/api/");
            Object result=api || !c.arguments().isEmpty()?BusinessDeadline.preview(c.db,c.db.viewerId,c.arguments()):null;
            return api?json(map("result",result),200):view(c,"calendar-preview",map("table","operations","preview",result,"selection",c.arguments(),"calendars",c.db.all("SELECT calendar_id FROM os_calendar ORDER BY calendar_id")));
        }
        Matcher questions=Pattern.compile("/record/([a-z0-9_]{1,80})/([0-9a-f]{32})/assistant").matcher(path);
        if(questions.matches()) {
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();String table=questions.group(1),id=questions.group(2);
            var result=method.equals("GET")?RecordQuestions.context(store,c.db,table,id):RecordQuestions.submit(store,c.db,table,id,c.arguments());
            return view(c,"record-assistant",map("table",table,"recordId",id,"assistant",result,"assistantPath",path));
        }
        if(path.equals("/audit/checkpoint")) {
            method(method,"GET");c.uniqueQuery();AuditGovernance.requireViewer(c.db,c.db.viewerId);
            var args=new LinkedHashMap<String,String>(c.arguments());String download=args.getOrDefault("download","false");args.remove("download");
            TypedFields.require(Set.of("true","false").contains(download),"Download must be true or false");
            boolean checking=!args.isEmpty();var result=AuditGovernance.inspect(c.db,args);
            if(download.equals("true")){TypedFields.require(!checking,"Download a current checkpoint without verification fields");disposition(c,"openservice-audit-checkpoint.json");return json(map("result",result),200);}
            return view(c,"audit-checkpoint",map("table","audit","checkpoint",checking?result.get("observed"):result,"verification",checking?result.get("verified"):null,"submitted",args));
        }
        Matcher picker=Pattern.compile("/record/([a-z0-9_]{1,80})/([0-9a-f]{32})/reference/([a-z0-9_]{1,80})").matcher(path);
        if(picker.matches()) {
            method(method,"GET","POST");c.uniqueQuery();String table=picker.group(1),id=picker.group(2),field=picker.group(3);
            if(method.equals("POST")){c.noQuery();ReferencePicker.save(c.db,table,id,field,c.arguments());return redirect("/record/"+table+"/"+id);}
            var context=ReferencePicker.context(c.db,table,id,field);
            var result=ReferenceSearch.search(c.db,context.get("target").toString(),c.arguments());
            return view(c,"reference-picker",map("table",table,"recordId",id,"field",field,"context",context,"search",result,"query",c.parameter("q",""),"pickerPath",path));
        }
        Matcher noticeTarget=Pattern.compile("/(?:account|api/openservice)/notifications/([0-9a-f]{32})/context").matcher(path);
        if(noticeTarget.matches()){
            method(method,"GET");c.noQuery();c.uniqueQuery();String target=NoticeTargets.open(c.db,noticeTarget.group(1));return path.startsWith("/api/")?json(map("result",map("url",target)),200):redirect(target);
        }
        Matcher knowledgeDraft=Pattern.compile("/(?:admin|api/openservice)/knowledge/([0-9a-f]{32})/draft").matcher(path);
        if(knowledgeDraft.matches()){
            method(method,"GET","POST");c.admin();c.uniqueQuery();boolean api=path.startsWith("/api/");String id=knowledgeDraft.group(1);Map<String,Object> result;
            if(method.equals("POST")){c.noQuery();Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");TypedFields.require("true".equals(form.get("confirm")),"Confirm the reviewed knowledge action");form.put("confirm",true);form.put("version",Service.number(Objects.toString(form.get("version"),""),0,Integer.MAX_VALUE));input=form;}result=KnowledgeDrafts.act(c.db,id,input);if(!api)return redirect(object(input).get("operation").equals("publish")?"/knowledge/"+id:"/record/kb_knowledge/"+id);}
            else result=KnowledgeDrafts.review(c.db,id,c.arguments());
            return api?json(map("result",result),200):view(c,"knowledge-draft",map("table","knowledge","review",result));
        }
        Matcher knowledgeComparison=Pattern.compile("/(?:admin/knowledge/([0-9a-f]{32})/versions|api/openservice/knowledge/([0-9a-f]{32})/compare)").matcher(path);
        if(knowledgeComparison.matches()) {
            method(method,"GET");c.admin();c.uniqueQuery();boolean api=path.startsWith("/api/");
            var result=KnowledgeComparison.compare(c.db,c.db.viewerId,knowledgeComparison.group(api?2:1),c.arguments());
            return api?json(map("result",result),200):view(c,"knowledge-comparison",map("table","knowledge","comparison",result));
        }
        Matcher migrationConflicts=Pattern.compile("/(?:admin|api/openservice)/migrations/([0-9a-f]{32})/conflicts(?:/([a-z][a-z0-9_]{0,79})/([0-9a-f]{32}))?").matcher(path);
        if(migrationConflicts.matches()){
            boolean api=path.startsWith("/api/"),detail=migrationConflicts.group(2)!=null;method(method,detail?new String[]{"GET","POST"}:new String[]{"GET"});c.admin();c.uniqueQuery();String job=migrationConflicts.group(1);Map<String,Object> result;
            if(detail){c.noQuery();String table=migrationConflicts.group(2),sourceId=migrationConflicts.group(3);if(method.equals("POST")){Object input;if(api)input=body(c);else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");TypedFields.require("true".equals(form.get("confirm")),"Confirm the reviewed conflict decision");form.put("confirm",true);form.put("revision",Service.number(Objects.toString(form.get("revision"),""),0,Integer.MAX_VALUE));input=form;}result=MigrationConflicts.save(c.db,job,table,sourceId,input);if(!api)return redirect("/admin/migrations/"+job+"/conflicts");}else result=MigrationConflicts.inspect(c.db,job,table,sourceId);}
            else result=MigrationConflicts.page(c.db,job,c.arguments());
            return api?json(map("result",result),200):view(c,detail?"migration-conflict":"migration-conflicts",map("table","migration","review",result));
        }

        Matcher reconciliation=Pattern.compile("/admin/migrations/([0-9a-f]{32})/reconciliation").matcher(path);
        if(reconciliation.matches()) {
            method(method,"GET");c.admin();c.uniqueQuery();
            TypedFields.require(Set.of("download").containsAll(c.arguments().keySet()),"Use download only");
            String download=c.parameter("download","false");TypedFields.require(Set.of("true","false").contains(download),"Download must be true or false");
            var result=TransferReconciliation.inspect(c.db,c.db.viewerId,reconciliation.group(1));
            if(download.equals("true")){disposition(c,"migration-reconciliation-"+reconciliation.group(1)+".json");return json(map("result",result),200);}
            return view(c,"reconciliation",map("table","migration","checks",result));
        }
        if(path.equals("/account/profile") || path.equals("/api/openservice/me/profile")) {
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();
            boolean api=path.startsWith("/api/");String actor=c.user.get("user_id").toString();
            if(method.equals("POST")) {
                var input=api?body(c):new LinkedHashMap<String,Object>(c.arguments());
                if(!api)object(input).remove("csrf");
                Profiles.save(c.db,actor,input);
                if(!api)return redirect("/account/profile");
            }
            var profile=Profiles.read(c.db,actor);
            return api?json(map("result",profile),200):view(c,"profile",map("table","profile","profile",profile));
        }
        if(path.equals("/reports")) {
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();
            String actor=c.user.get("user_id").toString();
            if(method.equals("POST")) return redirect("/reports/"+ReportForms.save(c.db,actor,"",c.arguments()));
            return view(c,"reports",map("table","reports","reports",SavedReports.list(c.db,actor),"canManage",Set.of("admin","agent").contains(c.role()),
                    "reportDef",map("name","","table_name","incident","shared",0),"query",Map.of()));
        }
        Matcher savedReport=Pattern.compile("/reports/([0-9a-f]{32})").matcher(path);
        if(savedReport.matches()) {
            method(method,"GET","POST");c.noQuery();c.uniqueQuery();
            String actor=c.user.get("user_id").toString(),id=savedReport.group(1);
            if(method.equals("POST")) {
                String operation=c.parameter("operation","");
                TypedFields.require(Set.of("save","delete").contains(operation),"Choose save or delete");
                if(operation.equals("delete")){SavedReports.remove(c.db,actor,id);return redirect("/reports");}
                ReportForms.save(c.db,actor,id,c.arguments());return redirect("/reports/"+id);
            }
            var definition=SavedReports.find(c.db,actor,id);
            boolean canManage=actor.equals(definition.get("owner_id")) && Set.of("admin","agent").contains(c.role());
            Map<String,Object> metrics=null;String reportError=null;
            try {metrics=SavedReports.run(c.db,actor,id);}
            catch(Failure unavailable){if(!canManage)throw unavailable;reportError=unavailable.getMessage();}
            return view(c,"saved-report",map("table","reports","reportDef",definition,"query",definition.get("definition"),
                    "canManage",canManage,"reportError",reportError,
                    "report",metrics,"history",ReportRuns.history(c.db,actor,id)));
        }
        if(path.equals("/admin/events") || path.equals("/api/openservice/event-search")) {
            boolean api=path.startsWith("/api/");
            method(method,api?new String[]{"GET"}:new String[]{"GET","POST"});
            c.admin(); c.uniqueQuery();
            String actor=c.user.get("user_id").toString();
            if(method.equals("POST")) {
                c.noQuery();
                TypedFields.require(Set.of("csrf","key","operation","reason").containsAll(c.arguments().keySet()),"Provide only the event response fields");
                EventActions.apply(c.db,actor,map("key",c.parameter("key",""),"operation",c.parameter("operation",""),"reason",c.parameter("reason","")));
                return redirect("/admin/events");
            }
            var result=EventSearch.page(c.db,actor,c.arguments());
            return api?json(result,200):view(c,"events",map("table","events","search",result));
        }
        if (path.equals("/admin/operations")) {
            method(method, "GET", "POST");
            c.admin();
            c.noQuery();
            String actor = c.user.get("user_id").toString();
            if (method.equals("POST")) {
                Operations.form(store, c.db, actor, c.http.getParameterMap());
                return redirect("/admin/operations");
            }
            return view(c, "operations", Operations.dashboard(store, c.db, actor));
        }
        if (path.equals("/account/notifications")
                || path.equals("/api/openservice/me/notifications")) {
            method(method, "GET", "POST");
            if(method.equals("POST")) c.noQuery(); else c.uniqueQuery();
            String actor = c.user.get("user_id").toString();
            if (method.equals("POST"))
                Notifications.update(
                        c.db,
                        actor,
                        path.startsWith("/api/")
                                ? body(c)
                                : c.parameter("operation", "").equals("preference")
                                        ? map("enabled", c.parameter("enabled", "").equals("true"))
                                        : c.parameter("operation", "").equals("batch")
                                                ? map("acknowledge_many",Arrays.asList(c.parameter("notice_ids", "").split(",",-1)))
                                                : map("acknowledge", c.parameter("notice_id", "")));
            Map<String,String> selection=method.equals("GET")?c.arguments():path.startsWith("/api/")?Map.of():Map.of("q",c.parameter("return_q",""),"state",c.parameter("return_state","all"),"offset",c.parameter("return_offset","0"),"limit",c.parameter("return_limit","100"));
            var result=NotificationSearch.page(c.db,actor,selection);
            var rows=objects(result.get("result"));
            return path.startsWith("/api/")
                    ? json(result, 200)
                    : view(
                            c,
                            "notifications",
                            map(
                                    "table",
                                    "notifications",
                                    "search",result,
                                    "notices",
                                    rows,
                                    "enabled",
                                    ((Number)
                                                            c.db.one(
                                                                            "SELECT notifications"
                                                                                + " FROM os_user"
                                                                                + " WHERE"
                                                                                + " user_id=?",
                                                                            actor)
                                                                    .get("notifications"))
                                                    .intValue()
                                            == 1));
        }
        if (path.equals("/knowledge") || path.equals("/api/openservice/knowledge")) {
            method(method, "GET");
            c.uniqueQuery();
            var result = KnowledgeSearch.page(c.db,c.arguments());
            return path.startsWith("/api/")
                    ? json(result, 200)
                    : view(c, "knowledge", map("table", "knowledge", "articles", result.get("result"),"search",result));
        }
        Matcher platform =
                Pattern.compile(
                                "/api/openservice/operations/(jobs|connectors|calendars|clocks|reports|knowledge|dependencies|events|references|qualification|provisioning|audit-access|audit-checkpoint|password-policy|assist|approval-reminders|assist-preview|preflight|event-actions|report-history|approval-diagnostics|reference-search|queue-health)(?:/([a-z0-9_-]{1,80}))?")
                        .matcher(path);
        if (platform.matches()) {
            method(method, "GET", "POST", "DELETE");
            boolean historyQuery=platform.group(1).equals("knowledge") && method.equals("GET");
            boolean clockQuery=platform.group(1).equals("clocks") && platform.group(2)!=null && method.equals("GET");
            boolean auditQuery=platform.group(1).equals("audit-checkpoint") && method.equals("GET");
            boolean referenceQuery=platform.group(1).equals("reference-search") && method.equals("GET");
            if(!historyQuery && !clockQuery && !auditQuery && !referenceQuery) c.noQuery();
            String actor = c.user.get("user_id").toString();
            return json(
                    map(
                            "result",
                            Operations.execute(
                                    store,
                                    c.db,
                                    actor,
                                    platform.group(1),
                                    Objects.toString(platform.group(2), ""),
                                    method,
                                    method.equals("GET") ? c.arguments() : body(c))),
                    200);
        }
        if (path.equals("/") && c.role().equals("requester")) return redirect("/requests");
        if (Set.of("/requests", "/api/openservice/requests").contains(path)) {
            method(method, "GET", "POST");
            c.noQuery();
            if (method.equals("POST")) {
                var item =
                        Requests.create(
                                c.db,
                                c.name(),
                                path.startsWith("/api/")
                                        ? body(c)
                                        : map(
                                                "short_description",
                                                c.parameter("short_description", ""),
                                                "description",
                                                c.parameter("description", "")));
                return path.startsWith("/api/")
                        ? json(map("result", FieldAccess.serialize(c.db, item)), 201)
                        : redirect("/record/sc_request/" + item.get("sys_id"));
            }
            var rows = Service.page(c.db, "sc_request", Map.of("sysparm_limit", "100"), "").rows();
            return path.startsWith("/api/")
                    ? json(map("result", rows), 200)
                    : view(c, "requests", map("table", "sc_request", "rows", rows));
        }
        if (path.equals("/forgot")) {
            method(method, "GET", "POST");
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                String email = c.parameter("email", "");
                if (email.length() > 254) throw new Failure(400, "Email address is too long");
                EmailOnboarding.forgot(store, c.db, email, c.http.getRemoteAddr());
            }
            return new ModelAndView(
                    "forgot",
                    map(
                            "csrf",
                            c.session.getAttribute("csrf"),
                            "submitted",
                            method.equals("POST")));
        }
        if (path.equals("/api/openservice/email-invitations")) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            return json(
                    map(
                            "result",
                            EmailOnboarding.invite(
                                    store, c.db, c.user.get("user_id").toString(), body(c))),
                    201);
        }
        if (Set.of("/admin/delivery", "/api/openservice/delivery").contains(path)) {
            method(method, "GET");
            c.admin();
            var directory = DeliveryDirectory.page(c.db, queryArguments(c));
            directory.put("diagnostics", Outbox.diagnostics(c.db, System.currentTimeMillis()));
            if (path.startsWith("/api/")) return json(directory, 200);
            directory.put("table", "delivery");
            directory.put("jobs", directory.get("result"));
            return view(c, "delivery", directory);
        }
        if (Set.of("/admin/delivery/retention", "/api/openservice/delivery/retention")
                .contains(path)) {
            method(method, "GET", "POST");
            c.admin();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/"), apply = method.equals("POST");
            Map<String, Object> values;
            if (apply) {
                c.noQuery();
                values =
                        api
                                ? object(body(c))
                                : map(
                                        "days",
                                        c.parameter("days", ""),
                                        "confirm",
                                        c.parameter("confirm", ""));
            } else {
                var arguments = queryArguments(c);
                if (!Set.of("days").containsAll(arguments.keySet()))
                    throw new Failure(400, "Unsupported retention parameter");
                values = map("days", arguments.getOrDefault("days", "90"));
            }
            var result =
                    DeliveryRetention.run(
                            c.db,
                            c.user.get("user_id").toString(),
                            values,
                            apply,
                            System.currentTimeMillis());
            return api
                    ? json(map("result", result), 200)
                    : view(c, "delivery_retention", map("table", "delivery", "retention", result));
        }
        Matcher delivery =
                Pattern.compile("/(?:admin|api/openservice)/delivery/([0-9a-f]{32})/(retry|cancel)")
                        .matcher(path);
        if (delivery.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            boolean cancel = delivery.group(2).equals("cancel");
            if (cancel) Outbox.cancel(c.db, c.user.get("user_id").toString(), delivery.group(1));
            else Outbox.retry(c.db, c.user.get("user_id").toString(), delivery.group(1));
            return path.startsWith("/api/")
                    ? json(map("result", cancel ? "cancelled" : "queued"), 200)
                    : redirect("/admin/delivery");
        }
        if (path.equals("/healthz")) {
            method(method, "GET");
            c.db.count("SELECT 1");
            return json(map("status", "ok"), 200);
        }
        if (path.equals("/static/migrations.js")) {
            method(method, "GET");
            try (var input = WebController.class.getResourceAsStream(path)) {
                return binary(
                        Objects.requireNonNull(input).readAllBytes(),
                        "text/javascript; charset=utf-8",
                        200);
            }
        }
        if (path.equals("/admin/migrations")) {
            method(method, "GET");
            c.admin();
            c.noQuery();
            return view(c, "transfers", map("table", "migration"));
        }
        if (path.equals("/api/openservice/migrations")) {
            c.admin();
            c.noQuery();
            method(method, "GET", "POST");
            return json(
                    map(
                            "result",
                            method.equals("GET")
                                    ? TransferJobs.list(c.db)
                                    : TransferJobs.create(
                                            c.db, body(c), c.db.viewerId, store.migrationOrigins)),
                    method.equals("GET") ? 200 : 201);
        }
        Matcher transfer =
                Pattern.compile(
                                "/api/openservice/migrations/([a-f0-9]{32})(?:/(step|preview|apply|sync|cutover|archive|reconciliation))?")
                        .matcher(path);
        if (transfer.matches()) {
            c.admin();
            String job = transfer.group(1), action = Objects.toString(transfer.group(2), "");
            if (action.equals("archive")) {
                method(method, "GET");
                if (!Set.of("table", "after").containsAll(c.arguments().keySet()))
                    throw new Failure(400, "Use table and after only");
                var definition = object(TransferJobs.job(c.db, job).get("definition"));
                String table = c.parameter("table", ""), cursor = c.parameter("after", "");
                if (!TransferJobs.phases(definition).contains(table)
                        || !cursor.matches("(?:[a-f0-9]{32})?"))
                    throw new Failure(400, "Choose a staged table and valid cursor");
                return json(
                        map(
                                "result",
                                c.db.all(
                                        "SELECT source_id,data FROM os_transfer_item WHERE job_id=?"
                                                + " AND source_table=? AND source_id>? ORDER BY"
                                                + " source_id LIMIT 100",
                                        job,
                                        table,
                                        cursor)),
                        200);
            }
            c.noQuery();
            if (action.isEmpty()) {
                method(method, "GET");
                return json(map("result", TransferJobs.describe(c.db, job)), 200);
            }
            if(action.equals("reconciliation")) {
                method(method,"GET");
                return json(map("result",TransferReconciliation.inspect(c.db,c.db.viewerId,job)),200);
            }
            method(method, "POST");
            Object input = body(c);
            if (!action.equals("cutover") && !object(input).isEmpty())
                throw new Failure(400, "This action takes an empty object");
            Object result =
                    switch (action) {
                        case "step" ->
                                TransferJobs.step(
                                        c.db,
                                        job,
                                        Objects.toString(c.http.getHeader("X-Source-Token"), ""),
                                        store.migrationOrigins);
                        case "preview" -> TransferApply.apply(c.db, job, c.db.viewerId, true);
                        case "apply" -> TransferApply.apply(c.db, job, c.db.viewerId, false);
                        case "sync" ->
                                TransferJobs.synchronize(
                                        c.db, job, c.db.viewerId, store.migrationOrigins);
                        default -> TransferJobs.cutover(c.db, job, input, c.db.viewerId);
                    };
            return json(map("result", result), 200);
        }
        if (Set.of("/account/email", "/api/openservice/me/email").contains(path)) {
            boolean api = path.startsWith("/api/");
            method(
                    method,
                    api ? new String[] {"GET", "POST", "DELETE"} : new String[] {"GET", "POST"});
            c.noQuery();
            c.uniqueQuery();
            String actor = c.user.get("user_id").toString();
            Map<String, Object> issued = null;
            if (!method.equals("GET")) {
                boolean cancel =
                        method.equals("DELETE")
                                || !api && c.parameter("operation", "").equals("cancel");
                if (cancel) {
                    if (api && !object(body(c)).isEmpty())
                        throw new Failure(400, "Provide an empty JSON object");
                    EmailEnrollment.cancel(c.db, actor);
                } else
                    issued =
                            EmailEnrollment.request(
                                    store,
                                    c.db,
                                    c.user,
                                    api
                                            ? body(c)
                                            : map(
                                                    "email",
                                                    c.parameter("email", ""),
                                                    "current_password",
                                                    c.parameter("current_password", "")));
            }
            var status = EmailEnrollment.status(c.db, actor);
            return api
                    ? json(map("result", status, "issued", issued), issued == null ? 200 : 202)
                    : view(
                            c,
                            "email_enrollment",
                            map("table", "account", "enrollment", status, "issued", issued));
        }
        Matcher emailVerification =
                Pattern.compile("/verify-email/([A-Za-z0-9_-]{32})").matcher(path);
        if (emailVerification.matches()) {
            method(method, "GET", "POST");
            c.noQuery();
            c.uniqueQuery();
            var enrollment = EmailEnrollment.describe(c.db, emailVerification.group(1));
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                EmailEnrollment.confirm(c.db, emailVerification.group(1));
                c.session.invalidate();
                return redirect("/login");
            }
            return new ModelAndView(
                    "verify_email",
                    map("enrollment", enrollment, "csrf", c.session.getAttribute("csrf")));
        }
        Matcher recovering = Pattern.compile("/recover/([A-Za-z0-9_-]{32})").matcher(path);
        if (recovering.matches()) {
            method(method, "GET", "POST");
            c.noQuery();
            c.uniqueQuery();
            var recovery = RecoveryLinks.describe(c.db, recovering.group(1));
            String error = null;
            int status = 200;
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                try {
                    RecoveryLinks.recover(c.db, recovering.group(1), c.parameter("password", ""));
                    c.session.invalidate();
                    return redirect("/login");
                } catch (Failure failure) {
                    error = failure.getMessage();
                    status = failure.status;
                }
            }
            return new ModelAndView(
                    "recover",
                    map(
                            "recovery",
                            recovery,
                            "csrf",
                            c.session.getAttribute("csrf"),
                            "error",
                            error),
                    HttpStatus.valueOf(status));
        }
        Matcher joining = Pattern.compile("/join/([A-Za-z0-9_-]{32})").matcher(path);
        if (joining.matches()) {
            method(method, "GET", "POST");
            c.noQuery();
            c.uniqueQuery();
            var invitation = Invitations.describe(c.db, joining.group(1));
            String error = null;
            int status = 200;
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                try {
                    Invitations.accept(c.db, joining.group(1), c.parameter("password", ""));
                    c.session.invalidate();
                    return redirect("/login");
                } catch (Failure failure) {
                    error = failure.getMessage();
                    status = failure.status;
                }
            }
            return new ModelAndView(
                    "join",
                    map(
                            "invitation",
                            invitation,
                            "csrf",
                            c.session.getAttribute("csrf"),
                            "error",
                            error),
                    HttpStatus.valueOf(status));
        }
        if (path.equals("/login")) {
            method(method, "GET", "POST");
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                var user =
                        Authentication.verify(
                                c.db,
                                c.parameter("username", ""),
                                c.parameter("password", ""),
                                "browser");
                if (user == null) throw new Failure(401, "Incorrect username or password");
                if (c.db.postgres) c.db.all("SELECT pg_advisory_xact_lock(18439,4)");
                Authentication.requireCurrent(c.db, user);
                BrowserSessions.remove(c.db, c.session.getId());
                c.http.changeSessionId();
                Collections.list(c.session.getAttributeNames()).forEach(c.session::removeAttribute);
                c.session.setAttribute("user", user.get("user_id"));
                c.session.setAttribute(
                        "auth_revision", ((Number) user.get("auth_revision")).longValue());
                long issued = System.currentTimeMillis();
                BrowserSessions.register(c.db, user, c.session.getId(), issued);
                SessionLimit.enforce(
                        c.db,
                        user,
                        c.session.getId(),
                        AccountSessionLimit.effective(
                                c.db, user.get("user_id").toString(), store.maxBrowserSessions));
                c.session.setAttribute("authenticated_at", issued);
                c.session.setAttribute("csrf", csrf());
                return redirect(PasswordReset.required(user) ? "/password-required" : "/");
            }
            return new ModelAndView("login", map("csrf", c.session.getAttribute("csrf")));
        }
        if (path.equals("/logout")) {
            method(method, "POST");
            BrowserSessions.remove(c.db, c.session.getId());
            c.session.invalidate();
            return redirect("/login");
        }
        if (path.equals("/password-required")) {
            method(method, "GET", "POST");
            c.noQuery();
            c.uniqueQuery();
            if (!PasswordReset.required(c.user)) return redirect("/account");
            String error = null;
            int status = 200;
            if (method.equals("POST")) {
                try {
                    Service.changePassword(
                            c.db,
                            c.user,
                            map(
                                    "current_password",
                                    c.parameter("current_password", ""),
                                    "new_password",
                                    c.parameter("new_password", "")));
                    c.session.invalidate();
                    return redirect("/login");
                } catch (Failure failure) {
                    error = failure.getMessage();
                    status = failure.status;
                    if (status == 429) c.response.setHeader("Retry-After", "300");
                }
            }
            return new ModelAndView(
                    "password-required",
                    map("csrf", c.session.getAttribute("csrf"), "error", error),
                    HttpStatus.valueOf(status));
        }
        Matcher permissionComparison =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/permission-comparison")
                        .matcher(path);
        if (permissionComparison.matches()) {
            method(method, "GET");
            c.admin();
            boolean api = path.startsWith("/api/");
            String target=permissionComparison.group(1);var args=queryArguments(c);Map<String,Object> comparison=map();
            if (api || !args.isEmpty())
                comparison =
                        PermissionComparison.inspect(
                                c.db, c.user.get("user_id").toString(), target, args);
            if(api)return json(map("result",comparison),200);
            return view(
                    c,
                    "permission-comparison",
                    map(
                            "table",
                            "accounts",
                            "account",
                            Service.account(c.db, target),
                            "comparison",
                            comparison,
                            "selectedTable",
                            args.getOrDefault("table", ""),
                            "selectedRecord",
                            args.getOrDefault("record_id", ""),
                            "comparisonName",
                            args.getOrDefault("with", "")));
        }
        Matcher creationPreview=Pattern.compile("/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/creation-preview").matcher(path);
        if(creationPreview.matches()){
            c.admin();c.noQuery();c.uniqueQuery();boolean api=path.startsWith("/api/");method(method,api?new String[]{"POST"}:new String[]{"GET","POST"});String target=creationPreview.group(1);
            Map<String,Object> inspection=map();String kind="record",table="incident",document="{\"short_description\":\"Review this work\"}";
            if(method.equals("POST")){
                Object input;
                if(api)input=body(c);
                else{var form=new LinkedHashMap<String,Object>(c.arguments());form.remove("csrf");TypedFields.require(form.keySet().equals(Set.of("kind","table","values")),"Provide kind, table and values");kind=Objects.toString(form.get("kind"),"");table=Objects.toString(form.get("table"),"");document=Objects.toString(form.get("values"),"");input=map("kind",kind,"table",table,"values",CreationPreview.document(document));}
                inspection=CreationPreview.inspect(c.db,c.db.viewerId,target,input);
            }
            return api?json(map("result",inspection),200):view(c,"creation-preview",map("table","accounts","account",Service.account(c.db,target),"inspection",inspection,"kind",kind,"selectedTable",table,"document",document));
        }
        Matcher accountPermissions =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/permissions")
                        .matcher(path);
        if (accountPermissions.matches()) {
            method(method, "GET"); c.admin(); c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            String target = accountPermissions.group(1);
            var args = queryArguments(c);
            Map<String,Object> inspection = map();
            if (api || !args.isEmpty())
                inspection =
                        AccountPermissions.inspect(
                                c.db, c.user.get("user_id").toString(), target, args);
            if (api) return json(map("result", inspection), 200);
            return view(
                    c,
                    "account-permissions",
                    map(
                            "table",
                            "accounts",
                            "account",
                            Service.account(c.db, target),
                            "inspection",
                            inspection,
                            "selectedTable",
                            args.getOrDefault("table", ""),
                            "selectedRecord",
                            args.getOrDefault("record_id", "")));
        }
        Matcher apiKeyLabel =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/label")
                        .matcher(path);
        if (apiKeyLabel.matches()) {
            method(method,"POST"); c.admin(); c.noQuery(); boolean api=path.startsWith("/api/");
            var result =
                    ApiKeyLabels.rename(
                            c.db,
                            c.user.get("user_id").toString(),
                            apiKeyLabel.group(1),
                            api
                                    ? body(c)
                                    : map(
                                            "key_id",
                                            c.parameter("key_id", ""),
                                            "label",
                                            c.parameter("label", "")));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + apiKeyLabel.group(1) + "/api-keys");
        }
        Matcher apiKeyReadOnly =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/read-only")
                        .matcher(path);
        if (apiKeyReadOnly.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            boolean api = path.startsWith("/api/");
            var result =
                    ApiKeyReadOnly.reduce(
                            c.db,
                            c.user.get("user_id").toString(),
                            apiKeyReadOnly.group(1),
                            api
                                    ? body(c)
                                    : map(
                                            "key_id",
                                            c.parameter("key_id", ""),
                                            "reason",
                                            c.parameter("reason", "")));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + apiKeyReadOnly.group(1) + "/api-keys");
        }
        Matcher apiKeyExpiry =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/expiry")
                        .matcher(path);
        if (apiKeyExpiry.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            boolean api = path.startsWith("/api/");
            var result =
                    ApiKeyExpiry.shorten(
                            c.db,
                            c.user.get("user_id").toString(),
                            apiKeyExpiry.group(1),
                            api
                                    ? body(c)
                                    : map(
                                            "key_id",
                                            c.parameter("key_id", ""),
                                            "hours",
                                            ApiKeyExpiry.hours(c.parameter("hours", ""))));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + apiKeyExpiry.group(1) + "/api-keys");
        }
        Matcher apiKeyCleanup =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/cleanup")
                        .matcher(path);
        if (apiKeyCleanup.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            boolean api = path.startsWith("/api/");
            var result =
                    ApiKeyCleanup.expired(
                            c.db,
                            c.user.get("user_id").toString(),
                            apiKeyCleanup.group(1),
                            api ? body(c) : map("reason", c.parameter("reason", "")));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + apiKeyCleanup.group(1) + "/api-keys");
        }
        Matcher apiKeyWrite =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/write")
                        .matcher(path);
        if (apiKeyWrite.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            String target = apiKeyWrite.group(1), actor = c.user.get("user_id").toString();
            boolean api = path.startsWith("/api/");
            var issued =
                    ApiKeyWrites.issue(
                            c.db,
                            actor,
                            target,
                            api
                                    ? body(c)
                                    : map(
                                            "label",
                                            c.parameter("label", ""),
                                            "table",
                                            c.parameter("table", ""),
                                            "days",
                                            ApiKeys.days(c.parameter("days", ""))));
            if (api) return json(map("result", issued), 201);
            return view(
                    c,
                    "api-keys",
                    map(
                            "table",
                            "accounts",
                            "account",
                            Service.account(c.db, target),
                            "keys",
                            ApiKeys.list(c.db, actor, target),
                            "issued",
                            issued));
        }
        Matcher apiKeyOnly =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/key-only")
                        .matcher(path);
        if (apiKeyOnly.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            String target = apiKeyOnly.group(1), actor = c.user.get("user_id").toString();
            boolean api = path.startsWith("/api/");
            var issued =
                    ApiKeyOnly.replace(
                            c.db,
                            actor,
                            target,
                            api
                                    ? body(c)
                                    : map(
                                            "label",
                                            c.parameter("label", ""),
                                            "table",
                                            c.parameter("table", ""),
                                            "days",
                                            ApiKeys.days(c.parameter("days", ""))));
            if (api) return json(map("result", issued), 201);
            return view(
                    c,
                    "api-keys",
                    map(
                            "table",
                            "accounts",
                            "account",
                            Service.account(c.db, target),
                            "keys",
                            ApiKeys.list(c.db, actor, target),
                            "issued",
                            issued));
        }
        Matcher apiKeysRevoke =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/revoke")
                        .matcher(path);
        if (apiKeysRevoke.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            boolean api = path.startsWith("/api/");
            var result =
                    ApiKeyRevocation.all(
                            c.db,
                            c.user.get("user_id").toString(),
                            apiKeysRevoke.group(1),
                            api ? body(c) : map("reason", c.parameter("reason", "")));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + apiKeysRevoke.group(1) + "/api-keys");
        }
        Matcher apiKeyState =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/([0-9a-f]{32})/state")
                        .matcher(path);
        if (apiKeyState.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            boolean api = path.startsWith("/api/");
            var result =
                    ApiKeyState.change(
                            c.db,
                            c.user.get("user_id").toString(),
                            apiKeyState.group(1),
                            apiKeyState.group(2),
                            api
                                    ? body(c)
                                    : map("enabled", ApiKeyState.form(c.parameter("enabled", ""))));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + apiKeyState.group(1) + "/api-keys");
        }
        Matcher apiKeyRotate =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/([0-9a-f]{32})/rotate")
                        .matcher(path);
        if (apiKeyRotate.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            String target = apiKeyRotate.group(1), actor = c.user.get("user_id").toString();
            var issued = ApiKeyRotation.rotate(c.db, actor, target, apiKeyRotate.group(2));
            if (path.startsWith("/api/")) return json(map("result", issued), 200);
            return view(
                    c,
                    "api-keys",
                    map(
                            "table",
                            "accounts",
                            "account",
                            Service.account(c.db, target),
                            "keys",
                            ApiKeys.list(c.db, actor, target),
                            "issued",
                            issued));
        }
        Matcher apiKeyRevoke =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys/([0-9a-f]{32})/revoke")
                        .matcher(path);
        if (apiKeyRevoke.matches()) {
            method(method, "POST"); c.admin(); c.noQuery();
            var result =
                    ApiKeys.revoke(
                            c.db,
                            c.user.get("user_id").toString(),
                            apiKeyRevoke.group(1),
                            apiKeyRevoke.group(2));
            return path.startsWith("/api/") ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + apiKeyRevoke.group(1) + "/api-keys");
        }
        Matcher apiKeys =
                Pattern.compile("/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/api-keys")
                        .matcher(path);
        if (apiKeys.matches()) {
            method(method, "GET", "POST"); c.admin(); c.noQuery();
            String target = apiKeys.group(1), actor = c.user.get("user_id").toString();
            boolean api = path.startsWith("/api/");
            Map<String, Object> issued = map();
            if (method.equals("POST"))
                issued =
                        ApiKeys.issue(
                                c.db,
                                actor,
                                target,
                                api
                                        ? body(c)
                                        : map(
                                                "label",
                                                c.parameter("label", ""),
                                                "table",
                                                c.parameter("table", ""),
                                                "days",
                                                ApiKeys.days(c.parameter("days", ""))));
            if (api)
                return json(
                        map(
                                "result",
                                method.equals("POST") ? issued : ApiKeys.list(c.db, actor, target)),
                        method.equals("POST") ? 201 : 200);
            return view(
                    c,
                    "api-keys",
                    map(
                            "table",
                            "accounts",
                            "account",
                            Service.account(c.db, target),
                            "keys",
                            ApiKeys.list(c.db, actor, target),
                            "issued",
                            issued));
        }
        if (path.equals("/api/openservice/me/csrf")) {
            method(method, "GET");
            c.noQuery();
            return json(map("result", map("csrf", c.session.getAttribute("csrf"))), 200);
        }
        Matcher accountSessionLimit =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/session-limit")
                        .matcher(path);
        if (accountSessionLimit.matches()) {
            boolean api = path.startsWith("/api/");
            method(method, api ? new String[]{"GET", "POST"} : new String[]{"POST"});
            c.admin(); c.noQuery(); c.uniqueQuery();
            String target = accountSessionLimit.group(1);
            String actor = c.user.get("user_id").toString();
            Service.requireAccountAdministrator(c.db, actor);
            if (method.equals("GET"))
                return json(
                        map(
                                "result",
                                AccountSessionLimit.state(c.db, target, store.maxBrowserSessions)),
                        200);
            var result =
                    AccountSessionLimit.change(
                            c.db,
                            actor,
                            target,
                            api
                                    ? body(c)
                                    : map(
                                            "maximum",
                                            AccountSessionLimit.form(c.parameter("maximum", ""))),
                            store.maxBrowserSessions);
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + target + "/sessions");
        }
        Matcher adminAllSessions =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/sessions/revoke")
                        .matcher(path);
        if (adminAllSessions.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            String target = adminAllSessions.group(1);
            var result = AdminSessions.revokeAll(c.db, c.user.get("user_id").toString(), target,
                    c.session.getId(), api ? body(c) : map("reason", c.parameter("reason", "")));
            boolean current = result.get("current").equals(true);
            if (current) c.session.invalidate();
            return api ? json(map("result", result), 200)
                    : redirect(current ? "/login" : "/admin/accounts/" + target + "/sessions");
        }
        Matcher adminSessionRevoke =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/sessions/([0-9a-f]{32})/revoke")
                        .matcher(path);
        if (adminSessionRevoke.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            String target = adminSessionRevoke.group(1);
            boolean current = AdminSessions.revoke(c.db, c.user.get("user_id").toString(),
                    target, adminSessionRevoke.group(2), c.session.getId(),
                    api ? body(c) : map("reason", c.parameter("reason", "")));
            if (current) c.session.invalidate();
            return api ? json(map("result", map("revoked", true, "current", current)), 200)
                    : redirect(current ? "/login" : "/admin/accounts/" + target + "/sessions");
        }
        Matcher adminSessions =
                Pattern.compile("/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/sessions")
                        .matcher(path);
        if (adminSessions.matches()) {
            method(method, "GET");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            var inventory =
                    AdminSessions.list(
                            c.db,
                            c.user.get("user_id").toString(),
                            adminSessions.group(1),
                            c.session.getId(),
                            System.currentTimeMillis());
            return path.startsWith("/api/")
                    ? json(inventory, 200)
                    : view(
                            c,
                            "admin-sessions",
                            map(
                                    "table", "accounts",
                                    "account", inventory.get("account"),
                                    "sessions", inventory.get("result"),
                                    "truncated", inventory.get("truncated"),
                                    "sessionPolicy",
                                            AccountSessionLimit.state(
                                                    c.db,
                                                    adminSessions.group(1),
                                                    store.maxBrowserSessions),
                                    "deploymentLimit", store.maxBrowserSessions));
        }
        Matcher basicAccess =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/basic-auth")
                        .matcher(path);
        if (basicAccess.matches()) {
            boolean api = path.startsWith("/api/");
            method(method, api ? new String[] {"GET", "POST"} : new String[] {"POST"});
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            String id = basicAccess.group(1);
            if (method.equals("GET")) return json(map("result", BasicAccess.state(c.db, id)), 200);
            Object input =
                    api
                            ? body(c)
                            : map(
                                    "allow_basic",
                                    Service.flag(
                                            Map.of("allow_basic", c.parameter("allow_basic", "")),
                                            "allow_basic"));
            var result = BasicAccess.change(c.db, c.user.get("user_id").toString(), id, input);
            return api ? json(map("result", result), 200) : redirect("/admin/accounts/" + id);
        }
        Matcher accessMode =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/access-mode")
                        .matcher(path);
        if (accessMode.matches()) {
            boolean api = path.startsWith("/api/");
            method(method, api ? new String[] {"GET", "POST"} : new String[] {"POST"});
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            String id = accessMode.group(1);
            if (method.equals("GET")) return json(map("result", AccessMode.state(c.db, id)), 200);
            Object input =
                    api
                            ? body(c)
                            : map(
                                    "api_only",
                                    Service.flag(
                                            Map.of("api_only", c.parameter("api_only", "")),
                                            "api_only"));
            var result = AccessMode.change(c.db, c.user.get("user_id").toString(), id, input);
            return api ? json(map("result", result), 200) : redirect("/admin/accounts/" + id);
        }
        Matcher passwordRequirement =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/require-password-change")
                        .matcher(path);
        if (passwordRequirement.matches()) {
            boolean api = path.startsWith("/api/");
            method(method, api ? new String[] {"GET", "POST"} : new String[] {"POST"});
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            String id = passwordRequirement.group(1);
            if (method.equals("GET"))
                return json(map("result", PasswordReset.state(c.db, id)), 200);
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            var state = PasswordReset.require(c.db, c.user.get("user_id").toString(), id);
            return api ? json(map("result", state), 200) : redirect("/admin/accounts/" + id);
        }
        if (Set.of("/admin/password-exclusions", "/api/openservice/password-exclusions")
                .contains(path)) {
            method(method, "GET", "POST");
            c.admin();
            c.uniqueQuery();
            if (method.equals("POST")) {
                c.noQuery();
                var entry =
                        PasswordExclusions.add(
                                c.db,
                                c.user.get("user_id").toString(),
                                path.startsWith("/api/")
                                        ? body(c)
                                        : map(
                                                "label",
                                                c.parameter("label", ""),
                                                "password",
                                                c.parameter("password", "")));
                return path.startsWith("/api/")
                        ? json(map("result", entry), 201)
                        : redirect("/admin/password-exclusions");
            }
            var directory = PasswordExclusions.page(c.db, queryArguments(c));
            return path.startsWith("/api/")
                    ? json(directory, 200)
                    : view(
                            c,
                            "password-exclusions",
                            map("table", "accounts", "directory", directory));
        }
        Matcher exclusionRemove =
                Pattern.compile(
                                "/(?:admin/password-exclusions/([0-9a-f]{32})/remove|api/openservice/password-exclusions/([0-9a-f]{32}))")
                        .matcher(path);
        if (exclusionRemove.matches()) {
            boolean api = path.startsWith("/api/");
            method(method, api ? "DELETE" : "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            PasswordExclusions.remove(
                    c.db,
                    c.user.get("user_id").toString(),
                    api ? exclusionRemove.group(2) : exclusionRemove.group(1));
            return api
                    ? json(map("result", map("removed", true)), 200)
                    : redirect("/admin/password-exclusions");
        }
        if (Set.of("/account/approvals", "/api/openservice/me/approvals").contains(path)) {
            method(method, "GET");
            c.uniqueQuery();
            if (c.db.postgres) c.db.all("SELECT pg_advisory_xact_lock(18439,4)");
            Authentication.requireCurrent(c.db, c.user);
            var result = ApprovalInbox.page(c.db, c.arguments());
            return path.startsWith("/api/")
                    ? json(result, 200)
                    : view(
                            c,
                            "approval-inbox",
                            map(
                                    "table",
                                    "my-approvals",
                                    "reviews",
                                    result.get("result"),
                                    "status",
                                    result.get("status"),
                                    "nextBefore",
                                    result.get("next_before")));
        }
        Matcher accountSecurity =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/security-events")
                        .matcher(path);
        if (accountSecurity.matches()) {
            method(method,"GET"); c.admin();
            var journal =
                    AccountSecurityEvents.page(
                            c.db,
                            c.user.get("user_id").toString(),
                            accountSecurity.group(1),
                            queryArguments(c));
            boolean download = (Boolean)journal.get("download");
            if(download) disposition(c,"account-security-events.json");
            if (path.startsWith("/api/") || download)
                return json(
                        map(
                                "account",
                                journal.get("account"),
                                "result",
                                journal.get("result"),
                                "next_before",
                                journal.get("next_before")),
                        200);
            String before = journal.get("before").toString();
            return view(
                    c,
                    "account-security-events",
                    map(
                            "table",
                            "accounts",
                            "account",
                            journal.get("account"),
                            "events",
                            journal.get("result"),
                            "nextBefore",
                            journal.get("next_before"),
                            "downloadQuery",
                            before.isEmpty() ? "download=true" : "download=true&before=" + before));
        }
        Matcher accountAuthentication =
                Pattern.compile(
                                "/(?:api/openservice/users|admin/accounts)/([0-9a-f]{32})/authentication-events")
                        .matcher(path);
        if (accountAuthentication.matches()) {
            method(method, "GET");
            c.admin();
            var journal = AccountAuthentication.page(c.db, c.user.get("user_id").toString(),
                    accountAuthentication.group(1), queryArguments(c));
            boolean download = (Boolean) journal.get("download");
            if (download) disposition(c, "account-authentication-events.json");
            if (path.startsWith("/api/") || download) return json(map(
                    "account", journal.get("account"), "result", journal.get("result"),
                    "next_before", journal.get("next_before")), 200);
            String before = journal.get("before").toString();
            return view(
                    c,
                    "account-authentication",
                    map(
                            "table",
                            "accounts",
                            "account",
                            journal.get("account"),
                            "events",
                            journal.get("result"),
                            "nextBefore",
                            journal.get("next_before"),
                            "methods",
                            AuthenticationEvents.METHODS,
                            "downloadQuery",
                            before.isEmpty() ? "download=true" : "download=true&before=" + before));
        }
        if (Set.of("/account/authentication-events", "/api/openservice/me/authentication-events")
                .contains(path)) {
            method(method, "GET");
            var journal =
                    AuthenticationEvents.mine(
                            c.db, c.user.get("user_id").toString(), queryArguments(c));
            boolean download = (Boolean) journal.get("download");
            if (download) disposition(c, "my-authentication-events.json");
            if (path.startsWith("/api/") || download)
                return json(
                        map(
                                "result",
                                journal.get("result"),
                                "next_before",
                                journal.get("next_before")),
                        200);
            String before = journal.get("before").toString();
            return view(
                    c,
                    "personal-authentication",
                    map(
                            "table",
                            "account",
                            "events",
                            journal.get("result"),
                            "nextBefore",
                            journal.get("next_before"),
                            "methods",
                            AuthenticationEvents.METHODS,
                            "downloadQuery",
                            before.isEmpty() ? "download=true" : "download=true&before=" + before));
        }
        Matcher sessionRevoke =
                Pattern.compile("/(?:account|api/openservice/me)/sessions/([0-9a-f]{32})/revoke")
                        .matcher(path);
        if (sessionRevoke.matches()) {
            method(method, "POST");
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            boolean current =
                    BrowserSessions.revoke(c.db, c.user, sessionRevoke.group(1), c.session.getId());
            if (current) c.session.invalidate();
            return api
                    ? json(map("result", map("revoked", true, "current", current)), 200)
                    : redirect(current ? "/login" : "/account/sessions");
        }
        if (Set.of("/account/sessions", "/api/openservice/me/sessions").contains(path)) {
            method(method, "GET");
            c.noQuery();
            var sessions =
                    BrowserSessions.mine(
                            c.db, c.user, c.session.getId(), System.currentTimeMillis());
            return path.startsWith("/api/")
                    ? json(sessions, 200)
                    : view(
                            c,
                            "sessions",
                            map(
                                    "table",
                                    "account",
                                    "sessions",
                                    sessions.get("result"),
                                    "truncated",
                                    sessions.get("truncated"),
                                    "maximumSessions",
                                    AccountSessionLimit.effective(
                                            c.db,
                                            c.user.get("user_id").toString(),
                                            store.maxBrowserSessions)));
        }
        if (path.equals("/account")) {
            method(method, "GET");
            c.noQuery();
            return view(c, "account", map("table", "account", "error", null));
        }
        if (Set.of("/account/password", "/api/openservice/me/password").contains(path)) {
            method(method, "POST");
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            try {
                Service.changePassword(
                        c.db,
                        c.user,
                        api
                                ? body(c)
                                : map(
                                        "current_password",
                                        c.parameter("current_password", ""),
                                        "new_password",
                                        c.parameter("new_password", "")));
                c.session.invalidate();
                return api ? json(map("result", map("changed", true)), 200) : redirect("/login");
            } catch (Failure failure) {
                if (api) throw failure;
                if (failure.status == 429) c.response.setHeader("Retry-After", "300");
                var result =
                        view(c, "account", map("table", "account", "error", failure.getMessage()));
                result.setStatus(HttpStatus.valueOf(failure.status));
                return result;
            }
        }
        if (Set.of("/account/sessions/revoke", "/api/openservice/me/sessions/revoke")
                .contains(path)) {
            method(method, "POST");
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            Service.revokeSessions(c.db, c.user.get("user_id").toString());
            SecurityEvents.record(
                    c.db,
                    c.user.get("user_id").toString(),
                    c.user.get("user_id").toString(),
                    "account.sessions_revoked",
                    map());
            c.session.invalidate();
            return api ? json(map("result", map("revoked", true)), 200) : redirect("/login");
        }
        Matcher membership =
                Pattern.compile(
                                "/api/openservice/groups/([0-9a-f]{32})/members(?:/([0-9a-f]{32}))?")
                        .matcher(path);
        if (membership.matches()) {
            c.admin();
            c.noQuery();
            String group = membership.group(1), member = membership.group(2);
            method(method, member == null ? new String[] {"GET", "PUT"} : new String[] {"DELETE"});
            Service.record(c.db, "sys_user_group", group);
            var result =
                    method.equals("GET")
                            ? GroupMembers.list(c.db, group)
                            : GroupMembers.change(
                                    c.db,
                                    group,
                                    c.user.get("user_id").toString(),
                                    member == null ? body(c) : map("user_id", member),
                                    method.equals("PUT"));
            return json(map("result", result), 200);
        }
        Matcher memberForm = Pattern.compile("/group-members/([0-9a-f]{32})").matcher(path);
        if (memberForm.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            String operation = c.parameter("operation", "");
            if (!Set.of("add", "remove").contains(operation))
                throw new Failure(400, "Choose add or remove");
            boolean add = operation.equals("add");
            String member =
                    add
                            ? RecordAccess.owner(c.db, c.parameter("username", ""))
                            : c.parameter("user_id", "");
            GroupMembers.change(
                    c.db,
                    memberForm.group(1),
                    c.user.get("user_id").toString(),
                    map("user_id", member),
                    add);
            return redirect("/record/sys_user_group/" + memberForm.group(1));
        }
        Matcher access =
                Pattern.compile("/(api/openservice/access|record-access)/([^/]+)/([0-9a-f]{32})")
                        .matcher(path);
        if (access.matches()) {
            boolean api = access.group(1).startsWith("api/");
            method(method, api ? "PATCH" : "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            var item = Service.record(c.db, access.group(2), access.group(3));
            Object change =
                    api
                            ? body(c)
                            : c.parameter("visibility", "").equals("group")
                                    ? map(
                                            "visibility",
                                            "group",
                                            "group_id",
                                            c.parameter("group_id", ""))
                                    : map(
                                            "visibility",
                                            c.parameter("visibility", ""),
                                            "owner_id",
                                            c.parameter("visibility", "").equals("private")
                                                    ? RecordAccess.owner(
                                                            c.db, c.parameter("owner", ""))
                                                    : "");
            var result = RecordAccess.change(c.db, item, c.user.get("user_id").toString(), change);
            return api
                    ? json(map("result", result), 200)
                    : redirect("/record/" + access.group(2) + "/" + access.group(3));
        }
        Matcher collection = Pattern.compile("/api/now/(?:v[12]/)?table/([^/]+)").matcher(path);
        if (collection.matches()) {
            method(method, "GET", "POST");
            String table = collection.group(1);
            if (method.equals("POST")) {
                c.noQuery();
                var item = Service.create(c.db, table, body(c), c.name());
                c.response.setHeader("Location", c.base() + path + "/" + item.get("sys_id"));
                return json(map("result", FieldAccess.serialize(c.db, item)), 201);
            }
            var page = Service.page(c.db, table, c.arguments(), "");
            if (path.startsWith("/api/now/v1/") && page.rows().isEmpty())
                throw new Failure(404, "No records found");
            if (page.total() >= 0)
                c.response.setHeader("X-Total-Count", String.valueOf(page.total()));
            if (!page.suppressPagination()) {
                var links = new ArrayList<String>();
                var positions = map("first", 0);
                if (page.total() >= 0)
                    positions.put(
                            "last", Math.max(0, (page.total() - 1) / page.limit() * page.limit()));
                positions.put("prev", (long) page.offset() - page.limit());
                positions.put("next", (long) page.offset() + page.limit());
                for (var entry : positions.entrySet()) {
                    long index = ((Number) entry.getValue()).longValue();
                    if (entry.getKey().equals("prev") && index < 0
                            || entry.getKey().equals("next") && !page.hasNext()) continue;
                    var args = new LinkedHashMap<>(c.arguments());
                    args.put("sysparm_limit", String.valueOf(page.limit()));
                    args.put("sysparm_offset", String.valueOf(index));
                    links.add(
                            "<"
                                    + c.http.getRequestURL()
                                    + "?"
                                    + query(args)
                                    + ">; rel=\""
                                    + entry.getKey()
                                    + "\"");
                }
                c.response.setHeader("Link", String.join(", ", links));
            }
            return json(map("result", page.rows()), 200);
        }
        Matcher resource =
                Pattern.compile("/api/now/(?:v[12]/)?table/([^/]+)/([^/]+)").matcher(path);
        if (resource.matches()) {
            method(method, "GET", "PATCH", "PUT", "DELETE");
            String table = resource.group(1);
            Service.schema(c.db, table);
            var item = Service.record(c.db, table, resource.group(2));
            if (method.equals("GET")) {
                if (!Set.of("sysparm_fields").containsAll(c.arguments().keySet()))
                    throw new Failure(400, "Unsupported query parameter");
                c.response.setHeader("ETag", "\"" + item.get("revision") + "\"");
                return json(
                        map(
                                "result",
                                Service.project(
                                        FieldAccess.serialize(c.db, item),
                                        c.parameter("sysparm_fields", ""))),
                        200);
            }
            c.noQuery();
            if (method.equals("DELETE")) {
                c.admin();
                Service.remove(c.db, item, c.name());
                return binary(new byte[0], "application/json", 204);
            }
            String revision = c.http.getHeader("If-Match");
            if (revision != null) revision = revision.replaceAll("^\"+|\"+$", "");
            return json(
                    map(
                            "result",
                            FieldAccess.serialize(
                                    c.db,
                                    Service.update(
                                            c.db, table, item, body(c), c.name(), c.role(),
                                            revision))),
                    200);
        }
        Matcher permissions =
                Pattern.compile("/api/openservice/permissions/([^/]+)/([0-9a-f]{32})")
                        .matcher(path);
        if (permissions.matches()) {
            method(method, "GET");
            c.noQuery();
            return json(
                    map(
                            "result",
                            Permissions.inspect(c.db, permissions.group(1), permissions.group(2))),
                    200);
        }
        Matcher comments =
                Pattern.compile("/api/openservice/comments/([^/]+)/([0-9a-f]{32})").matcher(path);
        if (comments.matches()) {
            method(method, "GET", "POST");
            c.noQuery();
            var item = Service.record(c.db, comments.group(1), comments.group(2));
            if (method.equals("POST"))
                return json(map("result", Comments.add(c.db, item, body(c), c.name())), 201);
            return json(map("result", Comments.list(c.db, comments.group(2))), 200);
        }
        Matcher analytics =
                Pattern.compile("/(api/openservice/analytics|analytics)/([^/]+)").matcher(path);
        if (analytics.matches()) {
            method(method, "GET");
            c.uniqueQuery();
            String table = analytics.group(2);
            if (analytics.group(1).startsWith("api/"))
                return json(map("result", Analytics.report(c.db, table, c.arguments())), 200);
            var definition = Service.schema(c.db, table);
            RecordPolicy.read(c.db,definition);
            definition = FieldAccess.schema(c.db, definition);
            var fields = Analytics.fields(definition);
            var args = new LinkedHashMap<>(c.arguments());
            args.putIfAbsent("field", fields.isEmpty() ? "" : fields.keySet().iterator().next());
            var report =
                    fields.isEmpty() ? map("rows", List.of()) : Analytics.report(c.db, table, args);
            return view(
                    c,
                    "analytics",
                    map(
                            "table",
                            table,
                            "definition",
                            definition,
                            "metricFields",
                            fields,
                            "metric",
                            args.get("field"),
                            "minimum",
                            c.parameter("minimum", ""),
                            "maximum",
                            c.parameter("maximum", ""),
                            "groupBy",
                            c.parameter("group_by", ""),
                            "filter",
                            c.parameter("q", ""),
                            "report",
                            report));
        }
        Matcher stats = Pattern.compile("/api/now/stats/([^/]+)").matcher(path);
        if (stats.matches()) {
            method(method, "GET");
            c.uniqueQuery();
            return json(map("result", Service.stats(c.db, stats.group(1), c.arguments())), 200);
        }
        if (path.equals("/api/openservice/configuration")) {
            method(method, "GET", "POST");
            c.admin();
            if (method.equals("GET")) {
                c.noQuery();
                disposition(c, "openservice-configuration.json");
                return json(Configuration.export(c.db), 200);
            }
            if (!Set.of("dry_run").containsAll(c.arguments().keySet())
                    || !Set.of("true", "false").contains(c.parameter("dry_run", "true")))
                throw new Failure(400, "Use dry_run=true or false");
            return json(
                    map(
                            "result",
                            Configuration.install(
                                    c.db,
                                    body(c),
                                    c.user.get("user_id").toString(),
                                    c.parameter("dry_run", "true").equals("true"))),
                    200);
        }
        if (path.equals("/api/openservice/schema")) {
            method(method, "GET");
            var result = new LinkedHashMap<String, Object>();
            for (var table : c.db.all("SELECT * FROM os_schema ORDER BY name"))
                if(RecordPolicy.allows(object(table.get("definition")),"read",c.role()))result.put((String) table.get("name"), FieldAccess.schema(c.db,object(table.get("definition"))));
            return json(map("result", result), 200);
        }
        if (path.equals("/api/openservice/me")) {
            method(method, "GET");
            c.noQuery();
            return json(
                    map(
                            "result",
                            map(
                                    "user_id",
                                    c.user.get("user_id"),
                                    "name",
                                    c.name(),
                                    "role",
                                    c.role())),
                    200);
        }
        if (path.equals("/admin/invitation-directory")
                || path.equals("/api/openservice/invitation-directory")) {
            method(method,"GET"); c.admin(); c.uniqueQuery();
            var result =
                    InvitationDirectory.list(
                            c.db, c.user.get("user_id").toString(), queryArguments(c));
            if(path.startsWith("/api/")) return json(map("result",result),200);
            result.put("table","invitations"); result.put("roles",Service.ROLES);
            return view(c,"invitation-directory",result);
        }
        if (path.equals("/admin/invitations")) return invitations(c, method);
        if (path.equals("/api/openservice/invitations")) {
            method(method, "GET", "POST");
            c.admin();
            c.noQuery();
            return method.equals("POST")
                    ? json(
                            map(
                                    "result",
                                    Invitations.issue(
                                            c.db, c.user.get("user_id").toString(), body(c))),
                            201)
                    : json(map("result", Invitations.latest(c.db)), 200);
        }
        Matcher revokeInvite =
                Pattern.compile("/(?:admin|api/openservice)/invitations/([0-9a-f]{32})/revoke")
                        .matcher(path);
        if (revokeInvite.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            var result =
                    Invitations.revoke(
                            c.db, c.user.get("user_id").toString(), revokeInvite.group(1));
            return api ? json(map("result", result), 200) : redirect("/admin/invitations");
        }
        if (Set.of(
                        "/admin/authentication-events",
                        "/api/openservice/authentication-events",
                        "/admin/security-events",
                        "/api/openservice/security-events")
                .contains(path)) {
            method(method, "GET");
            AuditGovernance.requireViewer(c.db,c.user.get("user_id").toString());
            boolean authentication = path.endsWith("authentication-events");
            var journal =
                    authentication
                            ? AuthenticationEvents.page(c.db, queryArguments(c))
                            : SecurityEvents.page(c.db, queryArguments(c));
            boolean download = (Boolean) journal.get("download");
            if (download)
                disposition(
                        c, authentication ? "authentication-events.json" : "security-events.json");
            if (path.startsWith("/api/") || download)
                return json(
                        map(
                                "result",
                                journal.get("result"),
                                "next_before",
                                journal.get("next_before")),
                        200);
            String before = journal.get("before").toString();
            return view(
                    c,
                    authentication ? "authentication-events" : "security-events",
                    map(
                            "table",
                            "security",
                            "events",
                            journal.get("result"),
                            "nextBefore",
                            journal.get("next_before"),
                            "downloadQuery",
                            before.isEmpty() ? "download=true" : "download=true&before=" + before));
        }
        Matcher recoveryIssue =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/recovery").matcher(path);
        if (recoveryIssue.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            return json(
                    map(
                            "result",
                            RecoveryLinks.issue(c.db, c.user, recoveryIssue.group(1), body(c))),
                    201);
        }
        Matcher recoveryRevoke =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/recovery/revoke")
                        .matcher(path);
        if (recoveryRevoke.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            RecoveryLinks.revoke(c.db, c.user.get("user_id").toString(), recoveryRevoke.group(1));
            return api
                    ? json(map("result", map("revoked", true)), 200)
                    : redirect("/admin/accounts/" + recoveryRevoke.group(1));
        }
        Matcher transferAssignments =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/assignments/transfer")
                        .matcher(path);
        if (transferAssignments.matches()) {
            method(method, "POST");
            c.admin();
            boolean api = path.startsWith("/api/");
            Object input;
            boolean dryRun;
            if (api) {
                if (!Set.of("dry_run").containsAll(c.arguments().keySet())
                        || !Set.of("true", "false").contains(c.parameter("dry_run", "false")))
                    throw new Failure(400, "Use dry_run=true or false");
                dryRun = c.parameter("dry_run", "false").equals("true");
                input = body(c);
            } else {
                c.noQuery();
                String operation = c.parameter("operation", "transfer");
                if (!Set.of("preview", "transfer").contains(operation))
                    throw new Failure(400, "Choose preview or transfer");
                dryRun = operation.equals("preview");
                if (c.http.getParameterMap().entrySet().stream()
                        .anyMatch(
                                entry ->
                                        !entry.getKey().equals("record_ids")
                                                && entry.getValue().length != 1))
                    throw new Failure(400, "Repeated scalar form parameter");
                String name = c.parameter("new_owner", "");
                if (!name.matches("[A-Za-z0-9]{1,80}"))
                    throw new Failure(400, "Choose an active account username");
                String[] selected = c.http.getParameterValues("record_ids");
                List<String> selections = selected == null ? List.of() : Arrays.asList(selected);
                var revisions = new LinkedHashMap<String, Object>();
                for (String selection : selections)
                    revisions.put(selection, c.parameter("revision_" + selection, ""));
                input =
                        map(
                                "new_owner_id",
                                RecordAccess.owner(c.db, name),
                                "record_ids",
                                selections,
                                "expected_revisions",
                                revisions);
            }
            var report =
                    Assignments.transfer(
                            c.db,
                            c.user.get("user_id").toString(),
                            transferAssignments.group(1),
                            input,
                            dryRun);
            if (!api && dryRun) {
                var page =
                        Assignments.page(
                                c.db,
                                transferAssignments.group(1),
                                Map.of("page", c.parameter("page", "1")));
                page.putAll(
                        map(
                                "table",
                                "accounts",
                                "preview",
                                report,
                                "newOwner",
                                c.parameter("new_owner", "")));
                return view(c, "assignments", page);
            }
            return api
                    ? json(map("result", report), 200)
                    : redirect("/admin/accounts/" + transferAssignments.group(1) + "/assignments");
        }
        Matcher assignments =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/assignments")
                        .matcher(path);
        if (assignments.matches()) {
            method(method, "GET");
            c.admin();
            c.uniqueQuery();
            var report = Assignments.page(c.db, assignments.group(1), c.arguments());
            if (path.startsWith("/api/")) return json(map("result", report), 200);
            report.put("table", "accounts");
            return view(c, "assignments", report);
        }
        Matcher transferPrivate =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/private-records/transfer")
                        .matcher(path);
        if (transferPrivate.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            Object input;
            if (api) input = body(c);
            else {
                String name = c.parameter("new_owner", "");
                if (!name.matches("[A-Za-z0-9]{1,80}"))
                    throw new Failure(400, "Choose an active account username");
                input = map("new_owner_id", RecordAccess.owner(c.db, name));
            }
            var result =
                    PrivateRecords.transfer(
                            c.db,
                            c.user.get("user_id").toString(),
                            transferPrivate.group(1),
                            input);
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + transferPrivate.group(1));
        }
        Matcher accountMemberships =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/memberships").matcher(path);
        if (accountMemberships.matches()) {
            method(method, "GET");
            c.admin();
            c.noQuery();
            return json(
                    map("result", GroupMembers.forUser(c.db, accountMemberships.group(1))), 200);
        }
        Matcher removeMemberships =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/memberships/remove")
                        .matcher(path);
        if (removeMemberships.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            var result =
                    GroupMembers.removeForUser(
                            c.db, c.user.get("user_id").toString(), removeMemberships.group(1));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + removeMemberships.group(1));
        }
        Matcher accountRole =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/role").matcher(path);
        if (accountRole.matches()) {
            method(method, "PATCH");
            c.admin();
            c.noQuery();
            return json(
                    map(
                            "result",
                            Service.changeRole(
                                    c.db,
                                    c.user.get("user_id").toString(),
                                    accountRole.group(1),
                                    body(c))),
                    200);
        }
        Matcher accountStatus =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/status").matcher(path);
        if (accountStatus.matches()) {
            method(method, "PATCH");
            c.admin();
            c.noQuery();
            return json(
                    map(
                            "result",
                            Service.changeActive(
                                    c.db,
                                    c.user.get("user_id").toString(),
                                    accountStatus.group(1),
                                    body(c))),
                    200);
        }
        Matcher accountPage = Pattern.compile("/admin/accounts/([0-9a-f]{32})").matcher(path);
        if (accountPage.matches()) return accountDetail(c, accountPage.group(1), method);
        if (path.equals("/api/openservice/users")) {
            method(method, "GET", "POST");
            c.admin();
            if (method.equals("GET")) {
                var directory = AccountDirectory.list(c.db, queryArguments(c));
                return json(
                        map(
                                "result",
                                directory.get("accounts"),
                                "page",
                                directory.get("page"),
                                "pages",
                                directory.get("pages"),
                                "total",
                                directory.get("total")),
                        200);
            }
            return json(
                    map(
                            "result",
                            Service.createManagedAccount(
                                    c.db, body(c), c.user.get("user_id").toString())),
                    201);
        }
        Matcher importing = Pattern.compile("/api/openservice/import/([^/]+)").matcher(path);
        if (importing.matches()) {
            method(method, "POST");
            c.admin();
            if (!Set.of("dry_run").containsAll(c.arguments().keySet())
                    || !List.of("true", "false").contains(c.parameter("dry_run", "true")))
                throw new Failure(400, "Use dry_run=true or false");
            var report =
                    Service.migrate(
                            c.db,
                            importing.group(1),
                            body(c),
                            c.name(),
                            c.parameter("dry_run", "true").equals("true"));
            return json(report, ((List<?>) report.get("errors")).isEmpty() ? 200 : 400);
        }
        if (path.startsWith("/api/now/attachment")) return attachments(c, path, method);
        if (path.equals("/") || path.startsWith("/workspace/")) {
            method(method, "GET");
            String table = path.equals("/") ? "incident" : path.substring(11);
            var definition = Service.schema(c.db, table);
            if(path.equals("/") && !RecordPolicy.allows(definition,"read",c.role()))return view(c,"workspace-limited",map("table","overview"));
            var page =
                    Service.page(
                            c.db,
                            table,
                            Map.of(
                                    "sysparm_query",
                                    c.parameter("q", ""),
                                    "sysparm_limit",
                                    "30",
                                    "sysparm_offset",
                                    c.parameter("offset", "0")),
                            c.parameter("search", ""));
            return view(
                    c,
                    "workspace",
                    map(
                            "table",
                            table,
                            "definition",
                            definition,
                            "rows",
                            page.rows(),
                            "total",
                            page.total(),
                            "offset",
                            page.offset(),
                            "canCreateRecord",
                            !Set.of("reader", "requester").contains(c.role())
                                    && RecordPolicy.allows(definition, "create", c.role()),
                            "overview",
                            path.equals("/"),
                            "search",
                            c.parameter("search", ""),
                            "previousLink",
                            "?"
                                    + query(
                                            Map.of(
                                                    "search",
                                                    c.parameter("search", ""),
                                                    "offset",
                                                    String.valueOf(
                                                            Math.max(0, page.offset() - 30)))),
                            "nextLink",
                            "?"
                                    + query(
                                            Map.of(
                                                    "search",
                                                    c.parameter("search", ""),
                                                    "offset",
                                                    String.valueOf((long) page.offset() + 30)))));
        }
        Matcher approval =
                Pattern.compile(
                                "/(api/openservice/records|record)/([^/]+)/([0-9a-f]{32})/approvals(?:/([0-9a-f]{32})/(decision|withdraw|delegate))?")
                        .matcher(path);
        if (approval.matches()) return approvals(c, approval, method);
        Matcher detail =
                Pattern.compile("/record/([^/]+)/([^/]+)(?:/(note|attachment|comment))?")
                        .matcher(path);
        if (detail.matches())
            return record(c, detail.group(1), detail.group(2), detail.group(3), method);
        Matcher download = Pattern.compile("/attachment/([^/]+)").matcher(path);
        if (download.matches()) {
            method(method, "GET");
            var file = c.db.one("SELECT * FROM os_attachment WHERE sys_id=?", download.group(1));
            if (file == null) throw new Failure(404, "Attachment not found");
            var allowed = new ArrayList<Object>(List.of(file.get("record_id")));
            if (c.db.one(
                            "SELECT sys_id FROM os_record WHERE sys_id=? AND "
                                    + RecordAccess.filter(c.db, "os_record.sys_id", allowed),
                            allowed.toArray())
                    == null) throw new Failure(404, "Attachment not found");
            AttachmentHolds.requireDownload(c.db,file.get("sys_id").toString());
            disposition(c, (String) file.get("name"));
            return binary((byte[]) file.get("content"), (String) file.get("content_type"), 200);
        }
        if (path.equals("/migration")) return migration(c, method);
        Matcher export = Pattern.compile("/export/([^/]+)").matcher(path);
        if (export.matches()) {
            method(method, "GET");
            String table = export.group(1);
            RecordPolicy.read(c.db,Service.schema(c.db,table));
            if (c.parameter("source", "").equals("true")) c.admin();
            var data = new ArrayList<Map<String, Object>>();
            var parameters = new ArrayList<Object>(List.of(table));
            String visible = RecordAccess.filter(c.db, "os_record.sys_id", parameters);
            for (var item :
                    c.db.all(
                            "SELECT * FROM os_record WHERE \"table\"=? AND "
                                    + visible
                                    + " ORDER BY seq",
                            parameters.toArray())) {
                var original = object(item.get("original"));
                if (c.parameter("source", "").equals("true") && !original.isEmpty())
                    data.add(original);
                else {
                    var merged =
                            new LinkedHashMap<String, Object>(
                                    c.role().equals("admin") ? original : map());
                    merged.putAll(FieldAccess.serialize(c.db, item));
                    data.add(merged);
                }
            }
            disposition(c, table + ".json");
            return json(map("result", data), 200);
        }
        if (path.equals("/audit")) {
            method(method, "GET");
            var parameters = new ArrayList<Object>();
            var events =
                    c.db.all(
                            "SELECT * FROM os_event WHERE "
                                    + RecordAccess.filter(c.db, "os_event.record_id", parameters)
                                    + " AND "
                                    + RecordAccess.journalFilter(c.db)
                                    + " ORDER BY seq DESC LIMIT 200",
                            parameters.toArray());
            var safeEvents = new ArrayList<Map<String, Object>>();
            for (var event : events) {
                var safe = FieldAccess.event(c.db, event);
                safe.put(
                        "detailText",
                        JSON.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(safe.get("detail")));
                safeEvents.add(safe);
            }
            events = safeEvents;
            return view(c, "audit", map("table", "audit", "events", events));
        }
        if (path.equals("/dictionary")) {
            method(method, "GET");
            return view(c, "dictionary", map("table", "dictionary"));
        }
        if (path.equals("/admin/configuration")) {
            method(method, "GET", "POST");
            c.admin();
            Map<String, Object> report = null;
            String document = c.parameter("document", "");
            if (method.equals("POST")) {
                if (!Set.of("preview", "install").contains(c.parameter("operation", "")))
                    throw new Failure(400, "Choose preview or install");
                Object packageBody;
                try {
                    packageBody = JSON.readValue(document, Object.class);
                } catch (tools.jackson.core.JacksonException invalid) {
                    throw new Failure(400, "Enter a valid JSON configuration package");
                }
                report =
                        Configuration.install(
                                c.db,
                                packageBody,
                                c.user.get("user_id").toString(),
                                c.parameter("operation", "").equals("preview"));
            }
            return view(
                    c,
                    "configuration",
                    map("table", "configuration", "document", document, "report", report));
        }
        if (path.equals("/admin/accounts")) return accounts(c, method);
        throw new Failure(404, "Resource not found");
    }

    private ModelAndView approvals(Request c, Matcher route, String method) throws Exception {
        if (c.db.postgres) c.db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Authentication.requireCurrent(c.db, c.user);
        boolean api = route.group(1).startsWith("api/");
        String table = route.group(2), id = route.group(3), decision = route.group(4);
        boolean withdraw = "withdraw".equals(route.group(5));
        boolean delegate = "delegate".equals(route.group(5));
        method(method, decision == null ? new String[] {"GET", "POST"} : new String[] {"POST"});
        c.uniqueQuery();
        String location = "/record/" + table + "/" + id + "/approvals";
        if (method.equals("POST")) {
            c.noQuery();
            Object payload;
            if (api) payload = body(c);
            else if (delegate) payload=map("approver",c.parameter("approver",""));
            else if (withdraw) {
                if (!Set.of("csrf", "withdrawal_reason").containsAll(c.arguments().keySet()))
                    throw new Failure(400, "Provide only a withdrawal reason");
                payload = map("comment", c.parameter("withdrawal_reason", ""));
            } else if (decision != null) {
                payload=map("decision",c.parameter("decision",""),"comment",c.parameter("comment",""));
                if(c.arguments().containsKey("current_password"))object(payload).put("current_password",c.parameter("current_password",""));
            } else {
                String revision = c.parameter("expected_revision", "");
                if (!revision.matches("0|[1-9][0-9]{0,9}")
                        || Long.parseLong(revision) > Integer.MAX_VALUE)
                    throw new Failure(400, "Provide a valid record revision");
                payload =
                        map(
                                "expected_revision",
                                Integer.parseInt(revision),
                                "approver",
                                c.parameter("approver", ""),
                                "target_state",
                                c.parameter("target_state", ""),
                                "reason",
                                c.parameter("reason", ""));
            }
            var result =
                    decision == null
                            ? Approvals.request(c.db, table, id, c.user, payload)
                            : delegate ? Approvals.delegate(c.db,table,id,decision,c.user,payload)
                            : withdraw
                                    ? Approvals.withdraw(c.db, table, id, decision, c.user, payload)
                                    : Approvals.decide(c.db, table, id, decision, c.user, payload);
            // Password verification committed its audit evidence and released the write lock.
            // The decision reacquired it; a browser session revoked in that interval must
            // still prevent this transaction from committing the decision.
            if(object(payload).containsKey("current_password") && Objects.toString(c.http.getHeader("Authorization"),"").isEmpty())
                BrowserSessions.requireCurrent(c.db,c.user,c.session.getId(),System.currentTimeMillis());
            return api
                    ? json(map("result", result), decision == null ? 201 : 200)
                    : redirect(location);
        }
        var result = Approvals.page(c.db, table, id, c.arguments());
        if (api) return json(result, 200);
        var item = Service.record(c.db, table, id);
        var definition = Service.schema(c.db, table);
        return view(
                c,
                "approvals",
                map(
                        "table",
                        table,
                        "item",
                        item,
                        "recordPath",
                        "/record/" + table + "/" + id,
                        "location",
                        location,
                        "viewerId",
                        c.user.get("user_id"),
                        "requirements",
                        result.get("requirements"),
                        "schemaVersion",
                        result.get("schema_version"),
                        "approvals",
                        result.get("result"),
                        "pending",
                        result.get("pending_count"),
                        "nextBefore",
                        result.get("next_before"),
                        "states",
                        definition.get("states"),
                        "targets",
                        object(definition.get("flow"))
                                .getOrDefault(object(item.get("data")).get("state"), List.of())));
    }

    private ModelAndView record(Request c, String table, String id, String action, String method)
            throws Exception {
        var definition = Service.schema(c.db, table);
        RecordPolicy.read(c.db,definition);
        var item = id.equals("new") ? null : Service.record(c.db, table, id);
        String location = "/record/" + table + "/" + id;
        if (action != null) {
            method(method, "POST");
            if (item == null) throw new Failure(404, "Record not found");
            if (action.equals("comment")) {
                Comments.add(c.db, item, map("body", c.parameter("body", "")), c.name());
            } else if (action.equals("note")) {
                String note = c.parameter("body", "").strip();
                if (note.isEmpty() || note.codePointCount(0, note.length()) > 65536)
                    throw new Failure(400, "Enter a note of 1–65536 characters");
                Service.audit(c.db, item, c.name(), "note", map("body", note));
            } else {
                var file =
                        c.http instanceof MultipartHttpServletRequest multipart
                                ? multipart.getFile("file")
                                : null;
                if (file == null
                        || file.getOriginalFilename() == null
                        || file.getOriginalFilename().isEmpty())
                    throw new Failure(400, "Choose a file");
                if (file.getSize() > 5 * 1024 * 1024)
                    throw new Failure(400, "Attachments are limited to 5 MB");
                String name = filename(file.getOriginalFilename()),attachmentId=id();
                c.db.update(
                        "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                        attachmentId,
                        id,
                        name,
                        file.getBytes());
                AttachmentScanning.enqueue(c.db,attachmentId);
                Service.audit(c.db, item, c.name(), "attachment", map("name", name));
            }
            return redirect(location);
        }
        method(method, "GET", "POST");
        if (method.equals("POST")) {
            var payload = new LinkedHashMap<String, Object>();
            for (String field : object(definition.get("fields")).keySet())
                if (c.http.getParameter(field) != null)
                    payload.put(field, c.http.getParameter(field));
            if (item == null) item = Service.create(c.db, table, payload, c.name());
            else
                Service.update(
                        c.db,
                        table,
                        item,
                        payload,
                        c.name(),
                        c.role(),
                        c.http.getParameter("revision"));
            c.session.setAttribute("flash", "Record saved. Everything is up to date.");
            return redirect("/record/" + table + "/" + item.get("sys_id"));
        }
        definition = FieldAccess.schema(c.db, definition);
        var data = item == null ? map() : FieldAccess.serialize(c.db, item);
        var states = object(definition.get("states"));
        String state =
                (String)
                        data.getOrDefault(
                                "state", states.isEmpty() ? "" : states.keySet().iterator().next());
        var choices = new LinkedHashMap<String, Object>();
        for (var option : states.entrySet())
            if (option.getKey().equals(state)
                    || item != null
                            && ((List<?>)
                                            object(definition.get("flow"))
                                                    .getOrDefault(state, List.of()))
                                    .contains(option.getKey()))
                choices.put(option.getKey(), option.getValue());
        return view(
                c,
                "detail",
                map(
                        "table",
                        table,
                        "definition",
                        definition,
                        "submittedAnswers",item!=null && table.equals("sc_request") && FieldAccess.allowed(c.db,Service.schema(c.db,table),"description")?CatalogueQuestions.answers(c.db,id):List.of(),
                        "customerIntake",item!=null && table.equals("customer_case") && FieldAccess.allowed(c.db,Service.schema(c.db,table),"description")?CustomerCases.snapshot(c.db,id):null,
                        "catalogueProgress",item!=null && table.equals("sc_request") && FieldAccess.allowed(c.db,Service.schema(c.db,table),"state")?CatalogueWorkflows.progress(c.db,id):null,
                        "hasFulfilment",item!=null && table.equals("sc_request") && Fulfilment.readable(c.db) && c.db.count("SELECT COUNT(*) FROM os_fulfilment WHERE request_id=?",id)>0,
                        "canWriteRecord",
                        !Set.of("reader", "requester").contains(c.role())
                                && RecordPolicy.allows(
                                        definition, item == null ? "create" : "update", c.role()),
                        "fieldRules",
                        TypedFields.rules(definition),
                        "emptyRule",
                        map(),
                        "item",
                        item,
                        "data",
                        data,
                        "access",
                        item == null ? map() : RecordAccess.status(c.db, id),
                        "accessGroups",
                        c.role().equals("admin") ? RecordAccess.groups(c.db) : List.of(),
                        "members",
                        item != null && table.equals("sys_user_group") && c.role().equals("admin")
                                ? GroupMembers.list(c.db, id)
                                : List.of(),
                        "stateChoices",
                        choices,
                        "currentState",
                        state,
                        "events",
                        item == null
                                ? List.of()
                                : c.db.all(
                                        "SELECT * FROM os_event WHERE record_id=? AND "
                                                + RecordAccess.journalFilter(c.db)
                                                + " ORDER BY seq DESC",
                                        id),
                        "attachments",
                        item == null
                                ? List.of()
                                : c.db.all(
                                        "SELECT sys_id,name,quarantined FROM os_attachment WHERE record_id=?"
                                                + " ORDER BY sys_id",
                                        id)));
    }

    private ModelAndView invitations(Request c, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        c.noQuery();
        c.uniqueQuery();
        Map<String, Object> issued = null;
        String error = null;
        int status = 200;
        if (method.equals("POST")) {
            try {
                issued =
                        !c.parameter("email", "").isEmpty()
                                ? EmailOnboarding.invite(
                                        store,
                                        c.db,
                                        c.user.get("user_id").toString(),
                                        map(
                                                "name",
                                                c.parameter("name", ""),
                                                "role",
                                                c.parameter("role", "reader"),
                                                "email",
                                                c.parameter("email", "")))
                                : Invitations.issue(
                                        c.db,
                                        c.user.get("user_id").toString(),
                                        map(
                                                "name",
                                                c.parameter("name", ""),
                                                "role",
                                                c.parameter("role", "reader")));
                status = 201;
            } catch (Failure failure) {
                error = failure.getMessage();
                status = failure.status;
            }
        }
        var view =
                view(
                        c,
                        "invitations",
                        map(
                                "table",
                                "invitations",
                                "issued",
                                issued,
                                "error",
                                error,
                                "invitations",
                                Invitations.latest(c.db),
                                "roles",
                                Service.ROLES));
        view.setStatus(HttpStatus.valueOf(status));
        return view;
    }

    private ModelAndView accountDetail(Request c, String id, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        c.noQuery();
        c.uniqueQuery();
        var account = Service.account(c.db, id);
        Map<String, Object> recovery = null;
        String error = null;
        int status = 200;
        if (method.equals("POST")) {
            try {
                String operation = c.parameter("operation", "status");
                if (!Set.of("status", "role", "recovery", "audit").contains(operation))
                    throw new Failure(400, "Choose an account operation");
                if (operation.equals("audit")) {
                    String enabled=c.parameter("enabled", "");
                    TypedFields.require(Set.of("true","false").contains(enabled), "Choose enabled or disabled audit access");
                    AuditGovernance.grant(c.db,c.user.get("user_id").toString(),map("user_id",id,"enabled",Boolean.parseBoolean(enabled)));
                } else if (operation.equals("role")) {
                    Service.changeRole(
                            c.db,
                            c.user.get("user_id").toString(),
                            id,
                            map("role", c.parameter("role", "")));
                } else if (operation.equals("recovery")) {
                    recovery =
                            RecoveryLinks.issue(
                                    c.db,
                                    c.user,
                                    id,
                                    map("current_password", c.parameter("current_password", "")));
                    status = 201;
                } else {
                    String active = c.parameter("active", "");
                    if (!Set.of("true", "false").contains(active))
                        throw new Failure(400, "Choose active or inactive");
                    Service.changeActive(
                            c.db,
                            c.user.get("user_id").toString(),
                            id,
                            map("active", Boolean.parseBoolean(active)));
                }
                if (!operation.equals("recovery")) return redirect("/admin/accounts/" + id);
            } catch (Failure failure) {
                if (failure.status == 429) c.response.setHeader("Retry-After", "300");
                error = failure.getMessage();
                status = failure.status;
            }
        }
        var result =
                view(
                        c,
                        "account-detail",
                        map(
                                "table",
                                "accounts",
                                "account",
                                account,
                                "profile",Profiles.read(c.db,id),
                                "auditViewer", c.db.count("SELECT audit_viewer FROM os_user WHERE user_id=?",id)==1,
                                "passwordChangeRequired",
                                PasswordReset.state(c.db, id).get("required"),
                                "apiOnly",
                                AccessMode.state(c.db, id).get("api_only"),
                                "allowBasic",
                                BasicAccess.state(c.db, id).get("allow_basic"),
                                "error",
                                error,
                                "roles",
                                Service.ROLES,
                                "privateCount",
                                PrivateRecords.count(c.db, id),
                                "memberships",
                                GroupMembers.forUser(c.db, id),
                                "recovery",
                                recovery));
        result.setStatus(HttpStatus.valueOf(status));
        return result;
    }

    private ModelAndView accounts(Request c, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        var args = queryArguments(c);
        var directory = AccountDirectory.list(c.db, args);
        int page = (Integer) directory.get("page");
        var values = map("name", "", "role", "reader", "require_password_change", "true");
        String error = null;
        if (method.equals("POST")) {
            // Servlet parameters place URL values before form values. Keep directory filters
            // separate from account fields, including the shared "role" parameter name.
            var form = new LinkedHashMap<String, String>();
            for (var entry : c.http.getParameterMap().entrySet()) {
                int offset = args.containsKey(entry.getKey()) ? 1 : 0;
                if (entry.getValue().length > offset + 1)
                    throw new Failure(400, "Repeated form parameter");
                if (entry.getValue().length > offset)
                    form.put(entry.getKey(), entry.getValue()[offset]);
            }
            values =
                    map(
                            "name",
                            form.getOrDefault("name", ""),
                            "role",
                            form.getOrDefault("role", "reader"),
                            "require_password_change",
                            form.getOrDefault("require_password_change", "false"));
            try {
                var account =
                        Service.createManagedAccount(
                                c.db,
                                map(
                                        "name",
                                        values.get("name"),
                                        "role",
                                        values.get("role"),
                                        "password",
                                        form.getOrDefault("password", ""),
                                        "require_password_change",
                                        Service.flag(form, "require_password_change")),
                                c.user.get("user_id").toString());
                c.session.setAttribute(
                        "flash",
                        "Account for " + account.get("name") + " created. They can now sign in.");
                args.put("page", Integer.toString(page));
                return redirect("/admin/accounts?" + query(args));
            } catch (Failure failure) {
                if (failure.status != 400) throw failure;
                error = failure.getMessage();
            }
        }
        var model =
                map("table", "accounts", "roles", Service.ROLES, "values", values, "error", error);
        model.putAll(directory);
        var result = view(c, "accounts", model);
        if (error != null) result.setStatus(HttpStatus.BAD_REQUEST);
        return result;
    }

    private ModelAndView migration(Request c, String method) throws Exception {
        method(method, "GET", "POST");
        Map<String, Object> report = null;
        if (method.equals("POST")) {
            c.admin();
            var file =
                    c.http instanceof MultipartHttpServletRequest multipart
                            ? multipart.getFile("file")
                            : null;
            if (file == null) throw new Failure(400, "Choose a JSON or CSV export");
            Object rows;
            try {
                String text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(file.getBytes()))
                                .toString()
                                .replaceFirst("^\uFEFF", "");
                if (Objects.toString(file.getOriginalFilename(), "")
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".csv")) {
                    var data = new ArrayList<Map<String, String>>();
                    try (var parser =
                            CSVParser.parse(
                                    text,
                                    CSVFormat.DEFAULT
                                            .builder()
                                            .setHeader()
                                            .setSkipHeaderRecord(true)
                                            .get())) {
                        for (var row : parser) data.add(row.toMap());
                    }
                    rows = data;
                } else rows = JSON.readValue(text, Object.class);
            } catch (java.io.IOException | RuntimeException error) {
                throw new Failure(400, "The file is not valid UTF-8 JSON or CSV");
            }
            report =
                    Service.migrate(
                            c.db,
                            c.parameter("table", "incident"),
                            rows,
                            c.name(),
                            !c.parameter("mode", "").equals("import"));
        }
        return view(
                c,
                "migration",
                map(
                        "table",
                        "migration",
                        "report",
                        report,
                        "destination",
                        c.parameter("table", "incident")));
    }

    private ModelAndView attachments(Request c, String path, String method) throws Exception {
        if (path.equals("/api/now/attachment/file")) {
            method(method, "POST");
            if (c.http instanceof MultipartHttpServletRequest)
                throw new Failure(415, "Send raw file bytes to this endpoint");
            if (!Set.of("table_name", "table_sys_id", "file_name").equals(c.arguments().keySet()))
                throw new Failure(400, "Supply table_name, table_sys_id and file_name only");
            return saveAttachment(
                    c,
                    c.parameter("table_name", ""),
                    c.parameter("table_sys_id", ""),
                    c.parameter("file_name", ""),
                    c.http.getContentType(),
                    c.http.getInputStream().readNBytes(5 * 1024 * 1024 + 1));
        }
        if (path.equals("/api/now/attachment/upload")) {
            method(method, "POST");
            c.noQuery();
            if (!(c.http instanceof MultipartHttpServletRequest multipart))
                throw new Failure(415, "Multipart form data is required");
            if (!Set.of("table_name", "table_sys_id").equals(c.arguments().keySet()))
                throw new Failure(400, "Supply table_name and table_sys_id form fields only");
            var files = multipart.getFiles("uploadFile");
            if (!multipart.getMultiFileMap().keySet().equals(Set.of("uploadFile"))
                    || files.size() != 1)
                throw new Failure(400, "Supply exactly one file part named uploadFile");
            var file = files.getFirst();
            return saveAttachment(
                    c,
                    c.parameter("table_name", ""),
                    c.parameter("table_sys_id", ""),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes());
        }
        String select =
                "SELECT a.sys_id,a.name AS file_name,a.record_id AS table_sys_id,r.\"table\" AS"
                        + " table_name,a.content_type,length(a.content) AS size_bytes";
        String from = " FROM os_attachment a JOIN os_record r ON r.sys_id=a.record_id";
        if (path.equals("/api/now/attachment")) {
            method(method, "GET");
            c.uniqueQuery();
            var args = c.arguments();
            if (!Set.of("sysparm_query", "sysparm_limit", "sysparm_offset")
                    .containsAll(args.keySet()))
                throw new Failure(400, "Unsupported attachment parameter");
            int limit = Service.number(c.parameter("sysparm_limit", "1000"), 1, 1000),
                    offset =
                            Service.number(
                                    c.parameter("sysparm_offset", "0"), 0, Integer.MAX_VALUE);
            var where = new ArrayList<String>();
            var ordering = new ArrayList<String>();
            var parameters = new ArrayList<Object>();
            var columns =
                    Map.of(
                            "sys_id",
                            "a.sys_id",
                            "file_name",
                            "a.name",
                            "table_sys_id",
                            "a.record_id",
                            "table_name",
                            "r.\"table\"");
            String encoded = c.parameter("sysparm_query", "");
            if (!encoded.isEmpty())
                for (String term : encoded.split("\\^", -1)) {
                    if (term.startsWith("ORDERBY")) {
                        boolean descending = term.startsWith("ORDERBYDESC");
                        String field = term.substring(descending ? 11 : 7);
                        if (!columns.containsKey(field))
                            throw new Failure(400, "Unsupported attachment ordering field");
                        ordering.add(columns.get(field) + (descending ? " DESC" : " ASC"));
                        continue;
                    }
                    int equals = term.indexOf('=');
                    if (equals < 0 || !columns.containsKey(term.substring(0, equals)))
                        throw new Failure(
                                400,
                                "Use equality on sys_id, file_name, table_sys_id or table_name,"
                                        + " joined by ^");
                    where.add(columns.get(term.substring(0, equals)) + "=?");
                    parameters.add(term.substring(equals + 1));
                }
            ordering.add("a.sys_id");
            String visible = RecordAccess.filter(c.db, "r.sys_id", parameters);
            parameters.add(limit);
            parameters.add(offset);
            var result = new ArrayList<Map<String, Object>>();
            for (var row :
                    c.db.all(
                            select
                                    + from
                                    + (where.isEmpty()
                                            ? ""
                                            : " WHERE " + String.join(" AND ", where))
                                    + (where.isEmpty() ? " WHERE " : " AND ")
                                    + visible
                                    + " ORDER BY "
                                    + String.join(",", ordering)
                                    + " LIMIT ? OFFSET ?",
                            parameters.toArray())) result.add(metadata(c, row));
            return json(map("result", result), 200);
        }
        Matcher route = Pattern.compile("/api/now/attachment/([^/]+)(/file)?").matcher(path);
        if (!route.matches()) throw new Failure(404, "Resource not found");
        c.noQuery();
        boolean content = route.group(2) != null;
        method(method, content ? new String[] {"GET"} : new String[] {"GET", "DELETE"});
        if (method.equals("DELETE")) c.admin();
        var parameters = new ArrayList<Object>(List.of(route.group(1)));
        var row =
                c.db.one(
                        select
                                + (content ? ",a.content" : "")
                                + from
                                + " WHERE a.sys_id=? AND "
                                + RecordAccess.filter(c.db, "r.sys_id", parameters),
                        parameters.toArray());
        if (row == null) throw new Failure(404, "Attachment not found");
        if (method.equals("DELETE")) {
            var parent =
                    Service.record(
                            c.db, (String) row.get("table_name"), (String) row.get("table_sys_id"));
            c.db.update("DELETE FROM os_attachment WHERE sys_id=?", row.get("sys_id"));
            Service.audit(
                    c.db,
                    parent,
                    c.name(),
                    "attachment_deleted",
                    map("sys_id", row.get("sys_id"), "name", row.get("file_name")));
            return binary(new byte[0], "application/json", 204);
        }
        var metadata = metadata(c, row);
        if (!content) return json(map("result", metadata), 200);
        AttachmentHolds.requireDownload(c.db,row.get("sys_id").toString());
        disposition(c, (String) row.get("file_name"));
        c.response.setHeader("X-Attachment-Metadata", JSON.writeValueAsString(metadata));
        return binary((byte[]) row.get("content"), (String) row.get("content_type"), 200);
    }

    private ModelAndView saveAttachment(
            Request c, String table, String parent, String originalName, String type, byte[] bytes)
            throws Exception {
        for (String value : Arrays.asList(table, parent, originalName))
            if (value == null || value.isBlank())
                throw new Failure(400, "Attachment fields must not be blank");
        try {
            if (type == null
                    || type.length() > 255
                    || !org.springframework.http.MediaType.parseMediaType(type).isConcrete())
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new Failure(415, "A concrete Content-Type of at most 255 characters is required");
        }
        if (bytes.length > 5 * 1024 * 1024)
            throw new Failure(413, "Attachments are limited to 5 MB");
        var item = Service.record(c.db, table, parent);
        String name = filename(originalName), id = id();
        c.db.update(
                "INSERT INTO os_attachment(sys_id,record_id,name,content,content_type)"
                        + " VALUES(?,?,?,?,?)",
                id,
                item.get("sys_id"),
                name,
                bytes,
                type);
        AttachmentScanning.enqueue(c.db,id);
        Service.audit(c.db, item, c.name(), "attachment", map("name", name));
        var row =
                map(
                        "sys_id",
                        id,
                        "file_name",
                        name,
                        "table_sys_id",
                        item.get("sys_id"),
                        "table_name",
                        item.get("table"),
                        "size_bytes",
                        bytes.length,
                        "content_type",
                        type);
        c.response.setHeader("Location", c.base() + "/api/now/attachment/" + id);
        return json(map("result", metadata(c, row)), 201);
    }

    private static Map<String, Object> metadata(Request c, Map<String, Object> row) {
        return map(
                "sys_id",
                row.get("sys_id"),
                "file_name",
                row.get("file_name"),
                "table_sys_id",
                row.get("table_sys_id"),
                "table_name",
                row.get("table_name"),
                "size_bytes",
                row.get("size_bytes").toString(),
                "content_type",
                row.get("content_type"),
                "download_link",
                c.base() + "/api/now/attachment/" + row.get("sys_id") + "/file");
    }

    private ModelAndView view(Request c, String name, Map<String, Object> data) throws Exception {
        var counts = new LinkedHashMap<String, Object>();
        var parameters = new ArrayList<Object>();
        for (var count :
                c.db.all(
                        "SELECT \"table\",COUNT(*) AS total FROM os_record WHERE "
                                + RecordAccess.filter(c.db, "os_record.sys_id", parameters)
                                + " GROUP BY \"table\"",
                        parameters.toArray()))
            counts.put((String) count.get("table"), count.get("total"));
        var tables = c.db.all("SELECT * FROM os_schema ORDER BY name");
        tables.removeIf(schema->!RecordPolicy.allows(object(schema.get("definition")),"read",c.role()));
        for (var schema : tables)
            schema.put("definition", FieldAccess.schema(c.db, object(schema.get("definition"))));
        if (c.role().equals("requester")) tables.removeIf(t -> !t.get("name").equals("sc_request"));
        var model =
                map(
                        "tables",
                        tables,
                        "counts",
                        counts,
                        "user",
                        map(
                                "name",
                                c.name(),
                                "role",
                                c.role(),
                                "audit_viewer",
                                ((Number) c.user.get("audit_viewer")).intValue() == 1),
                        "csrf",
                        c.session.getAttribute("csrf"),
                        "flash",
                        c.session.getAttribute("flash"),
                        "overview",
                        false,
                        "priorities",
                        map(
                                "1",
                                "Critical",
                                "2",
                                "High",
                                "3",
                                "Moderate",
                                "4",
                                "Low",
                                "5",
                                "Planning"));
        c.session.removeAttribute("flash");
        model.putAll(data);
        return new ModelAndView(name, model);
    }

    private static Map<String, String> queryArguments(Request c) {
        var result = new LinkedHashMap<String, String>();
        String query = c.http.getQueryString();
        if (query == null || query.isEmpty()) return result;
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    value =
                            pair.length == 2
                                    ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                                    : "";
            if (result.putIfAbsent(key, value) != null)
                throw new Failure(400, "Repeated query parameter");
        }
        return result;
    }

    private static String query(Map<String, String> arguments) {
        return String.join(
                "&",
                arguments.entrySet().stream()
                        .map(
                                e ->
                                        URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                                                + "="
                                                + URLEncoder.encode(
                                                        e.getValue(), StandardCharsets.UTF_8))
                        .toList());
    }

    static String filename(String name) {
        String clean =
                Normalizer.normalize(name, Normalizer.Form.NFKD)
                        .replaceAll("[^\\x00-\\x7F]", "")
                        .replace('/', ' ')
                        .strip()
                        .replaceAll("\\s+", "_")
                        .replaceAll("[^A-Za-z0-9_.-]", "")
                        .replaceAll("^[._]+|[._]+$", "");
        return clean.isEmpty() ? "attachment" : clean.substring(0, Math.min(255, clean.length()));
    }

    private static void disposition(Request c, String filename) {
        c.response.setHeader(
                "Content-Disposition",
                "attachment; filename=\""
                        + filename.replace("\"", "").replace("\r", "").replace("\n", "")
                        + "\"");
    }

    private static ModelAndView redirect(String location) {
        var result = new ModelAndView("redirect:" + location);
        result.setStatus(HttpStatus.FOUND);
        return result;
    }

    private static ModelAndView json(Object value, int status) {
        return binary(JSON.writeValueAsBytes(value), "application/json", status);
    }

    private static ModelAndView binary(byte[] bytes, String type, int status) {
        View view =
                (model, request, response) -> {
                    response.setCharacterEncoding((String) null);
                    response.setContentType(type);
                    response.setContentLength(bytes.length);
                    response.getOutputStream().write(bytes);
                };
        var result = new ModelAndView(view);
        result.setStatus(HttpStatus.valueOf(status));
        return result;
    }

    private static void method(String actual, String... accepted) {
        if (!List.of(accepted).contains(actual)) throw new Failure(405, "Method not allowed");
    }
}
