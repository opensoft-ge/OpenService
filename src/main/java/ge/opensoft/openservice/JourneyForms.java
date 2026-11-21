package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;

/** Operation descriptors drive both HTML forms and the exact accepted API fields. */
final class JourneyForms {
    private JourneyForms() {}
    record Operation(String area,String name,String title,String fields) {
        List<String> names(){return fields.isEmpty()?List.of():List.of(fields.split(" "));}
        Map<String,Object> form(){
            var controls=new ArrayList<Map<String,Object>>();
            for(String field:names()){
                String type="text";
                Object initial="";
                if(Set.of("target_bps","revision","sequence","usage_revision","employee_revision","movement_revision","draft_revision","stage","days","step","quantity","minutes","months","discount","limit","hours","release_revision","package_revision","capacity","rto_minutes","rpo_minutes","impact_score","likelihood").contains(field)){type="number";initial=0;}
                if(Set.of("skills","steps","resources","services","alerts","applications","options","crew","observations","cases","people","crews","jobs","entries","travel","controls","samples","fields").contains(field)){type="json";initial="[]";}
                if(Set.of("manifest","bindings").contains(field)){type="json";initial="{}";}
                if(Set.of("confirm","active").contains(field)){type="boolean";initial=false;}
                if(field.equals("new_id") || name.equals("join_desk") && field.equals("visit_id")) initial=Values.id();
                if(name.equals("join_desk") && field.equals("start")) initial="now";
                if(name.equals("join_desk") && field.equals("mode")) initial="walk_in";
                if(name.equals("exception_design")&&field.equals("bindings"))initial="{\"low\":\"\",\"medium\":\"\",\"high\":\"\"}";
                if(name.equals("exception_requisition")&&field.equals("manifest"))initial="{\"Laptop\":{\"quantity\":1,\"unit_cost\":\"900.00\",\"months\":36,\"salvage\":\"0.00\"}}";
                if(name.equals("business_tolerance")&&field.equals("manifest"))initial="{\"applications\":[],\"suppliers\":[],\"people\":[],\"sites\":[],\"fallback\":\"\",\"maximum_minutes\":60,\"maximum_backlog\":10}";
                if(name.equals("license_fact")&&field.equals("manifest"))initial="{\"kind\":\"rights\",\"contract\":\"\",\"covers\":[],\"quantity\":1}";
                controls.add(EnterpriseData.field(field,field.replace('_',' '),type,initial));
            }
            return map("operation",name,"title",title,"fields",controls);
        }
    }
    static final List<Operation> ALL=List.of(
        new Operation("studio","preview_benchmark","Preview five independent planner evaluation cases","new_id"),
        new Operation("studio","run_benchmark","Run the reviewed planner evaluation","new_id preview_token confirm"),
        new Operation("studio","identity","Bind employee, manager and directory identity","user_id manager_id directory_id revision"),
        new Operation("studio","append_approval","Add a manager approval to a draft","template_id stage title draft_revision"),
        new Operation("studio","append_team","Add a team task or team approval to a draft","template_id kind group stage title draft_revision"),
        new Operation("studio","append_directory","Add a directory membership action to a draft","template_id kind target alias oauth stage title draft_revision"),
        new Operation("studio","append_issue","Add a Jira label action to a draft","template_id issue label alias oauth stage title draft_revision"),
        new Operation("studio","remove_step","Remove a step from a draft","template_id step draft_revision"),
        new Operation("studio","publish_draft","Publish the reviewed workflow draft","template_id title days revision draft_revision"),
        new Operation("studio","publish","Publish a workflow version","template_id title days steps revision"),
        new Operation("studio","request","Start a published workflow","template_id new_id revision"),
        new Operation("studio","inspect","Open a workflow run","run_id"),
        new Operation("studio","advance","Respond, approve or finish one stage task","run_id step revision note"),
        new Operation("studio","stop_run","Stop remaining workflow steps","run_id revision note"),
        new Operation("studio","preview_actions","Review the payload for model planning","run_id revision goal"),
        new Operation("studio","draft_actions","Draft a multi-system action plan","run_id revision goal preview_token confirm"),
        new Operation("studio","delegate_run","Approve an API agent for this exact workflow run","run_id delegate_id revision confirm"),
        new Operation("studio","queue_run","Queue the approved external actions for execution","run_id revision confirm"),
        new Operation("studio","act","Execute a reviewed connector step","run_id step revision confirm"),
        new Operation("studio","recheck","Verify an uncertain destination outcome","run_id step revision"),
        new Operation("studio","rearm","Accept a verified result or authorize a retry","run_id step revision confirm"),
        new Operation("studio","evaluate_run","Evaluate plan authority, stop and expected effects","run_id"),
        new Operation("topology","inventory","Reconcile a complete inventory snapshot","source sequence resources services"),
        new Operation("topology","overlay","Set a manual resource label","source resource_id title revision"),
        new Operation("topology","impact","Show services using a resource","source resource_id"),
        new Operation("topology","signals","Correlate monitor observations","source resource_id alerts"),
        new Operation("topology","declare","Declare a monitored major incident and assign command","source resource_id commander note revision"),
        new Operation("topology","recover","Verify restoration and record a review","source resource_id change_id note revision"),
        new Operation("assets","accept_price","Approve an invoiced price variance","invoice_id model unit_cost note"),
        new Operation("assets","settle","Reconcile and export eligible invoice lines","invoice_id revision"),
        new Operation("assets","seat_usage","Import dated publisher subscriber activity","product source as_of observations revision"),
        new Operation("assets","reclaim","Review unused named-user subscriptions","product"),
        new Operation("assets","release_unused","Reclaim an unused subscription","product license_id user_id usage_revision revision confirm"),
        new Operation("hr","propose_transfer","Send exact department and equipment changes for manager approval","template_id new_id department asset_id revision"),
        new Operation("hr","apply_transfer","Apply the department and equipment transfer","run_id department asset_id employee_revision movement_revision note revision"),
        new Operation("hr","undo_transfer","Restore the prior department and equipment custody","note revision"),
        new Operation("hr","provision","Attach an approved workflow to this employee change","template_id new_id revision"),
        new Operation("hr","reverse","Request compensating work after cancellation","run_id template_id new_id revision"),
        new Operation("hr","systems","Review downstream provisioning and acknowledgements","run_id"),
        new Operation("field","installed_asset","Register an installed asset for this case account","asset_id revision"),
        new Operation("field","repair","Link an entitled customer case and reserve a repair crew","case_id asset_id crew revision"),
        new Operation("field","handoff","Send a synchronized visit outcome for customer review","note revision"),
        new Operation("security","control","Bind a control and a native vulnerability finding","finding_id control_id owner_id revision"),
        new Operation("security","exception","Request a dated control exception","control_id expires_on note revision"),
        new Operation("security","decide","Review a control exception","control_id decision revision"),
        new Operation("security","assess","Evaluate closure against new scan evidence","control_id revision"),
        new Operation("architecture","application","Register an application investment","application_id title capability owner_id annual_cost currency revision"),
        new Operation("architecture","dependency","Set required applications","application_id applications revision"),
        new Operation("architecture","compare_plans","Compare two retirement plans against staffed funding scenarios","new_id project_id scenario_id options months"),
        new Operation("architecture","approve_plan","Approve a retirement option","plan_id option revision"),
        new Operation("architecture","retire","Record completed application retirement","plan_id application_id note revision"),
        new Operation("architecture","realized","Record realized savings against the baseline","plan_id amount reference revision")
    );
    static final Set<String> GLOBAL=Set.of("studio","architecture","topology");
    static Map<String,Object> definition(Store.Db db,String area,String key)throws Exception {
        var data=object(get(db,area,key).get("definition"));
        require(!data.isEmpty(),"Record is unavailable");
        return data;
    }
    static void admin(Store.Db db)throws Exception{Service.requireAccountAdministrator(db,db.viewerId);}
    static String text(Map<String,Object> body,String key,int size){return WorkJobs.text(body.get(key),size);}
    static String id(Map<String,Object> body,String key){return EnterpriseData.id(body.get(key));}
    static Map<String,Object> global(Store.Db db,String area,String subject)throws Exception {
        require(subject.equals("global"),"Choose the shared workspace");
        if(area.equals("studio")) return EnterpriseData.page("Workflow studio","Publish approval and work stages, request an offering, and review verified execution.",WorkflowStudio.directory(db));
        admin(db);
        if(area.equals("architecture")) return EnterpriseData.page("Application investments","Compare retirement decisions and track their financial outcomes.",map("applications",list(db,"business-app"),"plans",list(db,"retire-plan")));
        return EnterpriseData.page("Service topology and incidents","Inspect discovered dependencies, affected services and correlated monitoring episodes.",map("inventory",list(db,"service-map"),"incidents",list(db,"service-episode")));
    }
    static Map<String,Object> decorate(Store.Db db,String area,Map<String,Object> page){
        var forms=new ArrayList<>(objects(page.get("forms")));
        for(var op:ALL) if(op.area.equals(area)) {
            if((op.name.startsWith("append_") || Set.of("publish_draft","remove_step","identity","publish","delegate_run","queue_run","preview_actions","draft_actions","preview_benchmark","run_benchmark","evaluate_run","rearm").contains(op.name)) && !"admin".equals(db.viewerRole)) continue;
            forms.add(op.form());
        }
        page.put("forms",forms);
        return page;
    }
    static Object execute(Store store,Store.Db db,String subject,Operation op,Map<String,Object> body)throws Exception {
        lock(db);
        var fields=new HashSet<>(op.names());fields.add("operation");
        require(fields.equals(body.keySet()),"Provide exactly the fields shown for this operation");
        return switch(op.area){
            case "studio" -> WorkflowStudio.command(store,db,op.name,body);
            case "topology" -> CustomerImpact.topology(store,db,op.name,body);
            case "assets" -> InvoiceSettlement.command(db,op.name,body);
            case "hr" -> Set.of("propose_transfer","apply_transfer","undo_transfer").contains(op.name)?TransferFulfillment.apply(db,subject,op.name,body):WorkflowStudio.employment(db,subject,op.name,body);
            case "field" -> CustomerRepairs.command(db,subject,op.name,body);
            case "security" -> ControlAssurance.command(db,subject,op.name,body);
            default -> ApplicationInvestments.command(db,op.name,body);
        };
    }
}
