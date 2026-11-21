package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.sql.SQLException;

final class CustomerRepairs {
    private CustomerRepairs(){}
    static void availability(Store.Db db,String task,String user,long starts,long ends)throws SQLException{
        for(var other:db.all("SELECT doc.subject,doc.definition,d.starts,d.ends,d.technician_id FROM os_enterprise_document doc JOIN os_field_dispatch d ON d.task_id=doc.subject WHERE doc.area='customer-repair' AND doc.subject<>? AND d.starts<? AND d.ends>? AND d.status IN ('assigned','accepted')",task,ends,starts)){
            var reserved=object(other.get("definition"));
            if(sameAppointment(reserved,other) && ((List<?>)reserved.get("crew")).contains(user))throw new Failure(409,"Technician is reserved on another repair crew");
        }
    }
    static boolean sameAppointment(Map<String,Object> reservation,Map<String,Object> dispatch){
        return List.of(reservation.get("lead"),reservation.get("starts").toString(),reservation.get("ends").toString())
                .equals(List.of(dispatch.get("technician_id"),dispatch.get("starts").toString(),dispatch.get("ends").toString()));
    }
    static Object command(Store.Db db,String taskId,String op,Map<String,Object> b)throws Exception {
        var task=FieldPlanning.task(db,taskId);
        String parent=task.get("parent_id").toString();
        require("customer_case".equals(task.get("parent_table")),"Choose field work belonging to the customer case");
        var customer=db.one("SELECT account_id,entitlement_id FROM os_customer_case WHERE record_id=?",parent);
        var dispatch=FieldDispatch.current(db,taskId);
        if(op.equals("installed_asset")){
            admin(db);
            String asset=id(b,"asset_id");
            AssetCustody.record(db,asset);
            save(db,"installation-account",asset,b.get("revision"),map("account",customer.get("account_id"),"asset",asset,"installed_at",now()));
            return get(db,"installation-account",asset);
        }
        if(op.equals("repair")){
            admin(db);
            require(parent.equals(id(b,"case_id")),"Choose the parent customer case");
            String asset=id(b,"asset_id");
            AssetCustody.record(db,asset);
            require(customer.get("account_id").equals(definition(db,"installation-account",asset).get("account")),"Asset must be installed for this customer account");
            require(Set.of("assigned","accepted").contains(dispatch.get("status")),"Schedule the lead technician first");
            var crew=new LinkedHashSet<String>();
            for(Object value:batch(b.get("crew"),12)){
                String technician=EnterpriseData.id(value);
                require(crew.add(technician),"List each crew member once");
                FieldAvailability.eligible(db,"customer_case",parent,task,technician,((Number)dispatch.get("starts")).longValue(),((Number)dispatch.get("ends")).longValue());
            }
            require(crew.contains(dispatch.get("technician_id")),"Include the lead technician");
            save(db,"customer-repair",taskId,b.get("revision"),map("case",parent,"account",customer.get("account_id"),"entitlement",customer.get("entitlement_id"),
                    "asset",asset,"crew",crew,"starts",dispatch.get("starts"),"ends",dispatch.get("ends"),"lead",dispatch.get("technician_id"),
                    "accepted_revision",((Number)dispatch.get("revision")).intValue()+("assigned".equals(dispatch.get("status"))?1:0)));
        }else{
            var repair=definition(db,"customer-repair",taskId);
            require(!repair.containsKey("handed_off"),"Customer handoff already completed");
            var installation=definition(db,"installation-account",repair.get("asset").toString());
            if(!repair.get("account").equals(installation.get("account")))repair.put("replacement_asset",DepotTransfers.replacement(db,taskId,repair.get("account"),installation));
            var receipt=definition(db,"offline-task",taskId);
            require("complete".equals(FieldVisits.current(db,taskId).get("phase")),"Complete the field checklist and evidence review before handoff");
            require(sameAppointment(repair,dispatch) && repair.get("lead").equals(receipt.get("technician")),"Review the crew and appointment after reassignment");
            require(repair.get("accepted_revision").equals(receipt.get("dispatch_revision"))
                    && ((Number)dispatch.get("revision")).intValue()==((Number)repair.get("accepted_revision")).intValue()+1,"Use a worksheet synchronized for this assignment");
            var result=CustomerResolutions.command(db,parent,map("operation","propose","record_revision",CustomerResolutions.record(db,parent).get("revision"),
                    "revision",CustomerResolutions.current(db,parent).get("revision"),"code","solved","solution",text(b,"note",4000)));
            repair.putAll(map("handed_off",now(),"receipt",receipt.get("fingerprint"),"resolution_revision",object(result.get("resolution")).get("revision")));
            save(db,"customer-repair",taskId,b.get("revision"),repair);
        }
        return get(db,"customer-repair",taskId);
    }
}
