package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.*;
/** Self-reported profile text is independent from verified identity and access policy. */
final class Profiles {
 private Profiles() {}
 static Map<String,Object> read(Store.Db db,String user) throws SQLException {
  Service.account(db,user);return db.one("SELECT display_name,job_title FROM os_user WHERE user_id=?",user);
 }
 static String text(Object input) {
  require(input instanceof String,"Profile values must be text");String raw=(String)input;
  require(raw.length()<=1024 && !raw.codePoints().anyMatch(Character::isISOControl),"Use profile text without control characters");
  String value=Normalizer.normalize(raw.strip(),Normalizer.Form.NFC);
  require(value.codePointCount(0,value.length())<=80,"Profile fields must be at most 80 characters");return value;
 }
 static Map<String,Object> save(Store.Db db,String actor,Object input) throws SQLException {
  var body=object(input);require(body.keySet().equals(Set.of("display_name","job_title")),"Provide display_name and job_title only");
  var updated=map("display_name",text(body.get("display_name")),"job_title",text(body.get("job_title")));
  var previous=read(db,actor);if(updated.equals(previous))return previous;
  db.update("UPDATE os_user SET display_name=?,job_title=? WHERE user_id=?",updated.get("display_name"),updated.get("job_title"),actor);
  var changed=new ArrayList<String>();for(String key:List.of("display_name","job_title"))if(!updated.get(key).equals(previous.get(key)))changed.add(key);
  SecurityEvents.record(db,actor,actor,"account.profile_changed",map("fields",changed));return updated;
 }
}
