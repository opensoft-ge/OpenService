package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.time.LocalDate;
import java.util.*;

final class EmploymentChanges {
    private EmploymentChanges() {}

    static void dates(String type,String effective,String back) {
        if(type.equals("leave")) require(ProjectDelivery.date(back).compareTo(effective)>0,"Return must follow the start of leave");
        else require(back.isEmpty(),"A transfer has no return date");
    }

    static Object command(Store.Db db,String id,Map<String,Object> body) throws Exception {
        lock(db);
        var record=EmployeeCases.record(db,id,false);
        require(EmployeeCases.writable(db),"HR case write access required");
        require(EmployeeOnboarding.open(record),"Choose an open HR case");
        RecordPolicy.require(Service.schema(db,"hr_case"),"update",db.viewerRole);
        var row=get(db,"employment-change",id);
        var data=object(row.get("definition"));
        String effective=ProjectDelivery.date(body.get("effective"));
        String back=Objects.toString(body.get("return_on"),"").strip();
        if (back.equals("none")) back="";
        if ("schedule_change".equals(body.get("operation"))) {
            keys(body,"operation","type","effective","return_on","steps","revision","record_revision");
            require(data.isEmpty(),"An employment change already exists on this case");
            var plan=EmployeeOnboarding.plan(db,id);
            require(((Number)plan.get("revision")).intValue()==0,"Choose a case without existing onboarding work");
            ProblemInvestigations.revision(body.get("record_revision"),record.get("revision"));
            String type=WorkJobs.text(body.get("type"),12);
            require(Set.of("transfer","leave").contains(type),"Choose transfer or leave");
            dates(type,effective,back);
            var phases=new ArrayList<String>();
            for(Object value:batch(body.get("steps"),20)) {
                var step=object(value);
                keys(step,"phase","group_id","summary","details");
                String phase=WorkJobs.text(step.get("phase"),16);
                require(Set.of("effective","return").contains(phase),"Choose effective or return");
                require(!phases.contains("return") || phase.equals("return"),"Place return work after leave work");
                phases.add(phase);
                var current=EmployeeCases.record(db,id,false);
                EmployeeOnboarding.command(db,id,map("operation","add","revision",EmployeeOnboarding.plan(db,id).get("revision"),
                        "record_revision",current.get("revision"),"display_title",step.get("summary"),"summary",step.get("summary"),
                        "details",step.get("details"),"group_id",step.get("group_id")));
            }
            require(phases.contains("effective") && phases.contains("return")==type.equals("leave"),"Provide work for every phase of this change");
            EmployeeOnboarding.command(db,id,map("operation","launch","revision",EmployeeOnboarding.plan(db,id).get("revision"),
                    "record_revision",EmployeeCases.record(db,id,false).get("revision")));
            data.putAll(map("type",type,"phases",phases));
        } else {
            keys(body,"operation","effective","return_on","revision","reason");
            require(!data.isEmpty(),"Schedule an employment change first");
            dates(data.get("type").toString(),effective,back);
            var work=db.all("SELECT ordinal,status FROM os_hr_onboarding_work WHERE parent_id=? ORDER BY ordinal",id);
            require(work.stream().anyMatch(step->step.get("status").equals("pending")),"No remaining scheduled tasks");
            for(var step:work) {
                if(step.get("status").equals("pending")) continue;
                int index=((Number)step.get("ordinal")).intValue()-1;
                String phase=((List<?>)data.get("phases")).get(index).toString();
                String dateField=phase.equals("return")?"return_on":"effective";
                require(Map.of("return_on",back,"effective",effective).get(dateField).equals(data.get(dateField)),"Keep dates for started or completed work");
            }
            data.put("reschedule_reason",WorkJobs.text(body.get("reason"),500));
        }
        data.putAll(map("effective",effective,"return_on",back));
        save(db,"employment-change",id,body.get("revision"),data);
        return data;
    }

    static boolean due(Store.Db db,Map<String,Object> work) throws java.sql.SQLException {
        var data=object(get(db,"employment-change",work.get("parent_id").toString()).get("definition"));
        if(data.isEmpty()) return true;
        int index=((Number)work.get("ordinal")).intValue()-1;
        String phase=((List<?>)data.get("phases")).get(index).toString();
        String day=data.get(phase.equals("return")?"return_on":"effective").toString();
        return !LocalDate.now().isBefore(LocalDate.parse(day));
    }
}
