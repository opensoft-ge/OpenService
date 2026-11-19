package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;

final class EnterpriseOperationsEdges {
    static void hr(WorkflowSupport h,String original,String it,String facilities) throws Exception {
        String service;
        try(var db=h.store.open()){service=db.one("SELECT service_id FROM os_hr_case WHERE record_id=?",original).get("service_id").toString();}
        String yesterday=LocalDate.now().minusDays(1).toString(),today=LocalDate.now().toString();
        var effective=map("phase","effective","summary","Move badge access","details","Update office access","group_id",it);
        var back=map("phase","return","summary","Restore equipment","details","Return to original office","group_id",facilities);
        var move=map("operation","move_change","effective",yesterday,"return_on",today,"revision",1,"reason","Employee request");
        bad(h,"hr",original,move,400);
        for(String type:List.of("transfer","leave")) {
            String id=EmployeeCasesAcceptance.create(h,service,"EmployeeAgent");
            var command=map("operation","schedule_change","revision",0,"record_revision",Integer.valueOf(object(data(h,"hr",id).get("record")).get("sys_mod_count").toString()),
                    "type",type,"effective",yesterday,"return_on","none","steps",List.of(effective));
            bad(h,"hr",id,move,400);
            if(type.equals("transfer")) {
                rejectVariants(h,"hr",id,command,List.of(map("return_on",today)));
                send(h,"hr",id,command);
            } else {
                command.putAll(map("return_on",today,"steps",List.of(effective,back,back)));
                send(h,"hr",id,command);
            }
            var tasks=EmployeeOnboardingAcceptance.bindings(h,id);
            for(int i=0;i<tasks.size();i++) {
                String child=tasks.get(i).get("child_id").toString(),person=i==0?"OnboardIT":"OnboardFacilities";
                EmployeeOnboardingAcceptance.task(h,child,"claim",person);
                if(i==1) {
                    rejectVariants(h,"hr",id,move,List.of(map("effective",LocalDate.now().minusDays(2).toString()),map("return_on",LocalDate.now().plusDays(1).toString())));
                    send(h,"hr",id,move);
                }
                EmployeeOnboardingAcceptance.task(h,child,"complete",person);
            }
            assertTrue(EmployeeOnboardingAcceptance.bindings(h,id).stream().allMatch(t->"complete".equals(t.get("status"))));
        }
        String existing=EmployeeCasesAcceptance.create(h,service,"EmployeeAgent");
        EmployeeOnboardingAcceptance.send(h,existing,EmployeeOnboardingAcceptance.addBody(h,existing,it),"admin");
        bad(h,"hr",existing,map("operation","schedule_change","type","transfer","effective",today,"return_on","","revision",0,
                "record_revision",0,"steps",List.of(effective)),400);
    }

    static void invoice(WorkflowSupport h) throws Exception {
        String key=id();
        var first=map("model","Desk","quantity",2,"unit_cost","50","months",12,"salvage","0");
        var second=new LinkedHashMap<>(first);second.put("model","Display");
        send(h,"assets","global",map("operation","multi_order","key",key,"supplier","Office supplies","currency","USD","lines",List.of(first,second)));
        send(h,"assets","global",map("operation","approve","order_id",key,"revision",1));
        var item=map("model","Desk","quantity",1,"unit_cost","40");
        var monitor=new LinkedHashMap<>(item);monitor.put("model","Display");
        var bill=map("operation","invoice","order",key,"invoice","BOTH","currency","USD","lines",List.of(item,monitor));
        rejectVariants(h,"assets","global",bill,List.of(map("lines",List.of()),map("lines",List.of(item,item)),map("currency","EUR")));
        assertEquals(2,object(EnterpriseOperationsAcceptance.outcome(h,"assets","global",bill).get("lines")).size());
    }

    static void portfolio(WorkflowSupport h,String project,Map<String,Object> scenario,Map<String,Object> calendar) throws Exception {
        var oversized=map();for(int i=0;i<=100;i++) oversized.put(LocalDate.now().plusDays(i).toString(),0);
        rejectVariants(h,"portfolio",project,calendar,List.of(map("week",List.of(10)),map("exceptions",oversized)));
        rejectVariants(h,"portfolio",project,scenario,List.of(map("finish_on","2026-01-01"),map("finish_on","2029-01-01")));
        bad(h,"portfolio",project,map("operation","open_scenario","key",id()),400);
        var wider=new LinkedHashMap<>(scenario);wider.putAll(map("key",id(),"start_on","2026-12-31","finish_on","2027-01-20"));
        send(h,"portfolio",project,wider);
        try(var db=h.store.open()) {
            PlatformFoundationTest.bind(db);
            var existing=EnterpriseData.get(db,"staff-scenario",scenario.get("key").toString());
            var snapshot=object(existing.get("definition"));snapshot.put("owner",id());
            EnterpriseData.save(db,"staff-scenario",scenario.get("key").toString(),existing.get("revision"),snapshot);
            assertThrows(Failure.class,()->StaffingScenarios.command(db,project,map("operation","open_scenario","key",scenario.get("key"))));
        }
    }
}
