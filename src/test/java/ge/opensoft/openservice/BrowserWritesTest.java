package ge.opensoft.openservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

class BrowserWritesTest {
    @Test
    void browserSignalsAndSafelistedBodiesCannotUseTheBasicBypass() {
        var request = new MockHttpServletRequest();
        String api = "/api/example", basic = "Basic test";
        assertTrue(BrowserWrites.requiresCsrf(request, "/admin/accounts", basic));
        assertTrue(BrowserWrites.requiresCsrf(request, api, null));
        assertTrue(BrowserWrites.requiresCsrf(request, api, ""));
        for (String type :
                Arrays.asList(
                        null,
                        "",
                        "text/plain",
                        " TEXT/PLAIN ; charset=UTF-8",
                        "application/x-www-form-urlencoded",
                        "multipart/form-data; boundary=example")) {
            request.setContentType(type);
            assertTrue(BrowserWrites.requiresCsrf(request, api, basic));
            request.addHeader("X-OpenService-API", "true");
            assertFalse(BrowserWrites.requiresCsrf(request, api, basic));
            request.removeHeader("X-OpenService-API");
        }
        request.setContentType("application/json");
        assertFalse(BrowserWrites.requiresCsrf(request, api, basic));
        for (String header : List.of("Origin", "Referer", "Sec-Fetch-Site")) {
            request.addHeader(header, "");
            request.addHeader("X-OpenService-API", "true");
            assertTrue(BrowserWrites.requiresCsrf(request, api, basic));
            request.removeHeader(header);
            request.removeHeader("X-OpenService-API");
        }
        request.setContentType("text/plain");
        request.addHeader("X-OpenService-API", "TRUE");
        assertTrue(BrowserWrites.requiresCsrf(request, api, basic));
    }
}
