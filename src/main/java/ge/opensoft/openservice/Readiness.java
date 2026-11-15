package ge.opensoft.openservice;

import java.sql.SQLException;

/** Database reachability and exact migration history, without exposing internal diagnostics. */
final class Readiness {
    private Readiness() {}

    static boolean ready(Store store) {
        try (var db = store.open()) {
            var history =
                    db.one(
                            "SELECT COUNT(*) AS total, COUNT(CASE WHEN version BETWEEN 1 AND ? THEN"
                                + " 1 END) AS valid FROM os_migration",
                            Migrations.VERSION);
            return ((Number) history.get("total")).intValue() == Migrations.VERSION
                    && ((Number) history.get("valid")).intValue() == Migrations.VERSION;
        } catch (SQLException unavailable) {
            return false;
        }
    }
}
