package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class CaseMining {
    private CaseMining() {}

    static class Cohort {
        final Map<String, Integer> variants = new TreeMap<>();
        final Map<String, Long> wait = new TreeMap<>();
        final Map<String, Integer> visits = new TreeMap<>();
        final List<Object> cases = new ArrayList<>();
        int complete, reopened, loops, incompleteHistory;
        long fulfillment;

        Map<String, Object> result() {
            var meanWait = map();
            wait.forEach((state, seconds) -> meanWait.put(state, seconds / visits.get(state)));
            return map(
                    "cases",
                    cases,
                    "variants",
                    variants,
                    "looping_cases",
                    loops,
                    "completed",
                    complete,
                    "reopened",
                    reopened,
                    "reopen_rate",
                    complete == 0 ? null : (double) reopened / complete,
                    "mean_seconds_to_first_completion",
                    complete == 0 ? null : fulfillment / complete,
                    "mean_observed_seconds_per_state_visit",
                    meanWait,
                    "excluded_incomplete_histories",
                    incompleteHistory);
        }

        void observe(
                String id,
                List<Map<String, Object>> events,
                long start,
                long horizon,
                String goal) {
            var path = new ArrayList<String>();
            long entered = start;
            Long completedAfter=null;var transitions=new ArrayList<Long>();
            String state = null;
            boolean finished = false, reopen = false;
            for (var event : events) {
                long at = timestamp(event.get("created"));
                if (at > horizon) break;
                var detail = object(event.get("detail"));
                var after = object(detail.get("after"));
                String next = Objects.toString(after.get("state"), "");
                if (next.equals(state)) continue;
                if (state != null) {
                    dwell(state, at - entered);
                    if (goal.equals(state)) reopen = true;
                }
                state = next;
                entered = at;
                path.add(next);transitions.add(at);
                if (goal.equals(next) && !finished) {
                    finished = true;
                    completedAfter=at-start;
                    fulfillment += completedAfter;
                    complete++;
                }
            }
            dwell(state, horizon - entered);
            if (reopen) reopened++;
            if (new HashSet<>(path).size() < path.size()) loops++;
            variants.merge(String.join(" → ", path), 1, Integer::sum);
            cases.add(map("record_id", id, "path", path,"transition_epoch_seconds",transitions,"observed_until",horizon,"seconds_to_first_completion",completedAfter,"reopened",reopen));
        }

        void dwell(String state, long seconds) {
            wait.merge(state, seconds, Long::sum);
            visits.merge(state, 1, Integer::sum);
        }
    }

    static long timestamp(Object at) {
        return LocalDateTime.parse(at.toString().replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC);
    }

    static Object compare(Store.Db db, Map<String, Object> b) throws Exception {
        return compare(db,b,false);
    }

    static Object compare(Store.Db db, Map<String,Object> b,boolean baselineOnly) throws Exception {
        String table = text(b, "table", 80), goal = text(b, "completed_state", 80);
        var schema = Service.schema(db, table);
        RecordPolicy.read(db, schema);
        require(
                FieldAccess.allowed(db, schema, "state")
                        && object(schema.get("states")).containsKey(goal),
                "Choose a readable state and completion milestone");
        String query = Objects.toString(b.get("query"), "");
        var selection = Service.selection(db, table, query, "");
        long a =
                LocalDate.parse(ProjectDelivery.date(b.get("before_start")))
                        .atStartOfDay()
                        .toEpochSecond(ZoneOffset.UTC);
        long z = baselineOnly ? Long.MAX_VALUE / 2 :
                LocalDate.parse(ProjectDelivery.date(b.get("after_start")))
                        .atStartOfDay()
                        .toEpochSecond(ZoneOffset.UTC);
        long span = integer(b.get("days"), 1, 90) * 86400L,
                horizon = integer(b.get("hours"), 1, 2160) * 3600L;
        require(
                a + span <= z && (baselineOnly ? a : z) + span + horizon <= Instant.now().getEpochSecond(),
                "Choose non-overlapping mature UTC cohorts");
        var rows =
                db.all(
                        "SELECT * FROM os_record WHERE "
                                + selection.where()
                                + " ORDER BY seq LIMIT 501",
                        selection.values().toArray());
        require(rows.size() <= 500, "Narrow the selection to 500 visible cases");
        var before = new Cohort();
        var after = new Cohort();
        int unknown = 0;
        for (var row : rows) {
            var events =
                    db.all(
                            "SELECT action,created,detail FROM os_event WHERE record_id=? AND"
                                + " action IN ('created','updated') ORDER BY seq LIMIT 501",
                            row.get("sys_id"));
            if (events.isEmpty() || !"created".equals(events.getFirst().get("action"))) {
                unknown++;
                continue;
            }
            long start = timestamp(events.getFirst().get("created"));
            Cohort group =
                    start >= a && start < a + span
                            ? before
                            : start >= z && start < z + span ? after : null;
            if (group == null) continue;
            if (events.size() > 500
                    && timestamp(events.getLast().get("created")) <= start + horizon) {
                group.incompleteHistory++;
                continue;
            }
            group.observe(row.get("sys_id").toString(), events, start, start + horizon, goal);
        }
        return map(
                "before",
                before.result(),
                "after",
                after.result(),
                "query",
                query,
                "hours",
                b.get("hours"),
                "unknown_creation",
                unknown);
    }
}
