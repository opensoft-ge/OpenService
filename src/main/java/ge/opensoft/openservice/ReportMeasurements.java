package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import org.apache.commons.codec.digest.DigestUtils;
/** Explicit numeric captures; current administrator authority gates all retained measurements. */
final class ReportMeasurements {
    private ReportMeasurements() {}
    static void cleanup(Store.Db db)throws SQLException{db.update("DELETE FROM os_report_measurement WHERE report_id NOT IN (SELECT report_id FROM os_saved_report) OR viewer_id NOT IN (SELECT user_id FROM os_user)");}
    static Map<String,Object> context(Store.Db db,String id)throws SQLException{
        Service.requireAccountAdministrator(db,db.viewerId);var report=SavedReports.find(db,db.viewerId,id);var args=new LinkedHashMap<String,String>();object(report.get("definition")).forEach((key,value)->args.put(key,value.toString()));require(args.getOrDefault("group_by","").isEmpty(),"Measurements require an ungrouped numeric report");var metrics=Analytics.report(db,report.get("table_name").toString(),args);String hash=DigestUtils.sha256Hex(JSON.writeValueAsString(map("table",report.get("table_name"),"query",new TreeMap<>(args))));return map("report",report,"definition_hash",hash,"current",objects(metrics.get("rows")).getFirst());
    }
    static Map<String,Object> capture(Store.Db db,String id,Object input)throws SQLException{
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");long started=System.nanoTime();var body=object(input);require(body.keySet().equals(Set.of("key")),"Provide the capture key only");String key=WorkJobs.text(body.get("key"),32);require(key.matches("[0-9a-f]{32}"),"Use a 32-character capture key");var context=context(db,id);String hash=context.get("definition_hash").toString();var existing=db.one("SELECT report_id,viewer_id,definition_hash FROM os_report_measurement WHERE measurement_id=?",key);
        if(existing!=null){if(!existing.get("viewer_id").equals(db.viewerId))throw new Failure(404,"Measurement not found");if(!existing.get("report_id").equals(id) || !existing.get("definition_hash").equals(hash))throw new Failure(409,"Capture key identifies another report definition");return map("measurement_id",key,"created",false);}
        var result=new LinkedHashMap<>(object(context.get("current")));result.remove("group");db.update("INSERT INTO os_report_measurement(measurement_id,report_id,viewer_id,captured_at,definition_hash,result) VALUES(?,?,?,?,?,?)",key,id,db.viewerId,System.currentTimeMillis(),hash,JSON.writeValueAsString(result));db.update("DELETE FROM os_report_measurement WHERE report_id=? AND viewer_id=? AND sequence NOT IN (SELECT sequence FROM os_report_measurement WHERE report_id=? AND viewer_id=? ORDER BY sequence DESC LIMIT 100)",id,db.viewerId,id,db.viewerId);ReportRuns.record(db,db.viewerId,object(context.get("report")),Math.max(0,(System.nanoTime()-started)/1000000));return map("measurement_id",key,"created",true);
    }
    static Map<String,Object> page(Store.Db db,String id,Map<String,String> args)throws SQLException{
        require(Set.of("metric").containsAll(args.keySet()),"Use metric only");String metric=args.getOrDefault("metric","average");require(Set.of("count","value_count","sum","min","max","average").contains(metric),"Choose a supported numeric metric");var context=context(db,id);var rows=db.all("SELECT measurement_id,captured_at,result FROM os_report_measurement WHERE report_id=? AND viewer_id=? AND definition_hash=? ORDER BY sequence",id,db.viewerId,context.get("definition_hash"));String previous=null;var history=new ArrayList<Map<String,Object>>();
        for(var row:rows){var values=object(JSON.readValue(row.get("result").toString(),Object.class));String value=(String)values.get(metric);String delta=value==null || previous==null?null:new BigDecimal(value).subtract(new BigDecimal(previous)).stripTrailingZeros().toPlainString();history.add(map("measurement_id",row.get("measurement_id"),"captured_at",Instant.ofEpochMilli(((Number)row.get("captured_at")).longValue()).toString(),"values",values,"value",value,"delta",delta));previous=value;}
        context.put("metric",metric);context.put("history",history);return context;
    }
}
