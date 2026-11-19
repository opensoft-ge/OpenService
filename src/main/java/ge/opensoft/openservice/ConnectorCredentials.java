package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Explicit OAuth client-credentials exchange into an existing approved connector alias. */
final class ConnectorCredentials {
    private ConnectorCredentials() {}
    static String credential(Object value,int maximum){require(value instanceof String text && !text.isEmpty() && text.length()<=maximum,"Use a nonempty credential within its length limit");return (String)value;}
    static List<Map<String,Object>> publicProfiles(Store.Db db)throws Exception {var rows=list(db,"oauth");for(var row:rows){var data=new LinkedHashMap<>(object(row.get("definition")));data.remove("envelope");row.put("definition",data);}return rows;}
    static void command(Store store,Store.Db db,Object input)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);lock(db);var body=object(input);String op=WorkJobs.text(body.get("operation"),12),alias=WorkJobs.text(body.get("alias"),80);var connector=db.one("SELECT * FROM os_connector WHERE alias=?",alias);if(connector==null)throw new Failure(404,"Connector alias not found");var row=get(db,"oauth",alias);ProblemInvestigations.revision(body.get("revision"),row.get("revision"));var data=object(row.get("definition"));
        if(op.equals("oauth")){
            keys(body,"operation","alias","revision","connector_revision","token_path","client_id","client_secret","scope");ProblemInvestigations.revision(body.get("connector_revision"),connector.get("revision"));String client=credential(body.get("client_id"),200),secret=credential(body.get("client_secret"),4096),scope=WorkJobs.text(body.get("scope"),1000);
            data=map("token_path",Connectors.path(body.get("token_path")),"connector_review",Connectors.review(connector),"expires_at",0L,"envelope",Outbox.encrypt(store.secret+":oauth-client-v1",JSON.writeValueAsString(map("alias",alias,"client_id",client,"client_secret",secret,"scope",scope))));
        }else{
            require(op.equals("refresh"),"Choose OAuth configuration or refresh");keys(body,"operation","alias","revision");require(!data.isEmpty(),"Configure OAuth credentials first");require(Connectors.review(connector).equals(data.get("connector_review")),"Connector changed; review OAuth configuration again");String origin=connector.get("origin").toString();require(Arrays.asList(store.environment.getProperty("CONNECTOR_ALLOWED_ORIGINS","").split(",")).contains(origin),"Connector origin is no longer allowed");var credentials=Outbox.decrypt(store.secret+":oauth-client-v1",data.get("envelope").toString());require(alias.equals(credentials.get("alias")),"Credential binding mismatch");
            String payload="grant_type=client_credentials&scope="+URLEncoder.encode(credentials.get("scope").toString(),StandardCharsets.UTF_8);String basic=Base64.getEncoder().encodeToString((URLEncoder.encode(credentials.get("client_id").toString(),StandardCharsets.UTF_8)+":"+URLEncoder.encode(credentials.get("client_secret").toString(),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(URI.create(origin+data.get("token_path"))).version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(15)).header("Authorization","Basic "+basic).header("Content-Type","application/x-www-form-urlencoded").POST(ConnectorWrites.body(payload)).build();
            try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()){
                var response=client.send(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),65536));require(response.statusCode()==200,"OAuth exchange failed; connector credential was not replaced");var token=object(JSON.readValue(response.body(),Object.class));require("Bearer".equalsIgnoreCase(Objects.toString(token.get("token_type"),"")),"OAuth server must return a bearer token");int seconds=integer(token.get("expires_in"),30,86400);Connectors.save(store,db,db.viewerId,map("alias",alias,"origin",origin,"token",token.get("access_token")));data.put("expires_at",System.currentTimeMillis()+seconds*1000L);data.put("connector_review",Connectors.review(db.one("SELECT * FROM os_connector WHERE alias=?",alias)));
            }
        }save(db,"oauth",alias,row.get("revision"),data);
    }
}
