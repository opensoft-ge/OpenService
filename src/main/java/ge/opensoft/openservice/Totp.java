package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;

/** Verified per-account TOTP enrollment and browser-bound second-factor challenges. */
final class Totp {
 private Totp() {}
 static boolean enabled(Store.Db db,String user)throws Exception {return db.count("SELECT COUNT(*) FROM os_totp WHERE user_id=?",user)==1;}
 static String code(String secret,long step)throws Exception {
  var mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(new Base32().decode(secret),"HmacSHA1"));byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=digest[digest.length-1]&15;int value=ByteBuffer.wrap(digest,offset,4).getInt()&0x7fffffff;return String.format(Locale.ROOT,"%06d",value%1000000);
 }
 static long match(String secret,String submitted,long now,long previous)throws Exception {
  long found=-1,center=now/30000;for(long step=center-1;step<=center+1;step++)if(step>=0 && step>previous && MessageDigest.isEqual(code(secret,step).getBytes(StandardCharsets.US_ASCII),submitted.getBytes(StandardCharsets.UTF_8)))found=step;return found;
 }
 static String seed(Store store,String envelope) {
  try {Object value=Outbox.decrypt(store.secret,envelope).get("seed");require(value instanceof String text && text.matches("[A-Z2-7]{32}"),"Invalid factor seed");return (String)value;}
  catch(Exception unavailable){throw new Failure(503,"The authentication factor is unavailable; contact your operator");}
 }
 static void throttle(Store.Db db,String user,long now)throws Exception {
  db.update("DELETE FROM os_totp_attempt WHERE expires_at<=?",now);if(db.count("SELECT COUNT(*) FROM os_totp_attempt WHERE user_id=? AND failures>=5",user)>0)throw new Failure(429,"Too many verification attempts; try again in five minutes");
 }
 static Failure failed(Store.Db db,Map<String,Object> user,long now)throws Exception {
  db.update("INSERT INTO os_totp_attempt(user_id,failures,expires_at) VALUES(?,1,?) ON CONFLICT(user_id) DO UPDATE SET failures=os_totp_attempt.failures+1,expires_at=CASE WHEN os_totp_attempt.failures+1>=5 THEN excluded.expires_at ELSE os_totp_attempt.expires_at END",user.get("user_id"),now+300000);
  AuthenticationEvents.record(db,user.get("name").toString(),Passwords.tokenHash(user.get("name").toString()),"mfa","failed");db.connection.commit();return new Failure(401,"The authenticator or recovery code was not accepted");
 }
 static void accepted(Store.Db db,Map<String,Object> user)throws Exception {
  db.update("DELETE FROM os_totp_attempt WHERE user_id=?",user.get("user_id"));AuthenticationEvents.record(db,user.get("name").toString(),Passwords.tokenHash(user.get("name").toString()),"mfa","succeeded");
 }
 static void factor(Store store,Store.Db db,Map<String,Object> user,String submitted,long now)throws Exception {
  String id=user.get("user_id").toString();throttle(db,id,now);var factor=db.one("SELECT * FROM os_totp WHERE user_id=?",id);if(factor==null)throw new Failure(401,"The authentication factor changed; sign in again");
  if(submitted.matches("osr_[A-Za-z0-9_-]{32}") && db.update("DELETE FROM os_totp_recovery WHERE user_id=? AND digest=?",id,Passwords.tokenHash(submitted))==1){accepted(db,user);return;}
  long step=match(seed(store,factor.get("envelope").toString()),submitted,now,((Number)factor.get("last_step")).longValue());
  if(step<0)throw failed(db,user,now);
  db.update("UPDATE os_totp SET last_step=? WHERE user_id=?",step,id);accepted(db,user);
 }
 static List<String> recovery(Store.Db db,String user)throws Exception {
  db.update("DELETE FROM os_totp_recovery WHERE user_id=?",user);var codes=new ArrayList<String>();for(int n=0;n<10;n++){String code="osr_"+Passwords.token();codes.add(code);db.update("INSERT INTO os_totp_recovery(user_id,digest) VALUES(?,?)",user,Passwords.tokenHash(code));}return codes;
 }
 static Map<String,Object> page(Store.Db db,Map<String,Object> user,String session,long now)throws Exception {
  String id=user.get("user_id").toString();return map("enabled",enabled(db,id),"pending",db.count("SELECT COUNT(*) FROM os_totp_setup WHERE user_id=? AND session_hash=? AND auth_revision=? AND expires_at>?",id,Passwords.tokenHash(session),user.get("auth_revision"),now)==1,"recovery_remaining",db.count("SELECT COUNT(*) FROM os_totp_recovery WHERE user_id=?",id),"setup_key","","setup_uri","","recovery_codes",List.of(),"sign_in_required",false);
 }
 static void current(Store.Db db,Map<String,Object> user,String session,long now)throws Exception {
  if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");Authentication.requireCurrent(db,user);BrowserSessions.requireCurrent(db,user,session,now);
 }
 static Map<String,Object> manage(Store store,Store.Db db,Map<String,Object> user,String session,Object input,long now)throws Exception {
  var body=object(input);String operation=WorkJobs.text(body.get("operation"),30),id=user.get("user_id").toString();
  require(Set.of("begin","confirm","regenerate","disable").contains(operation),"Choose a factor-management operation");
  Set<String> fields=operation.equals("begin")?Set.of("operation","password"):operation.equals("confirm")?Set.of("operation","code"):Set.of("operation","password","code");require(body.keySet().equals(fields),"Use only the selected factor-management fields");
  if(!operation.equals("confirm")){require(body.get("password") instanceof String,"Provide your current local password");if(Authentication.verify(db,user.get("name").toString(),(String)body.get("password"),"mfa-management")==null)throw new Failure(401,"Current password was not accepted");}
  current(db,user,session,now);var result=page(db,user,session,now);
  if(operation.equals("begin")) {
   require(!enabled(db,id),"Disable the current factor with a fresh code before replacing it");byte[] bytes=new byte[20];new SecureRandom().nextBytes(bytes);String seed=new Base32().encodeAsString(bytes);
   db.update("INSERT INTO os_totp_setup(user_id,session_hash,auth_revision,expires_at,envelope) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET session_hash=excluded.session_hash,auth_revision=excluded.auth_revision,expires_at=excluded.expires_at,envelope=excluded.envelope",id,Passwords.tokenHash(session),user.get("auth_revision"),now+600000,Outbox.encrypt(store.secret,JSON.writeValueAsString(map("seed",seed))));
   result.put("pending",true);result.put("setup_key",seed);result.put("setup_uri","otpauth://totp/"+java.net.URLEncoder.encode("OpenService:"+user.get("name"),StandardCharsets.UTF_8)+"?secret="+seed+"&issuer=OpenService&algorithm=SHA1&digits=6&period=30");SecurityEvents.record(db,id,id,"account.mfa_setup_started",map());return result;
  }
  require(body.get("code") instanceof String value && value.length()<=128,"Provide a bounded authenticator or recovery code");String submitted=body.get("code").toString();
  if(operation.equals("confirm")) {
   require(!enabled(db,id),"This account already has an authentication factor");var pending=db.one("SELECT * FROM os_totp_setup WHERE user_id=? AND session_hash=? AND auth_revision=? AND expires_at>?",id,Passwords.tokenHash(session),user.get("auth_revision"),now);if(pending==null)throw new Failure(401,"Factor setup expired or changed; start setup again");throttle(db,id,now);long step=match(seed(store,pending.get("envelope").toString()),submitted,now,-1);
   if(step<0)throw failed(db,user,now);
   db.update("INSERT INTO os_totp(user_id,envelope,last_step) VALUES(?,?,?)",id,pending.get("envelope"),step);db.update("DELETE FROM os_totp_setup WHERE user_id=?",id);accepted(db,user);result.put("recovery_codes",recovery(db,id));result.put("enabled",true);result.put("pending",false);result.put("recovery_remaining",10);
  } else {
   factor(store,db,user,submitted,now);
   if(operation.equals("regenerate")){result.put("recovery_codes",recovery(db,id));result.put("recovery_remaining",10);}
   else {db.update("DELETE FROM os_totp_recovery WHERE user_id=?",id);db.update("DELETE FROM os_totp WHERE user_id=?",id);db.update("DELETE FROM os_totp_setup WHERE user_id=?",id);result.put("enabled",false);result.put("pending",false);result.put("recovery_remaining",0);}
  }
  db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id=?",id);db.update("DELETE FROM os_totp_challenge WHERE user_id=?",id);result.put("sign_in_required",true);SecurityEvents.record(db,id,id,"account.mfa_"+operation,map());return result;
 }
 static void start(Store store,Store.Db db,Map<String,Object> user,String session,long now)throws Exception {
  if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");Authentication.requireCurrent(db,user);db.update("DELETE FROM os_totp_challenge WHERE expires_at<=? OR session_hash=?",now,Passwords.tokenHash(session));if(db.count("SELECT COUNT(*) FROM os_totp_challenge")>=1000)throw new Failure(429,"Second-factor sign-in is busy; try again later");
  var context=map("oidc_configuration",user.getOrDefault("oidc_configuration",""),"oidc_binding",user.getOrDefault("oidc_binding",""));db.update("INSERT INTO os_totp_challenge(session_hash,user_id,auth_revision,expires_at,envelope) VALUES(?,?,?,?,?)",Passwords.tokenHash(session),user.get("user_id"),user.get("auth_revision"),now+300000,Outbox.encrypt(store.secret,JSON.writeValueAsString(context)));
 }
 static boolean pending(Store.Db db,String session,long now)throws Exception {return db.count("SELECT COUNT(*) FROM os_totp_challenge WHERE session_hash=? AND expires_at>?",Passwords.tokenHash(session),now)==1;}
 static Map<String,Object> complete(Store store,Store.Db db,String session,Object input,long now)throws Exception {
  var body=object(input);require(body.keySet().equals(Set.of("code")) && body.get("code") instanceof String code && code.length()<=128,"Provide one bounded authenticator or recovery code");if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var challenge=db.one("SELECT * FROM os_totp_challenge WHERE session_hash=? AND expires_at>?",Passwords.tokenHash(session),now);if(challenge==null)throw new Failure(401,"Second-factor sign-in expired; sign in again");
  var user=db.one("SELECT * FROM os_user WHERE user_id=? AND auth_revision=? AND active=1 AND api_only=0",challenge.get("user_id"),challenge.get("auth_revision"));if(user==null)throw new Failure(401,"Account access changed; sign in again");Map<String,Object> context;
  try {context=Outbox.decrypt(store.secret,challenge.get("envelope").toString());}
  catch(Exception unavailable){throw new Failure(503,"Second-factor sign-in is unavailable; start again");}
  String fingerprint=context.get("oidc_configuration").toString();if(!fingerprint.isEmpty()) {
   if(!Oidc.permits(store,db,user,fingerprint,context.get("oidc_binding")))throw new Failure(401,"Organizational access changed; sign in again");user.putAll(context);
  }
  factor(store,db,user,body.get("code").toString(),now);db.update("DELETE FROM os_totp_challenge WHERE session_hash=?",Passwords.tokenHash(session));return user;
 }
}
