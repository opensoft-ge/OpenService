package ge.opensoft.openservice;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Basic credentials can be ambient browser credentials, not proof of an intentional API call. */
final class BrowserWrites {
    private BrowserWrites() {}

    static boolean requiresCsrf(HttpServletRequest request, String path, String authorization) {
        if (!path.startsWith("/api/") || authorization == null || authorization.isEmpty())
            return true;
        if (request.getHeader("Origin") != null
                || request.getHeader("Referer") != null
                || request.getHeader("Sec-Fetch-Site") != null) return true;
        String type =
                Objects.toString(request.getContentType(), "")
                        .split(";", 2)[0]
                        .strip()
                        .toLowerCase(Locale.ROOT);
        return Set.of("", "text/plain", "application/x-www-form-urlencoded", "multipart/form-data")
                        .contains(type)
                && !"true".equals(request.getHeader("X-OpenService-API"));
    }
}
