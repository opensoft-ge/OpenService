package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import java.time.*;
import java.util.*;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
/** Hashed identifiers, encrypted scalar attributes and delta merge under one shared lock. */
final class SqlSessions implements SessionRepository<SqlSessions.State> {
 static final ThreadLocal<Store.Db> REQUEST_DATABASE=new ThreadLocal<>();
 private record Lease(Store.Db db,boolean owned) implements AutoCloseable {
  void commit()throws Exception{if(owned)db.connection.commit();}
  public void close()throws Exception{if(owned)db.close();}
 }
 private Lease acquire()throws Exception{var current=REQUEST_DATABASE.get();return current==null?new Lease(store.open(),true):new Lease(current,false);}
 private final Store store;
 SqlSessions(Store store){this.store=store;}
 static final class State implements Session {
  private final MapSession delegate;String originalId;Map<String,Object> before=map();boolean fresh;
  State(String id,boolean fresh){delegate=new MapSession(id);this.originalId=id;this.fresh=fresh;setMaxInactiveInterval(Duration.ofMillis(BrowserSessions.IDLE_MILLIS));}
  public String getId(){return delegate.getId();}
  public String changeSessionId(){return delegate.changeSessionId();}
  public <T> T getAttribute(String name){return delegate.getAttribute(name);}
  public Set<String> getAttributeNames(){return delegate.getAttributeNames();}
  public void setAttribute(String name,Object value){delegate.setAttribute(name,value);}
  public void removeAttribute(String name){delegate.removeAttribute(name);}
  public Instant getCreationTime(){return delegate.getCreationTime();}
  void setCreationTime(Instant value){delegate.setCreationTime(value);}
  public void setLastAccessedTime(Instant value){delegate.setLastAccessedTime(value);}
  public Instant getLastAccessedTime(){return delegate.getLastAccessedTime();}
  public void setMaxInactiveInterval(Duration value){delegate.setMaxInactiveInterval(value);}
  public Duration getMaxInactiveInterval(){return delegate.getMaxInactiveInterval();}
  public boolean isExpired(){return delegate.isExpired();}
 }

 @Override public State createSession(){return new State(UUID.randomUUID().toString(),true);}
 static Map<String,Object> attributes(State session){var values=map();for(String name:session.getAttributeNames()){Object value=session.getAttribute(name);if(!(value instanceof String || value instanceof Long))throw new IllegalArgumentException("Shared session attributes must be strings or long integers");values.put(name,value);}if(JSON.writeValueAsBytes(values).length>65536)throw new IllegalArgumentException("Shared session exceeds64KiB");return values;}
 private Map<String,Object> decode(String hash,Object envelope)throws Exception{var data=Outbox.decrypt(store.secret+":shared-session-v1",envelope.toString());if(!hash.equals(data.get("session_hash")))throw new IllegalStateException("Shared session envelope binding mismatch");return object(data.get("attributes"));}
 private String encode(String hash,Map<String,Object> attributes)throws Exception{return Outbox.encrypt(store.secret+":shared-session-v1",JSON.writeValueAsString(map("session_hash",hash,"attributes",attributes)));}
 @Override public State findById(String id){
  if(id==null || !id.matches("[0-9a-f-]{36}"))return null;State result=null;
  try(var lease=acquire()){var db=lease.db();
   String hash=Passwords.tokenHash(id);var row=db.one("SELECT * FROM os_shared_session WHERE session_hash=?",hash);
   if(row!=null){
    if(((Number)row.get("expires_at")).longValue()<=System.currentTimeMillis()){db.update("DELETE FROM os_shared_session WHERE session_hash=? AND expires_at<=?",hash,System.currentTimeMillis());lease.commit();}
    else{var session=new State(id,false);session.setCreationTime(Instant.ofEpochMilli(((Number)row.get("created_at")).longValue()));session.setLastAccessedTime(Instant.ofEpochMilli(((Number)row.get("last_seen")).longValue()));for(var entry:decode(hash,row.get("envelope")).entrySet()){Object value=entry.getValue();session.setAttribute(entry.getKey(),value instanceof Number number?number.longValue():value);}session.before=attributes(session);result=session;}
   }
  }catch(Exception failure){throw new IllegalStateException("Shared browser session store unavailable",failure);}
  return result;
 }
 @Override public void save(State session){
  var values=attributes(session);long now=System.currentTimeMillis();if(!session.getMaxInactiveInterval().equals(Duration.ofMillis(BrowserSessions.IDLE_MILLIS)))throw new IllegalArgumentException("Shared sessions require the native30-minute idle lifetime");
  try(var lease=acquire()){var db=lease.db();
   if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,8)");db.update("DELETE FROM os_shared_session WHERE expires_at<=?",now);String old=Passwords.tokenHash(session.originalId),hash=Passwords.tokenHash(session.getId());var row=db.one("SELECT * FROM os_shared_session WHERE session_hash=?",old);var merged=map();long created=session.getCreationTime().toEpochMilli(),seen=session.getLastAccessedTime().toEpochMilli();
   if(!session.fresh && row==null){lease.commit();}else{
   if(!session.fresh){merged.putAll(decode(old,row.get("envelope")));created=((Number)row.get("created_at")).longValue();seen=Math.max(seen,((Number)row.get("last_seen")).longValue());var names=new HashSet<>(session.before.keySet());names.addAll(values.keySet());for(String name:names)if(!Objects.equals(session.before.get(name),values.get(name))){if(values.containsKey(name))merged.put(name,values.get(name));else merged.remove(name);}}
   else{if(db.count("SELECT COUNT(*) FROM os_shared_session")>=10000)throw new IllegalStateException("Shared session capacity reached");merged.putAll(values);}
   if(JSON.writeValueAsBytes(merged).length>65536)throw new IllegalStateException("Merged shared session exceeds64KiB");
   if(!old.equals(hash))db.update("DELETE FROM os_shared_session WHERE session_hash=?",old);
   db.update("INSERT INTO os_shared_session(session_hash,created_at,last_seen,expires_at,envelope) VALUES(?,?,?,?,?) ON CONFLICT(session_hash) DO UPDATE SET last_seen=excluded.last_seen,expires_at=excluded.expires_at,envelope=excluded.envelope",hash,created,seen,seen+BrowserSessions.IDLE_MILLIS,encode(hash,merged));lease.commit();session.fresh=false;session.originalId=session.getId();session.before=values;
   }
  }catch(Exception failure){throw new IllegalStateException("Shared browser session store unavailable",failure);}
 }
 @Override public void deleteById(String id){try(var lease=acquire()){var db=lease.db();if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,8)");db.update("DELETE FROM os_shared_session WHERE session_hash=?",Passwords.tokenHash(id));lease.commit();}catch(Exception failure){throw new IllegalStateException("Shared browser session store unavailable",failure);}}
}
