package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
/** Explicit contextual sources; stored references never replace current authorization. */
final class SupportSources {
 private SupportSources() {}
 static List<Map<String,Object>> references(Object input,String primary){
  require(input instanceof List<?> && ((List<?>)input).size()<=2,"Choose at most two additional sources");var refs=new ArrayList<Map<String,Object>>();for(Object value:(List<?>)input)refs.add(object(value));var seen=new HashSet<String>();seen.add("knowledge:"+primary);
  for(var ref:refs){String kind=WorkJobs.text(ref.get("kind"),20);require(Set.of("knowledge","record").contains(kind),"Choose knowledge or record context");boolean knowledge=kind.equals("knowledge");require(ref.keySet().equals(knowledge?Set.of("kind","article_id"):Set.of("kind","table","record_id")),"Provide only source identity fields");String id=WorkJobs.text(ref.get(knowledge?"article_id":"record_id"),32);require(id.matches("[0-9a-f]{32}"),"Use a valid source record ID");if(!knowledge)WorkJobs.text(ref.get("table"),80);require(seen.add(kind+":"+id),"Each source must be different");}return refs;
 }
 static Map<String,Object> resolve(Store store,Store.Db db,Map<String,Object> ref)throws Exception {
  if(ref.get("kind").equals("knowledge")){var source=new LinkedHashMap<>(object(KnowledgeQuestions.context(store,db,ref.get("article_id").toString()).get("source")));source.put("kind","knowledge");source.put("url","/knowledge/"+ref.get("article_id"));return source;}
  String table=ref.get("table").toString(),id=ref.get("record_id").toString();var record=Service.readRecord(db,table,id);var visible=FieldAccess.serialize(db,record);var allowed=Arrays.asList(store.environment.getProperty("AI_ALLOWED_FIELDS","short_description,description").split(","));var fields=map();for(String field:List.of("short_description","description"))if(allowed.contains(field) && visible.containsKey(field))fields.put(field,visible.get(field));require(!fields.isEmpty(),"No configured readable text is available for this record");return map("kind","record","table",table,"record_id",id,"revision",record.get("revision"),"fields",fields,"url","/record/"+table+"/"+id);
 }
 static List<Map<String,Object>> capture(Store store,Store.Db db,List<Map<String,Object>> refs)throws Exception {var saved=new ArrayList<Map<String,Object>>();for(var ref:refs){var source=resolve(store,db,ref);saved.add(map("reference",ref,"fingerprint",DigestUtils.sha256Hex(JSON.writeValueAsString(source))));}return saved;}
 static List<Map<String,Object>> current(Store store,Store.Db db,Object saved)throws Exception {var sources=new ArrayList<Map<String,Object>>();for(var entry:objects(saved)){var source=resolve(store,db,object(entry.get("reference")));if(!DigestUtils.sha256Hex(JSON.writeValueAsString(source)).equals(entry.get("fingerprint")))throw new Failure(409,"A contextual source changed; start a new conversation or delete this one from your list");sources.add(source);}return sources;}
 static Map<String,Object> form(Map<String,Object> form){var refs=new ArrayList<Map<String,Object>>();String article=Objects.toString(form.remove("additional_article"),""),table=Objects.toString(form.remove("context_table"),""),record=Objects.toString(form.remove("context_record"),"");if(!article.isEmpty())refs.add(map("kind","knowledge","article_id",article));if(!table.isEmpty() || !record.isEmpty())refs.add(map("kind","record","table",table,"record_id",record));if(!refs.isEmpty())form.put("sources",refs);return form;}
}
