package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

final class IssueTokens {
    private IssueTokens() {}

    static Object configure(Store store,Store.Db db,Map<String,Object> body) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);
        lock(db);
        keys(body,"operation","key","revision","issuer_alias","token_path","client_id","client_secret","refresh_token");
        String key=EnterpriseData.id(body.get("key"));
        var schedule=object(get(db,"import-schedule",key).get("definition"));
        require(db.viewerId.equals(schedule.get("owner")),"Select a schedule you own");
        RecurringImports.authority(db,schedule);
        var issuer=db.one("SELECT * FROM os_connector WHERE alias=?",WorkJobs.text(body.get("issuer_alias"),80));
        require(issuer!=null,"Select an approved token issuer alias");
        var secret=map();
        for(String field:List.of("client_id","client_secret","refresh_token")) secret.put(field,ConnectorCredentials.credential(body.get(field),4096));
        save(db,"schedule-token",key,body.get("revision"),map("owner",db.viewerId,"binding",schedule.get("connector"),
                "issuer_alias",issuer.get("alias"),"issuer",Connectors.review(issuer),"token_path",Connectors.path(body.get("token_path")),
                "expires",0L,"envelope",Outbox.encrypt(store.secret+":issue:"+key,JSON.writeValueAsString(secret))));
        return map("configured",true);
    }

    static Map<String,Object> exchange(HttpRequest request) throws Exception {
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response=client.send(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),1048576));
            require(response.statusCode()/100==2,"Remote request failed");
            return object(ConnectorSynchronization.readJson(response.body()));
        }
    }

    static String access(Store store,Store.Db db,String key,Map<String,Object> schedule,Map<String,Object> connector,boolean renew) throws Exception {
        var row=get(db,"schedule-token",key);
        var profile=object(row.get("definition"));
        if(profile.isEmpty()) return Outbox.decrypt(store.secret,connector.get("envelope").toString()).get("token").toString();
        require(List.of(profile.get("owner"),profile.get("binding")).equals(List.of(schedule.get("owner"),Connectors.review(connector))),"Review OAuth credentials for this schedule again");
        var issuer=RecurringImports.checkedConnector(store,db,map("alias",profile.get("issuer_alias"),"connector",profile.get("issuer")));
        var credentials=Outbox.decrypt(store.secret+":issue:"+key,profile.get("envelope").toString());
        if(((Number)profile.get("expires")).longValue()>System.currentTimeMillis()+15000) return credentials.get("access_token").toString();
        require(renew,"Access credential expired before issue update");
        var parameters=new StringJoiner("&");
        parameters.add("grant_type=refresh_token");
        for(String field:List.of("client_id","client_secret","refresh_token"))
            parameters.add(field+"="+URLEncoder.encode(credentials.get(field).toString(),StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(issuer.get("origin").toString()+profile.get("token_path"))).timeout(Duration.ofSeconds(15))
                .header("Accept","application/json").header("Content-Type","application/x-www-form-urlencoded")
                .POST(ConnectorWrites.body(parameters.toString())).build();
        var response=exchange(request);
        require("bearer".equalsIgnoreCase(Objects.toString(response.get("token_type"),"")),"Issuer must return a bearer credential");
        String token=WorkJobs.text(response.get("access_token"),4096);
        require(token.matches("[A-Za-z0-9._~+/=-]{1,4096}"),"Invalid access token");
        int seconds=integer(response.get("expires_in"),30,31536000);
        credentials.put("access_token",token);
        credentials.put("refresh_token",ConnectorCredentials.credential(response.getOrDefault("refresh_token",credentials.get("refresh_token")),4096));
        profile.putAll(map("expires",System.currentTimeMillis()+seconds*1000L,"envelope",Outbox.encrypt(store.secret+":issue:"+key,JSON.writeValueAsString(credentials))));
        save(db,"schedule-token",key,row.get("revision"),profile);
        return token;
    }
}
