package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Explicit employee enrollment and configurable HR service audiences. */
final class EmployeeServices {
    private EmployeeServices() {}

    static Map<String,Object> employee(Store.Db db,String user)throws SQLException {
        var row=db.one("SELECT * FROM os_employee WHERE user_id=?",user);
        return row==null?map("user_id",user,"department","","active",0,"revision",0):row;
    }

    static Map<String,Object> currentEmployee(Store.Db db)throws SQLException {
        var current=employee(db,db.viewerId);
        if(((Number)current.get("active")).intValue()!=1 || db.one("SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND password_reset_required=0",db.viewerId)==null)throw new Failure(403,"Current employee enrollment required");
        return current;
    }

    static Map<String,Object> service(Store.Db db,String id)throws SQLException {
        var row=db.one("SELECT * FROM os_hr_service WHERE service_id=?",id);
        if(row==null)throw new Failure(404,"Employee service not found");return row;
    }

    static Map<String,Object> eligible(Store.Db db,String id)throws SQLException {
        var employee=currentEmployee(db);var service=service(db,id);
        if(((Number)service.get("active")).intValue()!=1 || (!service.get("department").equals("") && !service.get("department").equals(employee.get("department"))))throw new Failure(403,"This service is not currently available to you");
        var visible=new LinkedHashMap<>(service);visible.keySet().retainAll(Set.of("service_id","name","description","revision"));return map("employee_revision",employee.get("revision"),"service",visible);
    }

    static Map<String,Object> administration(Store.Db db,String username)throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);Map<String,Object> found=null;
        if(!username.isEmpty()){
            var user=db.one("SELECT user_id,name FROM os_user WHERE name=? AND active=1 AND password_reset_required=0",WorkJobs.text(username,80));
            if(user==null)throw new Failure(404,"Active usable account not found");
            found=employee(db,user.get("user_id").toString());found.put("username",user.get("name"));
        }
        return map("lookup",found,"employees",db.all("SELECT e.*,u.name FROM os_employee e JOIN os_user u ON u.user_id=e.user_id ORDER BY u.name LIMIT 200"),"services",db.all("SELECT * FROM os_hr_service ORDER BY name,service_id LIMIT 200"),"groups",Service.page(db,"sys_user_group",Map.of("sysparm_limit","100"),"").rows());
    }

    static Map<String,Object> save(Store.Db db,Object input)throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var body=object(input);String operation=WorkJobs.text(body.get("operation"),8);boolean employee=operation.equals("employee");
        require(employee || operation.equals("service"),"Choose employee or service");
        require(body.keySet().equals(employee?Set.of("operation","username","department","active","revision"):Set.of("operation","service_id","name","description","department","group_id","active","revision")),"Provide only the selected employee service fields");
        require(body.get("active") instanceof Boolean,"Choose active or inactive");String department=Objects.toString(body.get("department"),"").trim();require(body.get("department") instanceof String && department.length()<=80 && (!employee || !department.isEmpty()),"Provide a department of at most 80 characters; an empty service audience means all employees");
        String subject;
        if(employee){
            String username=WorkJobs.text(body.get("username"),80);var user=db.one("SELECT user_id FROM os_user WHERE name=? AND active=1 AND password_reset_required=0",username);require(user!=null,"Choose an active usable account");subject=user.get("user_id").toString();var current=employee(db,subject);ProblemInvestigations.revision(body.get("revision"),current.get("revision"));
            require(db.count("SELECT COUNT(*) FROM os_employee WHERE user_id<>?",subject)<200,"At most 200 employee profiles are supported");
            db.update("INSERT INTO os_employee(user_id,department,active,revision) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET department=excluded.department,active=excluded.active,revision=excluded.revision",subject,department,(Boolean)body.get("active")?1:0,((Number)current.get("revision")).intValue()+1);
        }else{
            require(body.get("service_id") instanceof String,"Provide an existing service identifier or empty for new");String id=body.get("service_id").toString(),name=WorkJobs.text(body.get("name"),160),description=WorkJobs.text(body.get("description"),4000),group=WorkJobs.text(body.get("group_id"),32);Service.readRecord(db,"sys_user_group",group);var current=id.isEmpty()?map("revision",0):service(db,id);ProblemInvestigations.revision(body.get("revision"),current.get("revision"));
            if(id.isEmpty()){require(db.count("SELECT COUNT(*) FROM os_hr_service")<200,"At most 200 employee services are supported");id=Values.id();}subject=id;
            db.update("INSERT INTO os_hr_service(service_id,name,description,department,group_id,active,revision) VALUES(?,?,?,?,?,?,?) ON CONFLICT(service_id) DO UPDATE SET name=excluded.name,description=excluded.description,department=excluded.department,group_id=excluded.group_id,active=excluded.active,revision=excluded.revision",id,name,description,department,group,(Boolean)body.get("active")?1:0,((Number)current.get("revision")).intValue()+1);
        }
        SecurityEvents.record(db,db.viewerId,db.viewerId,"employee."+operation+"_saved",map("subject",subject));return administration(db,employee?body.get("username").toString():"");
    }
}
