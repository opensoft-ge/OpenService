package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.time.LocalDate;
import java.util.*;

final class ControlAssurance {
    private ControlAssurance() {}
    static List<Map<String,Object>> cases(Store.Db db,String incident)throws Exception {
        var visible=new ArrayList<Map<String,Object>>();
        for(var row:db.all("SELECT subject,revision,definition FROM os_enterprise_document WHERE area='case-control' AND subject LIKE ?",incident+"%")){
            var control=object(row.get("definition"));
            String finding=control.get("finding").toString();
            try{WorkTasks.record(db,"vulnerability",finding);}catch(Failure unavailable){continue;}
            var latest=VulnerabilityResponse.latest(db,finding);
            if("excepted".equals(control.get("state")) && !LocalDate.parse(control.get("expires_on").toString()).isAfter(LocalDate.now()))control.put("state","expired");
            if("verified".equals(control.get("state")) && !"fixed".equals(object(latest.get("detail")).get("status")))control.put("state","open");
            row.put("control_id",row.get("subject").toString().substring(incident.length()));
            visible.add(row);
        }
        return visible;
    }
    static Object command(Store.Db db,String incident,String op,Map<String,Object> b)throws Exception {
        WorkTasks.record(db,"security_incident",incident);
        require(SecurityIncidents.writable(db),"Security case update required");
        String key=incident+id(b,"control_id");
        var row=get(db,"case-control",key);
        var control=object(row.get("definition"));
        String finding=op.equals("control")?id(b,"finding_id"):boundFinding(control);
        WorkTasks.record(db,"vulnerability",finding);
        var scope=VulnerabilityResponse.finding(db,finding);
        String group=scope.get("group_id").toString();
        SecurityIncidents.eligible(db,group);
        require(db.count("SELECT COUNT(*) FROM os_security_case WHERE record_id=? AND group_id=?",incident,group)==1,"Finding and incident require the same response team");
        var latest=VulnerabilityResponse.latest(db,finding);
        if(op.equals("control")){
            require(control.isEmpty(),"This control already has a baseline");
            String owner=id(b,"owner_id");
            AgentPlans.account(db,owner);
            require(db.count("SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?",group,owner)==1,"Choose a current response team owner");
            control.putAll(map("finding",finding,"owner",owner,"baseline",latest.get("revision"),"state","open"));
        }else if(op.equals("exception")){
            LocalDate until=LocalDate.parse(ProjectDelivery.date(b.get("expires_on")));
            require(until.isAfter(LocalDate.now()) && !until.isAfter(LocalDate.now().plusYears(1)),"Exception must expire within one year");
            require(!db.viewerId.equals(control.get("owner")),"Exception request and decision require different people");
            control.putAll(map("requested_by",db.viewerId,"expires_on",until.toString(),"reason",text(b,"note",2000),"state","pending","exception_active",false));
        }else if(op.equals("decide")){
            require("pending".equals(control.get("state")) && db.viewerId.equals(control.get("owner")) && Fulfilment.member(db,group),"Current independent control owner required");
            boolean approved="approve".equals(b.get("decision"));
            require(approved || "reject".equals(b.get("decision")),"Choose approve or reject");
            require(LocalDate.parse(control.get("expires_on").toString()).isAfter(LocalDate.now()),"Exception has expired");
            control.putAll(map("state",approved?"excepted":"open","exception_active",approved,"decided_by",db.viewerId));
        }else{
            boolean verified=((Number)latest.get("revision")).intValue()>((Number)control.get("baseline")).intValue()
                    && "fixed".equals(object(latest.get("detail")).get("status")) && !scope.get("verify_at").toString().isEmpty() && VulnerabilityResponse.confirmed(db,finding);
            boolean excepted=Boolean.TRUE.equals(control.get("exception_active")) && LocalDate.parse(control.get("expires_on").toString()).isAfter(LocalDate.now());
            control.putAll(map("state",verified?"verified":excepted?"excepted":"open","exception_active",excepted,"report",latest.get("report_id"),"observed_at",latest.get("observed_at")));
        }
        var history=new ArrayList<>(objects(control.getOrDefault("history",List.of())));
        history.add(map("state",control.get("state"),"operation",op,"evidence",latest.get("report_id"),"actor",db.viewerId,"at",now()));
        control.put("history",history);
        save(db,"case-control",key,b.get("revision"),control);
        return get(db,"case-control",key);
    }
    private static String boundFinding(Map<String,Object> control){
        require(control.containsKey("finding"),"Register this control first");
        return control.get("finding").toString();
    }
}
