package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.EmployeeCasesAcceptance.result;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;

final class TenBusinessAcceptance {
    static void portfolio(WorkflowSupport h)throws Exception{
        String project=project(h),scenario=id(),billing=id(),database=id(),key=id();
        send(h,"portfolio",project,map("operation","budget","revision",0,"budget","400","remaining","100","currency","USD"));
        send(h,"portfolio",project,map("operation","save_scenario","key",scenario,"title","Implementation funding","currency","USD","ceiling","400","option_a",List.of(project),"option_b",List.of(),"start_on","2027-01-04","finish_on","2027-01-06"));
        var app=map("operation","application","application_id",database,"title","Shared database","capability","Billing","annual_cost","1200","currency","USD","owner_id",uid(h,"admin"),"revision",0);
        post(h,"architecture",app);
        app.putAll(map("application_id",billing,"title","Legacy billing","annual_cost","600"));post(h,"architecture",app);
        bad(h,"architecture","global",map("operation","dependency","application_id",id(),"applications",List.of(),"revision",0),400);
        for(var dependencies:List.of(List.of(billing),List.of(database,database)))
            bad(h,"architecture","global",map("operation","dependency","application_id",billing,"applications",dependencies,"revision",1),400);
        post(h,"architecture",map("operation","dependency","application_id",billing,"applications",List.of(database),"revision",1));
        var comparisonInputs=map("operation","compare_plans","new_id",id(),"project_id",project,"scenario_id",scenario,"months",12,"options",List.of());
        bad(h,"architecture","global",comparisonInputs,400);
        comparisonInputs.put("options",List.of(map("retire",List.of(),"transition_cost","500"),map("retire",List.of(),"transition_cost","0")));
        var unfunded=object(post(h,"architecture",comparisonInputs).get("definition"));
        assertEquals(false,object(unfunded.get("option_a")).get("feasible"));
        var comparison=post(h,"architecture",map("operation","compare_plans","new_id",key,"project_id",project,"scenario_id",scenario,"months",6,"options",List.of(
                map("retire",List.of(database),"transition_cost","35"),map("retire",List.of(billing),"transition_cost","25"))));
        var options=object(comparison.get("definition"));
        assertEquals(false,object(options.get("option_a")).get("feasible"));
        assertEquals("275.00",object(options.get("option_b")).get("forecast"));
        var decision=map("operation","approve_plan","plan_id",key,"option","option_a","revision",1);
        bad(h,"architecture","global",decision,400);
        decision.put("option","option_b");post(h,"architecture",decision);
        bad(h,"architecture","global",decision,400);
        var savings=map("operation","realized","plan_id",key,"amount","75.75","reference","GL-83","revision",2);
        assertEquals("199.25",object(post(h,"architecture",savings).get("definition")).get("remaining_forecast"));
        assertEquals(3,post(h,"architecture",savings).get("revision"));
        savings.put("amount","150");bad(h,"architecture","global",savings,400);
        var retirement=map("operation","retire","plan_id",key,"application_id",billing,"note","Decommissioning certificate 20","revision",3);
        var wrong=new LinkedHashMap<>(retirement);wrong.put("application_id",database);bad(h,"architecture","global",wrong,400);
        var second=map("operation","compare_plans","new_id",id(),"project_id",project,"scenario_id",scenario,"options",List.of(map("retire",List.of(billing),"transition_cost","0"),map("retire",List.of(),"transition_cost","0")),"months",6);
        post(h,"architecture",second);
        bad(h,"architecture","global",map("operation","approve_plan","plan_id",second.get("new_id"),"option","option_a","revision",1),400);
        post(h,"architecture",map("operation","dependency","application_id",database,"applications",List.of(billing),"revision",1));
        bad(h,"architecture","global",retirement,400);
        post(h,"architecture",map("operation","dependency","application_id",database,"applications",List.of(),"revision",2));
        post(h,"architecture",retirement);
        assertEquals("retired",object(doc(h,"business-app",billing).get("definition")).get("lifecycle"));
        retirement.put("revision",4);bad(h,"architecture","global",retirement,400);
        bad(h,"architecture","global",map("operation","dependency","application_id",billing,"applications",List.of(),"revision",4),400);
        bad(h,"architecture","global",map("operation","dependency","application_id",database,"applications",List.of(billing),"revision",3),400);
        second.putAll(map("new_id",id(),"options",List.of(map("retire",List.of(database),"transition_cost","0"),map("retire",List.of(),"transition_cost","0"))));
        post(h,"architecture",second);
        post(h,"architecture",map("operation","approve_plan","plan_id",second.get("new_id"),"option","option_a","revision",1));
        post(h,"architecture",map("operation","retire","plan_id",second.get("new_id"),"application_id",database,"note","Consumer already retired","revision",2));
        savings.putAll(map("plan_id",second.get("new_id"),"revision",3));bad(h,"architecture","global",savings,409);
    }
    static void assurance(WorkflowSupport h)throws Exception{
        var setup=VulnerabilityResponseAcceptance.setup(h);
        String team=setup[0],asset=setup[1],incident;
        try(var db=h.store.open()){PlatformFoundationTest.bind(db);incident=SecurityIncidents.intake(db,SecurityIncidentsAcceptance.intake(team)).get("sys_id").toString();db.connection.commit();}
        try(var scanner=new VulnerabilityResponseAcceptance.Endpoint(h.store,h.environment)){
            VulnerabilityResponseAcceptance.scan(h,scanner,team,VulnerabilityResponseAcceptance.report(1,List.of(VulnerabilityResponseAcceptance.entry(asset,"CONTROL-17","present"))));
            String finding=VulnerabilityResponseAcceptance.finding(h,asset,"CONTROL-17"),control=id();
            bad(h,"security",incident,map("operation","control","finding_id",finding,"control_id",control,"owner_id",uid(h,"VulnOther"),"revision",0),400);
            send(h,"security",incident,map("operation","control","finding_id",finding,"control_id",control,"owner_id",uid(h,"VulnAnalyst"),"revision",0));
            var exception=map("operation","exception","control_id",control,"expires_on",LocalDate.now().toString(),"note","Maintenance","revision",1);
            bad(h,"security",incident,exception,400);
            exception.put("expires_on",LocalDate.now().plusYears(2).toString());bad(h,"security",incident,exception,400);
            exception.put("expires_on",LocalDate.now().plusDays(2).toString());
            assertEquals(400,h.json("POST",api("security",incident),exception,auth("VulnAnalyst")).statusCode());
            send(h,"security",incident,map("operation","exception","control_id",control,"expires_on",LocalDate.now().plusDays(2).toString(),"note","Scheduled maintenance","revision",1));
            var decision=map("operation","decide","decision","approve","control_id",control,"revision",2);
            bad(h,"security",incident,decision,400);
            try(var db=h.store.open()){
                PlatformFoundationTest.bind(db);db.viewerId=RecordAccess.owner(db,"VulnAnalyst");
                db.update("DELETE FROM os_group_member WHERE group_id=? AND user_id=?",team,db.viewerId);
                assertThrows(Failure.class,()->ControlAssurance.command(db,incident,"decide",decision));db.connection.rollback();
                var extra=Service.create(db,"sys_user_group",map("short_description","Alternate response team"),"admin").get("sys_id");
                db.update("UPDATE os_security_case SET group_id=? WHERE record_id=?",extra,incident);
                assertThrows(Failure.class,()->ControlAssurance.command(db,incident,"assess",map("control_id",control,"revision",2)));
            }
            var invalid=new LinkedHashMap<>(decision);invalid.put("decision","unknown");
            assertEquals(400,h.json("POST",api("security",incident),invalid,auth("VulnAnalyst")).statusCode());
            result(h.json("POST",api("security",incident),decision,auth("VulnAnalyst")));
            var assess=map("operation","assess","control_id",control,"revision",3);
            assertEquals("excepted",object(object(send(h,"security",incident,assess).get("outcome")).get("definition")).get("state"));
            // An exception surviving overnight must be evaluated against today's date.
            try(var db=h.store.open()){
                PlatformFoundationTest.bind(db);
                var row=EnterpriseData.get(db,"case-control",incident+control);
                var historical=object(row.get("definition"));
                historical.put("expires_on",LocalDate.now().minusDays(1).toString());
                EnterpriseData.save(db,"case-control",incident+control,row.get("revision"),historical);db.connection.commit();
            }
            assertEquals("expired",object(objects(data(h,"security",incident).get("controls")).getFirst().get("definition")).get("state"));
            assess.put("revision",5);assertEquals("open",object(object(send(h,"security",incident,assess).get("outcome")).get("definition")).get("state"));
            // A later report can say fixed before the remediation team has requested verification.
            VulnerabilityResponseAcceptance.scan(h,scanner,team,VulnerabilityResponseAcceptance.report(10,List.of(VulnerabilityResponseAcceptance.entry(asset,"CONTROL-17","fixed"))));
            assess.put("revision",6);assertEquals("open",object(object(send(h,"security",incident,assess).get("outcome")).get("definition")).get("state"));
            VulnerabilityResponseAcceptance.scan(h,scanner,team,VulnerabilityResponseAcceptance.report(11,List.of(VulnerabilityResponseAcceptance.entry(asset,"CONTROL-17","present"))));
            VulnerabilityResponseAcceptance.state(h,finding,"remediate",200);
            VulnerabilityResponseAcceptance.task(h,finding,team);VulnerabilityResponseAcceptance.complete(h,finding);
            VulnerabilityResponseAcceptance.state(h,finding,"verify",200);
            var delayed=VulnerabilityResponseAcceptance.report(12,List.of(VulnerabilityResponseAcceptance.entry(asset,"CONTROL-17","fixed")));
            try(var db=h.store.open()){
                PlatformFoundationTest.bind(db);
                // Delayed scan delivery can carry a fixed observation timestamped before verification.
                var previous=VulnerabilityResponse.latest(db,finding);
                delayed.put("observed_at",Instant.parse(previous.get("observed_at").toString()).plusNanos(1).toString());
            }
            VulnerabilityResponseAcceptance.scan(h,scanner,team,delayed);
            var delayedReview=map("operation","assess","control_id",control,"revision",7);
            assertEquals("open",object(object(send(h,"security",incident,delayedReview).get("outcome")).get("definition")).get("state"));
            VulnerabilityResponseAcceptance.scan(h,scanner,team,VulnerabilityResponseAcceptance.report(2,List.of(VulnerabilityResponseAcceptance.entry(asset,"CONTROL-17","fixed"))));
            assess.put("revision",8);
            assertEquals("verified",object(object(send(h,"security",incident,assess).get("outcome")).get("definition")).get("state"));
            VulnerabilityResponseAcceptance.scan(h,scanner,team,VulnerabilityResponseAcceptance.report(3,List.of(VulnerabilityResponseAcceptance.entry(asset,"CONTROL-17","present"))));
            assertEquals("open",object(objects(data(h,"security",incident).get("controls")).getFirst().get("definition")).get("state"));
            var settled=map("operation","decide","decision","approve","control_id",control,"revision",9);
            assertEquals(400,h.json("POST",api("security",incident),settled,auth("VulnAnalyst")).statusCode());
            exception.put("revision",9);send(h,"security",incident,exception);
            decision.putAll(map("decision","reject","revision",10));
            result(h.json("POST",api("security",incident),decision,auth("VulnAnalyst")));
            assess.put("revision",11);assertEquals("open",object(object(send(h,"security",incident,assess).get("outcome")).get("definition")).get("state"));
        }
    }
    static void transfer(WorkflowSupport h)throws Exception{
        var employee=EmployeeOnboardingAcceptance.setup(h);var assets=AssetCustodyAcceptance.setup(h);AssetCustodyAcceptance.receive(h,assets);
        String hr=employee[0],user=uid(h,"EmployeeAgent"),template=id(),run=id();
        var prior=object(data(h,"hr",hr).get("record"));
        send(h,"hr",hr,map("operation","schedule_change","type","transfer","effective",LocalDate.now().toString(),"return_on","none",
                "steps",List.of(map("phase","effective","summary","Review move","details","Record custody change","group_id",employee[1])),"revision",0,"record_revision",Integer.parseInt(prior.get("sys_mod_count").toString())));
        String group=UUID.randomUUID().toString(),member=UUID.randomUUID().toString(),inverse=id(),reversal=id();
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
        provider.settings.put("CONNECTOR_WRITES_ENABLED","true");
        var assigned=new java.util.concurrent.atomic.AtomicBoolean();
        provider.server.createContext("/v1.0/groups/",e->{
            String verb=e.getRequestMethod(),path=e.getRequestURI().getPath();
            int status;Map<String,Object> payload=map();
            if(verb.equals("GET")){
                boolean parent=path.equals("/v1.0/groups/"+group);
                status=parent || assigned.get()?200:404;payload=map("id",parent?group:member);
            }else{assigned.set(verb.equals("POST"));status=204;}
            byte[] bytes=JSON.writeValueAsBytes(payload);e.sendResponseHeaders(status,status==204?-1:bytes.length);
            if(status!=204)e.getResponseBody().write(bytes);e.close();
        });
        post(h,"studio",map("operation","identity","user_id",user,"manager_id",uid(h,"admin"),"directory_id",member,"revision",0));
        var approval=map("stage",1,"kind","approval","title","Approve move");
        var grant=map("stage",2,"kind","entra_add","title","Grant access","target",group,"alias",provider.alias,"oauth","alias");
        post(h,"studio",map("operation","publish","template_id",template,"title","Employee transfer","days",3,"steps",List.of(approval,grant),"revision",0));
        var revoke=new LinkedHashMap<>(grant);revoke.put("kind","entra_remove");
        post(h,"studio",map("operation","publish","template_id",inverse,"title","Reverse access","days",3,"steps",List.of(approval,revoke),"revision",0));
        var proposal=map("operation","propose_transfer","template_id",template,"new_id",run,"revision",1,"asset_id",assets[0],"department","Product");
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            var profile=EmployeeServices.employee(db,user);
            var same=new LinkedHashMap<>(proposal);same.put("department",profile.get("department"));
            assertThrows(Failure.class,()->TransferFulfillment.apply(db,hr,"propose_transfer",same));
            db.update("UPDATE os_employee SET active=0 WHERE user_id=?",user);
            assertThrows(Failure.class,()->TransferFulfillment.apply(db,hr,"propose_transfer",proposal));
            db.connection.rollback();
            var job=EnterpriseData.get(db,"employment-change",hr);var changed=object(job.get("definition"));changed.put("effective",LocalDate.now().plusDays(1).toString());
            EnterpriseData.save(db,"employment-change",hr,job.get("revision"),changed);
            assertThrows(Failure.class,()->WorkflowStudio.employment(db,hr,"provision",proposal));
        }
        send(h,"hr",hr,map("operation","propose_transfer","template_id",template,"new_id",run,"revision",1,"asset_id",assets[0],"department","Product"));
        assertEquals(1,object(send(h,"hr",hr,map("operation","systems","run_id",run)).get("outcome")).get("revision"));
        var apply=map("operation","apply_transfer","run_id",run,"department","Product","asset_id",assets[0],"revision",0,"note","Transfer to Product","employee_revision",1,"movement_revision",1);
        bad(h,"hr",hr,apply,400);
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            var lost=EnterpriseData.get(db,"hr-system-run",run);var orphan=object(lost.get("definition"));orphan.put("case",id());
            EnterpriseData.save(db,"hr-system-run",run,lost.get("revision"),orphan);
            assertThrows(Failure.class,()->WorkflowStudio.current(db,run,JourneyForms.definition(db,"workflow-run",run)));db.connection.rollback();
            var job=EnterpriseData.get(db,"employment-change",hr);var changed=object(job.get("definition"));changed.put("type","leave");
            EnterpriseData.save(db,"employment-change",hr,job.get("revision"),changed);
            assertThrows(Failure.class,()->TransferFulfillment.apply(db,hr,"apply_transfer",apply));
            changed.putAll(map("type","transfer","effective",LocalDate.now().plusDays(1).toString()));
            EnterpriseData.save(db,"employment-change",hr,EnterpriseData.get(db,"employment-change",hr).get("revision"),changed);
            assertThrows(Failure.class,()->TransferFulfillment.apply(db,hr,"apply_transfer",apply));
            assertThrows(Failure.class,()->WorkflowStudio.current(db,run,JourneyForms.definition(db,"workflow-run",run)));
        }
        post(h,"studio",map("operation","advance","run_id",run,"step",1,"revision",1,"note","Approved"));
        post(h,"studio",TenConnectorAcceptance.call("act",run,2,2));assertTrue(assigned.get());
        var wrong=new LinkedHashMap<>(apply);wrong.put("department","Finance");bad(h,"hr",hr,wrong,400);
        wrong.put("asset_id",id());bad(h,"hr",hr,wrong,400);
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            var saved=EnterpriseData.get(db,"workflow-run",run);var state=object(saved.get("definition"));
            state.put("steps",List.of(revoke));EnterpriseData.save(db,"workflow-run",run,saved.get("revision"),state);
            assertThrows(Failure.class,()->TransferFulfillment.apply(db,hr,"apply_transfer",apply));db.connection.rollback();
            var binding=JourneyForms.definition(db,"hr-system-run",run);
            for(var change:List.of(map("case",id()),map("phase","reversal"))){
                var historical=new LinkedHashMap<>(binding);historical.putAll(change);
                EnterpriseData.save(db,"hr-system-run",run,1,historical);
                assertThrows(Failure.class,()->TransferFulfillment.apply(db,hr,"apply_transfer",apply));db.connection.rollback();
            }
        }
        var effect=object(object(send(h,"hr",hr,apply).get("outcome")).get("definition"));
        assertEquals("applied",effect.get("state"));
        bad(h,"hr",hr,apply,400);
        var undo=map("operation","undo_transfer","note","Return issued equipment","revision",1);
        bad(h,"hr",hr,undo,400);
        var reverse=map("operation","reverse","run_id",run,"template_id",inverse,"new_id",reversal,"revision",1);
        bad(h,"hr",hr,reverse,400);
        send(h,"hr",hr,map("operation","cancel","confirm",true,"revision",0,"record_revision",Integer.parseInt(object(data(h,"hr",hr).get("record")).get("sys_mod_count").toString()),"reason","Transfer withdrawn","completed_actions",map()));
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            assertThrows(Failure.class,()->WorkflowStudio.current(db,run,JourneyForms.definition(db,"workflow-run",run)));
            db.update("DELETE FROM os_enterprise_document WHERE area='transfer-effects' AND subject=?",hr);
            assertThrows(Failure.class,()->TransferFulfillment.apply(db,hr,"apply_transfer",apply));
        }
        send(h,"hr",hr,undo);
        bad(h,"hr",hr,undo,400);
        var wrongInverse=new LinkedHashMap<>(reverse);wrongInverse.put("template_id",template);bad(h,"hr",hr,wrongInverse,400);
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            var row=EnterpriseData.get(db,"workflow-run",run);var saved=object(row.get("definition"));
            for(var change:List.of(map("case",id()),map("phase","reversal"))){
                var provenance=JourneyForms.definition(db,"hr-system-run",run);provenance.putAll(change);
                EnterpriseData.save(db,"hr-system-run",run,1,provenance);
                assertThrows(Failure.class,()->WorkflowStudio.employment(db,hr,"reverse",reverse));db.connection.rollback();
            }
            var original=objects(saved.get("steps"));
            var completed=original.getLast();
            var pending=TenRecoveryAcceptance.copy(completed);pending.put("state","unverified");
            saved.put("steps",List.of(pending,original.getFirst()));
            EnterpriseData.save(db,"workflow-run",run,row.get("revision"),saved);
            assertThrows(Failure.class,()->WorkflowStudio.employment(db,hr,"reverse",reverse));db.connection.rollback();
            var previouslyPresent=TenRecoveryAcceptance.copy(completed);previouslyPresent.put("changed",false);
            var released=TenRecoveryAcceptance.copy(completed);released.put("kind","entra_remove");
            saved.put("steps",List.of(original.getFirst(),pending,previouslyPresent,completed,released));
            EnterpriseData.save(db,"workflow-run",run,row.get("revision"),saved);
            String manual=id();WorkflowStudio.publish(db,map("template_id",manual,"revision",0,"steps",List.of(approval),"days",1,"title","Confirm reconciliation"));
            var noNetChange=new LinkedHashMap<>(reverse);noNetChange.put("template_id",manual);
            assertEquals(reversal,object(WorkflowStudio.employment(db,hr,"reverse",noNetChange)).get("run_id"));
        }
        send(h,"hr",hr,reverse);
        post(h,"studio",map("operation","advance","run_id",reversal,"step",1,"revision",1,"note","Reversal approved"));
        post(h,"studio",TenConnectorAcceptance.call("act",reversal,2,2));assertFalse(assigned.get());
        try(var db=h.store.open()){
            assertEquals(effect.get("prior_department"),EmployeeServices.employee(db,user).get("department"));
            assertEquals("in_stock",AssetCustody.latest(db,assets[0]).get("state"));
        }
        }
    }
    static void repair(WorkflowSupport h)throws Exception{
        var customers=CustomerCasesAcceptance.setup(h);var assets=AssetCustodyAcceptance.setup(h);
        String entitlement=CustomerCasesAcceptance.entitlement(h,customers[0],2);
        String parent=CustomerCasesAcceptance.create(h,entitlement,CustomerCasesAcceptance.body(h,entitlement,"CustomerOne"),"CustomerOne").get("sys_id").toString();
        String user=uid(h,"CustomerAgent"),task;
        ChangeSchedulingAcceptance.calendar(h,"FieldDay","UTC","08:00","18:00",List.of(1,2,3,4,5),List.of());
        FieldDispatchAcceptance.available(h,"CustomerAgent",true);
        result(h.json("PATCH","/api/now/table/customer_case/"+parent,map("state","working"),auth("admin")));
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            var rec=CustomerResolutions.record(db,parent);
            WorkTasks.command(db,"customer_case",parent,map("operation","configure","revision",0,"record_revision",rec.get("revision"),"close_at","closed"));
            var newTask=WorkTasks.command(db,"customer_case",parent,map("operation","create","revision",1,"record_revision",CustomerResolutions.record(db,parent).get("revision"),"summary","Customer repair","details","Repair installed asset","group_id",customers[2],"required",false,"predecessors",List.of()));
            task=objects(newTask.get("tasks")).getFirst().get("task_id").toString();
            FieldVisits.command(db,"customer_case",parent,task,map("operation","configure","record_revision",CustomerResolutions.record(db,parent).get("revision"),"task_revision",WorkTasks.task(db,parent,task).get("revision"),"revision",0,"checklist",List.of("Verify installation"),"evidence_required",false));
            FieldDispatch.command(db,"customer_case",parent,task,map("operation","assign","record_revision",CustomerResolutions.record(db,parent).get("revision"),"task_revision",WorkTasks.task(db,parent,task).get("revision"),"revision",0,"technician_id",user,"start","2030-01-07T09:00:00Z","end","2030-01-07T10:00:00Z","location","Site A"));
            db.viewerId=user;db.viewerRole="agent";
            FieldDispatch.command(db,"customer_case",parent,task,map("operation","accept","record_revision",CustomerResolutions.record(db,parent).get("revision"),"task_revision",WorkTasks.task(db,parent,task).get("revision"),"revision",1,"reason","On the way"));
            db.connection.commit();
        }
        var repair=map("operation","repair","case_id",parent,"asset_id",assets[0],"crew",List.of(user),"revision",0);
        bad(h,"field",task,repair,400);
        send(h,"field",task,map("operation","installed_asset","asset_id",assets[0],"revision",0));
        send(h,"field",task,repair);
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            var assigned=FieldDispatch.current(db,task);
            long start=((Number)assigned.get("starts")).longValue(),end=((Number)assigned.get("ends")).longValue();
            assertThrows(Failure.class,()->CustomerRepairs.availability(db,id(),user,start,end));
            CustomerRepairs.availability(db,id(),RecordAccess.owner(db,"CustomerOutsider"),start,end);
            var old=EnterpriseData.get(db,"customer-repair",task);var booking=object(old.get("definition"));
            booking.put("lead",id());EnterpriseData.save(db,"customer-repair",task,old.get("revision"),booking);
            CustomerRepairs.availability(db,id(),user,start,end);db.connection.rollback();
            // A dispatcher's crew booking can precede the lead's acceptance.
            db.update("UPDATE os_field_dispatch SET status='assigned' WHERE task_id=?",task);
            var amendment=new LinkedHashMap<>(repair);amendment.put("revision",1);
            CustomerRepairs.command(db,task,"repair",amendment);
        }
        var note=map("operation","handoff","note","Installation tested on site","revision",1);
        bad(h,"field",task,note,400);
        String replacement=AssetCustodyAcceptance.create(h,"VISIT-PART"),secondDepot=h.add("stockroom").get("sys_id").toString();
        AssetCustodyAcceptance.receive(h,new String[]{assets[0],secondDepot,""});
        var priorCustody=AssetCustodyAcceptance.body(h,assets[0],"issue");priorCustody.put("username","CustomerOne");AssetCustodyAcceptance.save(h,assets[0],priorCustody);
        AssetCustodyAcceptance.receive(h,new String[]{replacement,assets[1],assets[2]});
        var reserve=map("task_id",task,"asset_id",replacement,"stockroom_id",assets[1],"revision",0);
        var suggestions=EnterpriseExpansionAcceptance.cmd(h,"admin","routing","source_parts","task_id",task,"model","Field laptop","entries",List.of(assets[1],secondDepot),"start",java.time.Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),"minutes",60,"days",2);
        assertEquals(1,((List<?>)suggestions.get("stock")).size());
        EnterpriseExpansionAcceptance.cmd(h,"admin","routing","reserve_serial","task_id",task,"asset_id",replacement,"stockroom_id",assets[1],"revision",0);
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            assertThrows(Failure.class,()->DepotTransfers.source(db,map("task_id",task,"model","Field laptop","entries",List.of())));
            var shortage=object(DepotTransfers.source(db,map("task_id",task,"model","Field laptop","entries",List.of(assets[1],secondDepot),"start",java.time.Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),"minutes",60,"days",2)));
            assertEquals(true,shortage.get("shortage"));assertEquals(parent,shortage.get("case_id"));assertTrue(object(shortage.get("alternatives")).containsKey("appointments"));
            assertThrows(Failure.class,()->DepotTransfers.command(db,"reserve_serial",reserve));
            DepotTransfers.command(db,"release_serial",map("task_id",task,"asset_id",replacement,"revision",1));
            reserve.put("revision",2);
            DepotTransfers.command(db,"reserve_serial",reserve);
            DepotTransfers.command(db,"pickup_serial",map("task_id",task,"asset_id",replacement,"revision",3));
            assertEquals(user,AssetCustody.latest(db,replacement).get("assignee_id"));
            db.connection.commit();
        }
        var phone=h.get("/field-phone/"+task,auth("CustomerAgent"));assertEquals(200,phone.statusCode(),html(phone));
        String markup=html(phone);
        assertTrue(markup.contains("Save completed worksheet"));
        String ticket=markup.split("id=\"packet\">")[1].split("</textarea>")[0];
        byte[] report=(ticket+"\r\nminutes,45\r\ninstalled_serial,\r\ninstalled_serial,"+replacement+"\r\nevidence_note,Repaired assembly\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var db=h.store.open()){
            db.viewerId=user;db.viewerRole="agent";
            var synced=OfflineWorksheet.sync(h.store,db,task,report,"",new byte[0]);
            assertEquals(synced,OfflineWorksheet.sync(h.store,db,task,report,"",new byte[0]));
            assertEquals(4,((Number)AssetCustody.latest(db,replacement).get("revision")).intValue());
            assertEquals(customers[0],JourneyForms.definition(db,"installation-account",replacement).get("account"));
            OfflineWorksheet.action(db,"customer_case",parent,task,"complete",map("notes","Tested on site","answers",List.of(1),"parts_reviewed",true,"evidence",List.of()));
            db.connection.commit();
        }
        try(var db=h.store.open()){
            db.viewerId=user;db.viewerRole="agent";assertEquals(45,OfflineWorksheet.sync(h.store,db,task,report,"",new byte[0]).get("minutes"));
            DepotTransfers.command(db,"return_serial",map("task_id",task,"asset_id",assets[0],"stockroom_id",secondDepot));
            assertEquals("in_stock",AssetCustody.latest(db,assets[0]).get("state"));
            assertEquals(RecordAccess.owner(db,"CustomerOne"),AssetCustody.latest(db,replacement).get("assignee_id"));
            db.connection.commit();
            db.update("UPDATE os_field_dispatch SET technician_id=?,revision=revision+1 WHERE task_id=?",RecordAccess.owner(db,"CustomerOutsider"),task);
            assertEquals("needs_review",OfflineWorksheet.sync(h.store,db,task,report,"",new byte[0]).get("status"));
        }
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            for(var mismatch:List.of(map("lead",id()),map("accepted_revision",0))){
                var repairRow=EnterpriseData.get(db,"customer-repair",task);var reservation=object(repairRow.get("definition"));
                reservation.putAll(mismatch);EnterpriseData.save(db,"customer-repair",task,repairRow.get("revision"),reservation);
                assertThrows(Failure.class,()->CustomerRepairs.command(db,task,"handoff",note));db.connection.rollback();
            }
            var receiptRow=EnterpriseData.get(db,"offline-task",task);var prior=object(receiptRow.get("definition"));prior.put("technician",id());
            EnterpriseData.save(db,"offline-task",task,receiptRow.get("revision"),prior);
            assertThrows(Failure.class,()->CustomerRepairs.command(db,task,"handoff",note));db.connection.rollback();
            db.update("UPDATE os_field_dispatch SET revision=revision+2 WHERE task_id=?",task);
            assertThrows(Failure.class,()->CustomerRepairs.command(db,task,"handoff",note));
        }
        assertEquals(replacement,object(object(send(h,"field",task,note).get("outcome")).get("definition")).get("replacement_asset"));
        bad(h,"field",task,note,400);
        var accepted=CustomerResolutionsAcceptance.command(h,parent,"accept","CustomerOne");
        assertEquals("closed",object(accepted.get("record")).get("state"));
    }
}
