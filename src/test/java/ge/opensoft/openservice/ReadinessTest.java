package ge.opensoft.openservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

import java.nio.file.Path;
import java.sql.SQLException;

class ReadinessTest {
    @TempDir Path root;

    @Test
    void connectionQueryAndCloseFailuresAreSanitizedAndDoNotCreateSessions() throws Exception {
        var unavailable = mock(Store.class);
        when(unavailable.open()).thenThrow(new SQLException("private-host-and-credential"));
        var controller = new WebController(unavailable);
        var request = new MockHttpServletRequest("GET", "/readyz");
        var response = new MockHttpServletResponse();
        var view = controller.dispatch(request, response);
        assertEquals(503, view.getStatus().value());
        view.getView().render(view.getModel(), request, response);
        assertEquals("{\"status\":\"not_ready\"}", response.getContentAsString());
        assertNull(request.getSession(false));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var emptyQuery = new MockHttpServletRequest("GET", "/readyz");
        emptyQuery.setQueryString("");
        assertEquals(
                503,
                controller.dispatch(emptyQuery, new MockHttpServletResponse()).getStatus().value());
        assertNull(emptyQuery.getSession(false));

        var fixture = new MigrationsTest();
        fixture.root = root;
        var real = new Store(fixture.configuration(false));
        try (var db = real.open()) {
            db.update("DROP TABLE os_migration");
            db.connection.commit();
        }
        assertFalse(Readiness.ready(real));
        var closeStore = mock(Store.class);
        var connection = spy(real.open());
        when(closeStore.open()).thenReturn(connection);
        doReturn(
                        ge.opensoft.openservice.Values.map(
                                "total", Migrations.VERSION, "valid", Migrations.VERSION))
                .when(connection)
                .one(anyString(), any());
        doThrow(new SQLException("private close failure")).when(connection).close();
        try {
            assertFalse(Readiness.ready(closeStore));
        } finally {
            connection.connection.close();
        }
        reset(unavailable);
        request = new MockHttpServletRequest("HEAD", "/readyz");
        assertEquals(
                405,
                controller.dispatch(request, new MockHttpServletResponse()).getStatus().value());
        request = new MockHttpServletRequest("GET", "/readyz");
        request.setQueryString("internal=true");
        assertEquals(
                400,
                controller.dispatch(request, new MockHttpServletResponse()).getStatus().value());
        verifyNoInteractions(unavailable);
    }
}
