package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class TerritoryRoutes {
    private TerritoryRoutes() {}

    record Crew(
            String id,
            String depot,
            int start,
            int end,
            Set<String> skills,
            Map<String, Integer> parts) {}

    record Job(
            String id,
            String crew,
            String place,
            int open,
            int close,
            int duration,
            String skill,
            String part,
            boolean locked) {}

    record Visit(int start, int end, String place) {}

    record Choice(Crew crew, int start, int extra) {}

    static int minute(Map<String, Object> row, String field) {
        return integer(row.get(field), 0, 1440);
    }

    static int distance(Map<String, Integer> matrix, String from, String to) {
        return from.equals(to) ? 0 : matrix.getOrDefault(key(from, to), 2000);
    }

    static Choice best(Crew crew, Job job, List<Visit> stops, Map<String, Integer> matrix) {
        Choice best = null;
        String from = crew.depot;
        int available = crew.start;
        for (int i = 0; i <= stops.size(); i++) {
            Visit next =
                    i < stops.size() ? stops.get(i) : new Visit(crew.end, crew.end, crew.depot);
            int travel = distance(matrix, from, job.place);
            int start = Math.max(job.open, available + travel);
            int onward = distance(matrix, job.place, next.place);
            int added = travel + onward - distance(matrix, from, next.place);
            if (start + job.duration <= job.close
                    && start + job.duration + onward <= next.start
                    && (best == null || added < best.extra)) best = new Choice(crew, start, added);
            from = next.place;
            available = next.end;
        }
        return best;
    }

    static Map<String, Object> solve(
            List<Crew> crews,
            List<Job> jobs,
            Map<String, Integer> matrix,
            String absent,
            boolean baseline) {
        var stops = new TreeMap<String, List<Visit>>();
        var stock = new HashMap<String, Map<String, Integer>>();
        for (var c : crews) {
            stops.put(c.id, new ArrayList<>());
            stock.put(c.id, new HashMap<>(c.parts));
        }
        var appointments = new TreeMap<String, Object>();
        var missed = new TreeMap<String, String>();
        for (var j : jobs)
            if (j.locked) {
                require(stops.containsKey(j.crew), "Include the crew performing the locked visit");
                stock.get(j.crew)
                        .computeIfPresent(j.part, (part, available) -> Math.max(0, available - 1));
                stops.get(j.crew).add(new Visit(j.open, j.close, j.place));
                appointments.put(
                        j.id, map("crew", j.crew, "start", j.open, "end", j.close, "locked", true));
            }
        stops.values().forEach(s -> s.sort(Comparator.comparingInt(Visit::start)));
        var work = new ArrayList<>(jobs);
        if (!baseline) work.sort(Comparator.comparingInt(Job::close).thenComparing(Job::id));
        for (var j : work) {
            if (j.locked) continue;
            Choice selected = null;
            String reason = "No available qualified crew with parts";
            for (var c : crews) {
                if (c.id.equals(absent)
                        || (baseline && !c.id.equals(j.crew))
                        || !c.skills.contains(j.skill)
                        || stock.get(c.id).getOrDefault(j.part, 0) < 1) continue;
                reason = "Travel, shift or promised window prevents assignment";
                var candidate = best(c, j, stops.get(c.id), matrix);
                if (candidate != null && (selected == null || candidate.extra < selected.extra))
                    selected = candidate;
            }
            if (selected == null) {
                missed.put(j.id, reason);
                continue;
            }
            var schedule = stops.get(selected.crew.id);
            schedule.add(new Visit(selected.start, selected.start + j.duration, j.place));
            schedule.sort(Comparator.comparingInt(Visit::start));
            stock.get(selected.crew.id).merge(j.part, -1, Integer::sum);
            appointments.put(
                    j.id,
                    map(
                            "crew",
                            selected.crew.id,
                            "start",
                            selected.start,
                            "end",
                            selected.start + j.duration,
                            "locked",
                            false));
        }
        int travel = 0;
        for (var c : crews) {
            String from = c.depot;
            for (var v : stops.get(c.id)) {
                travel += distance(matrix, from, v.place);
                from = v.place;
            }
            travel += distance(matrix, from, c.depot);
        }
        return map(
                "visits",
                appointments,
                "unassigned",
                missed,
                "missed_windows",
                missed.size(),
                "travel_minutes",
                travel);
    }

    static Map<String, Object> basis(Store.Db db, String id) throws Exception {
        var task = FieldPlanning.task(db, id);
        return map(
                "dispatch",
                FieldDispatch.current(db, id).get("revision"),
                "task",
                task.get("revision"),
                "parent",
                WorkTasks.record(
                                db,
                                task.get("parent_table").toString(),
                                task.get("parent_id").toString())
                        .get("revision"),
                "visit",
                FieldVisits.current(db, id).get("revision"));
    }

    static void dispatch(Store.Db db, String id, String op, Map<String, Object> fields)
            throws Exception {
        var task = FieldPlanning.task(db, id);
        String table = task.get("parent_table").toString(),
                parent = task.get("parent_id").toString();
        fields.putAll(
                map(
                        "operation",
                        op,
                        "revision",
                        FieldDispatch.current(db, id).get("revision"),
                        "task_revision",
                        task.get("revision"),
                        "record_revision",
                        WorkTasks.record(db, table, parent).get("revision")));
        FieldDispatch.command(db, table, parent, id, fields);
    }

    static Object apply(Store.Db db, String id, Map<String, Object> data, Map<String, Object> b)
            throws Exception {
        require(Boolean.TRUE.equals(b.get("confirm")), "Confirm the reviewed route");
        ProblemInvestigations.revision(
                b.get("revision"), get(db, "territory-plan", id).get("revision"));
        require(!data.containsKey("published"), "This route was already published");
        var expected = object(data.get("basis"));
        var visits = object(object(data.get("proposed")).get("visits"));
        for (var job : objects(data.get("jobs"))) {
            String task = job.get("task").toString();
            require(expected.get(task).equals(basis(db, task)), "A visit changed after planning");
        }
        long midnight =
                LocalDate.parse(data.get("date").toString())
                        .atStartOfDay(ZoneOffset.UTC)
                        .toEpochSecond();
        var unchanged = new HashSet<String>();
        for (var job : objects(data.get("jobs"))) {
            String task = job.get("task").toString();
            var prior = FieldDispatch.current(db, task);
            if (visits.containsKey(task) && "assigned".equals(prior.get("status"))) {
                var planned = object(visits.get(task));
                if (List.of(
                                planned.get("crew"),
                                job.get("place"),
                                (midnight + 60L * (Integer) planned.get("start")) * 1000,
                                (midnight + 60L * (Integer) planned.get("end")) * 1000)
                        .equals(
                                List.of(
                                        prior.get("technician_id"),
                                        prior.get("location"),
                                        ((Number) prior.get("starts")).longValue(),
                                        ((Number) prior.get("ends")).longValue()))) {
                    unchanged.add(task);
                    continue;
                }
            }
            if ("assigned".equals(FieldDispatch.current(db, task).get("status")))
                dispatch(
                        db,
                        task,
                        "release",
                        map("reason", "Dispatcher published territory plan " + id));
        }
        for (var job : objects(data.get("jobs"))) {
            String task = job.get("task").toString();
            if (!visits.containsKey(task) || unchanged.contains(task)) continue;
            var visit = object(visits.get(task));
            if (Boolean.TRUE.equals(visit.get("locked"))) continue;
            dispatch(
                    db,
                    task,
                    "assign",
                    map(
                            "technician_id",
                            visit.get("crew"),
                            "start",
                            Instant.ofEpochSecond(midnight + 60L * (Integer) visit.get("start"))
                                    .toString(),
                            "end",
                            Instant.ofEpochSecond(midnight + 60L * (Integer) visit.get("end"))
                                    .toString(),
                            "location",
                            job.get("place")));
        }
        data.put("published", now());
        return put(db, "territory-plan", id, b.get("revision"), data);
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        boolean fresh = op.equals("optimize");
        String id = id(b, fresh ? "new_id" : "plan_id");
        if (op.equals("inspect")) return get(db, "territory-plan", id);
        var data =
                fresh
                        ? map(
                                "territory",
                                text(b, "territory", 100),
                                "date",
                                ProjectDelivery.date(b.get("date")),
                                "crews",
                                b.get("crews"),
                                "jobs",
                                b.get("jobs"),
                                "travel",
                                b.get("travel"))
                        : definition(db, "territory-plan", id);
        if (op.equals("publish")) return apply(db, id, data, b);
        var revisions = map();
        var crews = new ArrayList<Crew>();
        var people = new HashSet<String>();
        for (Object value : batch(data.get("crews"), 40)) {
            var c = object(value);
            keys(c, "id", "depot", "start", "end", "skills", "parts");
            String lead = id(c, "id");
            require(
                    people.add(lead) && "agent".equals(AgentPlans.account(db, lead).get("role")),
                    "Unique active technician required");
            var parts = new HashMap<String, Integer>();
            object(c.get("parts")).forEach((k, n) -> parts.put(k, integer(n, 0, 10000)));
            int start = minute(c, "start"), end = minute(c, "end");
            require(start < end, "Shift must end after its start");
            crews.add(
                    new Crew(
                            lead,
                            text(c, "depot", 100),
                            start,
                            end,
                            AuditEngagements.names(c.get("skills"), 40),
                            parts));
        }
        var matrix = new TreeMap<String, Integer>();
        for (Object value : batch(data.get("travel"), 600)) {
            var leg = object(value);
            keys(leg, "from", "to", "minutes");
            matrix.put(key(text(leg, "from", 100), text(leg, "to", 100)), minute(leg, "minutes"));
        }
        var jobs = new ArrayList<Job>();
        var ids = new HashSet<String>();
        for (Object value : batch(data.get("jobs"), 120)) {
            var j = object(value);
            keys(j, "task", "crew", "place", "open", "close", "duration", "skill", "part");
            String task = id(j, "task");
            require(ids.add(task), "Duplicate visit");
            revisions.put(task, basis(db, task));
            int open = minute(j, "open"), close = minute(j, "close");
            require(open < close, "Invalid promised window");
            var dispatch = FieldDispatch.current(db, task);
            boolean locked = "accepted".equals(dispatch.get("status"));
            String crew = id(j, "crew"), place = text(j, "place", 100);
            if (locked) {
                long midnight =
                        LocalDate.parse(data.get("date").toString())
                                .atStartOfDay(ZoneOffset.UTC)
                                .toInstant()
                                .toEpochMilli();
                open =
                        Math.toIntExact(
                                (((Number) dispatch.get("starts")).longValue() - midnight) / 60000);
                close =
                        Math.toIntExact(
                                (((Number) dispatch.get("ends")).longValue() - midnight) / 60000);
                require(open >= 0 && close <= 1440, "Accepted visit falls outside this UTC day");
                crew = dispatch.get("technician_id").toString();
                place = dispatch.get("location").toString();
            }
            jobs.add(
                    new Job(
                            task,
                            crew,
                            place,
                            open,
                            close,
                            integer(j.get("duration"), 1, 1440),
                            text(j, "skill", 80),
                            text(j, "part", 80),
                            locked));
        }
        String absent = fresh ? "" : id(b, "absent");
        require(fresh || people.contains(absent), "Choose a crew from this day");
        if (fresh) data.put("original", solve(crews, jobs, matrix, "", true));
        data.remove("published");
        data.put("basis", revisions);
        data.put("proposed", solve(crews, jobs, matrix, absent, false));
        data.put("absent", absent);
        return put(db, "territory-plan", id, fresh ? 0 : b.get("revision"), data);
    }
}
