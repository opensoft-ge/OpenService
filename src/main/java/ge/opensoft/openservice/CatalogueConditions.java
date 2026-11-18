package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
/** Ordered declarative question dependencies; hiding never supplies authorization. */
final class CatalogueConditions {
 private CatalogueConditions() {}
 static void validate(Map<String,Object> raw,Map<String,Object> normalized,List<Map<String,Object>> earlier){
  if(!raw.containsKey("when"))return;var when=object(raw.get("when"));require(when.keySet().equals(Set.of("key","value")),"Condition requires key and value only");String key=WorkJobs.text(when.get("key"),40),value=WorkJobs.text(when.get("value"),120);var source=earlier.stream().filter(q->q.get("key").equals(key)).findFirst().orElseThrow(()->new Failure(400,"Condition must reference an earlier question"));require(Set.of("choice","boolean").contains(source.get("type")),"Conditions use an earlier choice or yes/no answer");require(source.get("type").equals("boolean")?Set.of("true","false").contains(value):((List<?>)source.get("choices")).contains(value),"Condition value must be a supported source answer");normalized.put("when",map("key",key,"value",value));
 }
 static List<Map<String,Object>> active(List<Map<String,Object>> questions,Map<String,Object> answers){
  var active=new ArrayList<Map<String,Object>>();var keys=new HashSet<String>();for(var q:questions){var when=object(q.getOrDefault("when",map()));boolean visible=when.isEmpty() || (keys.contains(when.get("key")) && when.get("value").equals(answers.get(when.get("key"))));if(visible){active.add(q);keys.add(q.get("key").toString());}else require(Objects.toString(answers.getOrDefault(q.get("key"),""),"").isEmpty(),"Do not submit answers for inactive questions");}return active;
 }
 static void form(Map<String,Object> form,Map<String,Object> question){
  if(!form.containsKey("condition_key"))return;require(form.get("condition_key") instanceof String && form.get("condition_value") instanceof String,"Condition source and value must be text");String key=form.get("condition_key").toString(),value=form.get("condition_value").toString();if(key.isEmpty())require(value.isEmpty(),"Leave both condition fields empty for an unconditional question");else question.put("when",map("key",key,"value",value));
 }
}
