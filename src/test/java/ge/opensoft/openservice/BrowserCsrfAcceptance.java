package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;

final class BrowserCsrfAcceptance {
    static HttpResponse<byte[]> send(
            WorkflowSupport h,
            String method,
            String path,
            byte[] body,
            String type,
            Map<String, String> headers)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(h.base() + path))
                        .header("Authorization", auth("admin"));
        if (type != null) builder.header("Content-Type", type);
        headers.forEach(builder::setHeader);
        return h.client.send(
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    static void run(WorkflowSupport h) throws Exception {
        String bootstrap = "/api/openservice/me/csrf", records = "/api/now/table/incident";
        assertEquals(401, h.get(bootstrap, null).statusCode());
        var response = h.get(bootstrap, auth("admin"));
        assertEquals(200, response.statusCode());
        String token = object(parsed(response).get("result")).get("csrf").toString();
        assertTrue(token.length() > 32);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertEquals(400, h.get(bootstrap + "?callback=foo", auth("admin")).statusCode());
        assertEquals(405, h.json("POST", bootstrap, map(), auth("admin")).statusCode());
        byte[] body = JSON.writeValueAsBytes(map("short_description", "Intentional request"));
        for (String header : List.of("Origin", "Referer", "Sec-Fetch-Site")) {
            assertEquals(
                    403,
                    send(h, "POST", records, body, "application/json", Map.of(header, "null"))
                            .statusCode());
            assertEquals(
                    201,
                    send(
                                    h,
                                    "POST",
                                    records,
                                    body,
                                    "application/json",
                                    Map.of(header, "null", "X-CSRF-Token", token))
                            .statusCode());
        }
        assertEquals(
                403,
                send(
                                h,
                                "POST",
                                records,
                                body,
                                "application/json",
                                Map.of("Origin", h.base(), "X-OpenService-API", "true"))
                        .statusCode());
        assertEquals(
                201, send(h, "POST", records, body, "application/json", Map.of()).statusCode());
        String incident = h.add("incident").get("sys_id").toString();
        String attachment =
                "/api/now/attachment/file?table_name=incident&table_sys_id="
                        + incident
                        + "&file_name=example.txt";
        for (String type :
                Arrays.asList(
                        null,
                        "text/plain",
                        "text/plain; charset=UTF-8",
                        "application/x-www-form-urlencoded",
                        "multipart/form-data; boundary=example"))
            assertEquals(
                    403, send(h, "POST", attachment, new byte[] {1}, type, Map.of()).statusCode());
        assertEquals(
                403,
                send(
                                h,
                                "POST",
                                attachment,
                                new byte[] {1},
                                "text/plain",
                                Map.of("X-OpenService-API", "false"))
                        .statusCode());
        assertEquals(
                201,
                send(
                                h,
                                "POST",
                                attachment,
                                new byte[] {1},
                                "text/plain",
                                Map.of("X-OpenService-API", "true"))
                        .statusCode());
        assertEquals(
                201,
                send(
                                h,
                                "POST",
                                attachment,
                                new byte[] {2},
                                "text/plain",
                                Map.of("X-CSRF-Token", token))
                        .statusCode());
        assertEquals(
                201,
                send(h, "POST", attachment, new byte[] {3}, "application/octet-stream", Map.of())
                        .statusCode());
        var first = h.client;
        try (var second = SessionLimitAcceptance.client()) {
            h.client = second;
            String other =
                    object(parsed(h.get(bootstrap, auth("admin"))).get("result"))
                            .get("csrf")
                            .toString();
            assertNotEquals(token, other);
            assertEquals(
                    403,
                    send(
                                    h,
                                    "POST",
                                    records,
                                    body,
                                    "application/json",
                                    Map.of("Origin", h.base(), "X-CSRF-Token", token))
                            .statusCode());
        } finally {
            h.client = first;
        }
        String fresh = h.login("admin");
        assertNotEquals(token, fresh);
        assertEquals(
                403,
                send(
                                h,
                                "POST",
                                records,
                                body,
                                "application/json",
                                Map.of("Origin", h.base(), "X-CSRF-Token", token))
                        .statusCode());
        assertEquals(
                201,
                send(
                                h,
                                "POST",
                                records,
                                body,
                                "application/json",
                                Map.of("Authorization", "", "X-CSRF-Token", fresh))
                        .statusCode());
        h.account("CsrfApiOnly", "reader");
        String target;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "CsrfApiOnly");
            String actor = RecordAccess.owner(db, "admin");
            AccessMode.change(db, actor, target, map("api_only", true));
            PasswordReset.require(db, actor, target);
            db.connection.commit();
        }
        var limited = h.get(bootstrap, auth("CsrfApiOnly"));
        assertEquals(200, limited.statusCode());
        assertEquals(403, h.get("/api/openservice/me", auth("CsrfApiOnly")).statusCode());
        String limitedToken = object(parsed(limited).get("result")).get("csrf").toString();
        assertEquals(
                200,
                send(
                                h,
                                "POST",
                                "/api/openservice/me/password",
                                JSON.writeValueAsBytes(
                                        map(
                                                "current_password",
                                                PASSWORD,
                                                "new_password",
                                                "csrf-api-replacement-password")),
                                "application/json",
                                Map.of(
                                        "Authorization",
                                        auth("CsrfApiOnly"),
                                        "Origin",
                                        h.base(),
                                        "X-CSRF-Token",
                                        limitedToken))
                        .statusCode());
    }
}
