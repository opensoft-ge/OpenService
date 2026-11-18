package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.id.*;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import jakarta.servlet.http.HttpSession;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** One explicitly configured issuer; only reviewed subject bindings can authenticate local users. */
final class Oidc {
    private Oidc() {}

    static URI url(String input) {
        URI uri;
        try { uri = URI.create(input); }
        catch (IllegalArgumentException invalid) { throw new Failure(400,"Use a valid federation URL"); }
        require(uri.getHost()!=null && uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null
                && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost())),
                "Federation requires HTTPS URLs without credentials, query or fragment; HTTP is restricted to loopback");
        return uri;
    }

    static Map<String,Object> settings(Store store) {
        if(!Boolean.parseBoolean(store.environment.getProperty("OIDC_ENABLED","false")))throw new Failure(503,"Organizational sign-in is disabled; use local sign-in");
        var config=new LinkedHashMap<String,Object>();
        for(String key:List.of("issuer","client_id","client_secret","authorization_url","token_url","jwks_url"))
            config.put(key,WorkJobs.text(store.environment.getProperty("OIDC_"+key.toUpperCase(Locale.ROOT),""),2048));
        require(config.get("issuer").toString().length()<=512,"Use a bounded issuer URL");
        URI issuer=url(config.get("issuer").toString());
        for(String key:List.of("authorization_url","token_url","jwks_url")) {
            URI endpoint=url(config.get(key).toString());
            require(Objects.equals(issuer.getScheme(),endpoint.getScheme()) && Objects.equals(issuer.getRawAuthority(),endpoint.getRawAuthority()),"Federation endpoints must share the configured issuer origin");
        }
        URI base=url(store.environment.getProperty("PUBLIC_BASE_URL",""));
        require(Set.of("","/").contains(base.getPath()),"Use a public origin without a path");
        config.put("callback",base.getScheme()+"://"+base.getRawAuthority()+"/oidc/callback");
        config.put("fingerprint",DigestUtils.sha256Hex(JSON.writeValueAsString(config)));
        return config;
    }

    static Map<String,Object> page(Store store,Store.Db db)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);
        Map<String,Object> config=map("ready",false,"issuer","","callback","");
        try {var ready=settings(store);config=map("ready",true,"issuer",ready.get("issuer"),"callback",ready.get("callback"));}
        catch(Failure unavailable) { /* Local sign-in and removal of existing bindings remain available. */ }
        return map("configuration",config,"bindings",db.all("SELECT b.binding_id,b.issuer,b.subject,b.user_id,b.revision,u.name FROM os_oidc_binding b JOIN os_user u ON u.user_id=b.user_id ORDER BY b.issuer,b.subject"));
    }

    static Map<String,Object> bind(Store store,Store.Db db,String id,Object input)throws Exception {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Service.requireAccountAdministrator(db,db.viewerId);var body=object(input);
        require(body.keySet().equals(Set.of("revision","username","subject","remove")) && body.get("remove") instanceof Boolean,"Provide revision, username, subject and Boolean remove");
        var before=id.isEmpty()?null:db.one("SELECT * FROM os_oidc_binding WHERE binding_id=?",id);
        if(!id.isEmpty() && before==null)throw new Failure(404,"Federation binding not found");
        ProblemInvestigations.revision(body.get("revision"),before==null?0:before.get("revision"));
        String userId;
        if(Boolean.TRUE.equals(body.get("remove"))) {
            require(before!=null,"Choose an existing binding");userId=before.get("user_id").toString();
            db.update("DELETE FROM os_oidc_binding WHERE binding_id=?",id);
        } else {
            var config=settings(store);String username=WorkJobs.text(body.get("username"),80),subject=subjectText(body.get("subject"));
            var user=db.one("SELECT * FROM os_user WHERE name=? AND active=1 AND api_only=0 AND password_reset_required=0",username);
            require(user!=null,"Choose an active browser account without a password replacement requirement");userId=user.get("user_id").toString();
            require(before==null || before.get("user_id").equals(userId),"A binding keeps its local account; remove it before replacement");
            if(db.count("SELECT COUNT(*) FROM os_oidc_binding WHERE issuer=? AND (subject=? OR user_id=?) AND binding_id<>?",config.get("issuer"),subject,userId,id)>0)throw new Failure(409,"This subject or account already has a binding for the issuer");
            if(before==null){require(db.count("SELECT COUNT(*) FROM os_oidc_binding")<1000,"At most1000 federation bindings are supported");id=Values.id();db.update("INSERT INTO os_oidc_binding(binding_id,issuer,subject,user_id,revision) VALUES(?,?,?,?,1)",id,config.get("issuer"),subject,userId);}
            else db.update("UPDATE os_oidc_binding SET issuer=?,subject=?,revision=revision+1 WHERE binding_id=?",config.get("issuer"),subject,id);
        }
        db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id=?",userId);
        SecurityEvents.record(db,db.viewerId,userId,"identity.federation_changed",map("binding_id",id,"removed",body.get("remove")));
        return page(store,db);
    }

    static String subjectText(Object input) {
        require(input instanceof String text && text.matches("[!-~]{1,255}"),"Use an exact nonempty ASCII provider subject without whitespace");
        return (String)input;
    }

    static String encoded(Map<String,Object> values) {
        var parts=new ArrayList<String>();values.forEach((key,value)->parts.add(URLEncoder.encode(key,StandardCharsets.UTF_8)+"="+URLEncoder.encode(value.toString(),StandardCharsets.UTF_8)));
        return String.join("&",parts);
    }

    static String start(Store store,Store.Db db,String session,long now)throws Exception {
        var config=settings(store);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        String browser=Passwords.tokenHash(session);db.update("DELETE FROM os_oidc_challenge WHERE expires_at<=? OR session_hash=?",now,browser);
        if(db.count("SELECT COUNT(*) FROM os_oidc_challenge")>=1000)throw new Failure(429,"Organizational sign-in is busy; try again later");
        String state=Passwords.token(),nonce=Passwords.token(),verifier=Passwords.token()+Passwords.token();
        db.update("INSERT INTO os_oidc_challenge(state_hash,session_hash,expires_at,configuration,envelope) VALUES(?,?,?,?,?)",Passwords.tokenHash(state),browser,now+300000,config.get("fingerprint"),Outbox.encrypt(store.secret,JSON.writeValueAsString(map("nonce",nonce,"verifier",verifier))));
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(DigestUtils.sha256(verifier));
        return config.get("authorization_url")+"?"+encoded(map("client_id",config.get("client_id"),"redirect_uri",config.get("callback"),"response_type","code","scope","openid","state",state,"nonce",nonce,"code_challenge",challenge,"code_challenge_method","S256"));
    }

    static byte[] request(String url,Map<String,Object> form)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Accept","application/json");
        if(form.isEmpty())builder.GET();else builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(encoded(form)));
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),65536));
            if(response.statusCode()!=200)throw new Failure(502,"The identity provider could not complete sign-in; start again or use local sign-in");
            return response.body();
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new Failure(502,"Organizational sign-in was interrupted; start again");}
        catch(java.io.IOException unavailable) {throw new Failure(502,"The identity provider is unavailable; start again or use local sign-in");}
    }

    static String subject(Map<String,Object> config,String nonce,byte[] tokenBody,byte[] keyBody,long now) {
        try {
            var token=object(JSON.readValue(tokenBody,Object.class));String raw=WorkJobs.text(token.get("id_token"),16384);var jwt=SignedJWT.parse(raw);
            var keys=JWKSet.parse(new String(keyBody,StandardCharsets.UTF_8));
            require(!keys.getKeys().isEmpty() && keys.getKeys().size()<=16,"Invalid provider signing keys");
            String kid=jwt.getHeader().getKeyID();require(kid!=null && !kid.isEmpty(),"Missing provider signing key identity");
            require(keys.getKeys().stream().filter(candidate->kid.equals(candidate.getKeyID())).count()==1,"Ambiguous provider signing key identity");
            var key=keys.getKeyByKeyId(kid);require(key instanceof RSAKey rsa && !rsa.isPrivate() && rsa.toRSAPublicKey().getModulus().bitLength()>=2048,"Invalid provider signing key");
            var validator=new IDTokenValidator(new Issuer(config.get("issuer").toString()),new ClientID(config.get("client_id").toString()),JWSAlgorithm.RS256,new JWKSet(key));validator.setMaxClockSkew(30);
            var claims=validator.validate(jwt,new Nonce(nonce));var values=jwt.getJWTClaimsSet();String azp=values.getStringClaim("azp");
            require((azp==null || azp.equals(config.get("client_id"))) && (values.getAudience().size()==1 || azp!=null),"Invalid authorized party");
            require(values.getIssueTime().getTime()>=now-300000 && values.getExpirationTime().after(values.getIssueTime()),"Invalid provider token lifetime");
            return subjectText(claims.getSubject().getValue());
        } catch(Exception rejected) {throw new Failure(401,"The identity provider response could not be verified; start sign-in again");}
    }

    static Map<String,Object> complete(Store store,Store.Db db,String session,Map<String,String> args,long now)throws Exception {
        require(Set.of("state","code","error","iss").containsAll(args.keySet()) && args.containsKey("state") && (args.containsKey("code") ^ args.containsKey("error")),"Provide one organizational sign-in response");
        String state=args.get("state");require(state.matches("[A-Za-z0-9_-]{32}"),"Invalid sign-in state");
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var challenge=db.one("SELECT * FROM os_oidc_challenge WHERE state_hash=? AND session_hash=? AND expires_at>?",Passwords.tokenHash(state),Passwords.tokenHash(session),now);
        if(challenge==null)throw new Failure(401,"Sign-in expired or was already used; start again");
        db.update("DELETE FROM os_oidc_challenge WHERE state_hash=?",Passwords.tokenHash(state));db.connection.commit();
        var config=settings(store);
        if(!config.get("fingerprint").equals(challenge.get("configuration")) || args.containsKey("error") || args.containsKey("iss") && !args.get("iss").equals(config.get("issuer")))throw new Failure(401,"Organizational sign-in changed or was declined; start again");
        Map<String,Object> material;
        try { material=Outbox.decrypt(store.secret,challenge.get("envelope").toString()); }
        catch(Exception unavailable) {throw new Failure(503,"Sign-in challenge is unavailable; start again");}
        String code=WorkJobs.text(args.get("code"),4096);
        // Release the read transaction before network access; all current authority is read again below.
        db.connection.commit();
        var token=request(config.get("token_url").toString(),map("grant_type","authorization_code","code",code,"redirect_uri",config.get("callback"),"client_id",config.get("client_id"),"client_secret",config.get("client_secret"),"code_verifier",material.get("verifier")));
        String subject=subject(config,material.get("nonce").toString(),token,request(config.get("jwks_url").toString(),Map.of()),now);
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        if(!settings(store).get("fingerprint").equals(config.get("fingerprint")))throw new Failure(401,"Organizational sign-in configuration changed; start again");
        var binding=db.one("SELECT * FROM os_oidc_binding WHERE issuer=? AND subject=?",config.get("issuer"),subject);
        if(binding==null)throw new Failure(401,"No eligible local account is linked to this organizational identity");
        var user=db.one("SELECT * FROM os_user WHERE user_id=? AND active=1 AND api_only=0 AND password_reset_required=0",binding.get("user_id"));
        if(user==null)throw new Failure(401,"No eligible local account is linked to this organizational identity");
        AuthenticationEvents.record(db,user.get("name").toString(),Passwords.tokenHash(user.get("name").toString()),"oidc","succeeded");
        user.put("oidc_configuration",config.get("fingerprint"));user.put("oidc_binding",binding.get("binding_id"));return user;
    }

    static boolean current(Store store,Store.Db db,Map<String,Object> user,HttpSession session)throws Exception {
        return permits(store,db,user,session.getAttribute("oidc_configuration"),session.getAttribute("oidc_binding"));
    }

    static boolean permits(Store store,Store.Db db,Map<String,Object> user,Object fingerprint,Object binding)throws Exception {
        if(fingerprint==null)return true;
        try {var config=settings(store);return fingerprint.equals(config.get("fingerprint")) && db.count("SELECT COUNT(*) FROM os_oidc_binding WHERE binding_id=? AND user_id=? AND issuer=?",binding,user.get("user_id"),config.get("issuer"))==1;}
        catch(Failure unavailable) {return false;}
    }
}
