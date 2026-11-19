package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.time.LocalDate;

final class TransferFulfillment {
    private TransferFulfillment(){}
    static Object apply(Store.Db db,String hr,String operation,Map<String,Object> body)throws Exception{
        admin(db);
        var record=EmployeeCases.record(db,hr,false);
        String employee=db.one("SELECT user_id FROM os_hr_case WHERE record_id=?",hr).get("user_id").toString();
        var profile=EmployeeServices.employee(db,employee);
        require(((Number)profile.get("active")).intValue()==1,"Active employee required");
        String username=AgentPlans.account(db,employee).get("name").toString();
        if(operation.equals("propose_transfer")){
            String asset=id(body,"asset_id");
            var movement=AssetCustody.latest(db,asset);
            require("in_stock".equals(movement.get("state")),"Choose available equipment");
            String department=text(body,"department",80);
            require(!department.equals(profile.get("department")),"Choose a new department");
            var policy=definition(db,"workflow-template",id(body,"template_id"));
            require(objects(policy.get("steps")).stream().anyMatch(s->"approval".equals(s.get("kind"))),"Manager approval required for a transfer");
            require("transfer".equals(definition(db,"employment-change",hr).get("type")),"Choose a scheduled transfer");
            var request=WorkflowStudio.employment(db,hr,"provision",body);
            save(db,"transfer-terms",id(body,"new_id"),0,map("asset",asset,"department",department,"prior_department",profile.get("department"),
                    "employee_revision",profile.get("revision"),"movement_revision",movement.get("revision")));
            return request;
        }
        var before=get(db,"transfer-effects",hr);
        var effect=object(before.get("definition"));
        boolean forward=operation.equals("apply_transfer");
        String asset;
        String department;
        if(forward){
            require(effect.isEmpty() && EmployeeOnboarding.open(record),"Open transfer without previous fulfillment required");
            var change=definition(db,"employment-change",hr);
            require("transfer".equals(change.get("type")) && !LocalDate.now().isBefore(LocalDate.parse(change.get("effective").toString())),"Choose an effective transfer");
            String run=id(body,"run_id");
            var binding=definition(db,"hr-system-run",run);
            var workflow=definition(db,"workflow-run",run);
            require(hr.equals(binding.get("case")) && "effective".equals(binding.get("phase")) && "complete".equals(workflow.get("state"))
                    && objects(workflow.get("steps")).stream().anyMatch(s->"approval".equals(s.get("kind"))),"Complete manager-reviewed provisioning first");
            VerifiedActions.validateApprovals(db,workflow);
            var terms=definition(db,"transfer-terms",run);
            require(terms.get("asset").equals(body.get("asset_id")) && terms.get("department").equals(body.get("department")),"Apply the exact equipment and department approved by the manager");
            ProblemInvestigations.revision(terms.get("employee_revision"),profile.get("revision"));
            ProblemInvestigations.revision(terms.get("movement_revision"),AssetCustody.latest(db,id(body,"asset_id")).get("revision"));
            department=text(body,"department",80);
            asset=id(body,"asset_id");
            effect.putAll(map("run",run,"asset",asset,"employee",employee,"prior_department",profile.get("department"),"department",department,"stockroom",AssetCustody.latest(db,asset).get("stockroom_id")));
            ProblemInvestigations.revision(body.get("employee_revision"),profile.get("revision"));
            ProblemInvestigations.revision(body.get("movement_revision"),AssetCustody.latest(db,asset).get("revision"));
        }else{
            require("cancelled".equals(object(record.get("data")).get("state")) && "applied".equals(effect.get("state")),"Cancel the applied transfer before reversal");
            department=effect.get("prior_department").toString();
            asset=effect.get("asset").toString();
            ProblemInvestigations.revision(effect.get("employee_revision"),profile.get("revision"));
            ProblemInvestigations.revision(effect.get("movement_revision"),AssetCustody.latest(db,asset).get("revision"));
            require(employee.equals(AssetCustody.latest(db,asset).get("assignee_id")),"Equipment custody changed after transfer");
        }
        var movement=map("operation",forward?"issue":"return","revision",AssetCustody.latest(db,asset).get("revision"),"record_revision",AssetCustody.record(db,asset).get("revision"),"reason",text(body,"note",500));
        if(forward)movement.put("username",username);
        else movement.put("stockroom_id",effect.get("stockroom"));
        AssetCustody.save(db,asset,movement);
        EmployeeServices.save(db,map("operation","employee","username",username,"department",department,"active",true,"revision",profile.get("revision")));
        effect.putAll(map("state",forward?"applied":"reversed","employee_revision",EmployeeServices.employee(db,employee).get("revision"),
                "movement_revision",AssetCustody.latest(db,asset).get("revision"),"updated",now(),"actor",db.viewerId));
        save(db,"transfer-effects",hr,body.get("revision"),effect);
        return get(db,"transfer-effects",hr);
    }
}
