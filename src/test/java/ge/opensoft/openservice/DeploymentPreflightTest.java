package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;

class DeploymentPreflightTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void diagnosesConfigurationWithoutClaimingDeploymentEvidence(boolean postgres) throws Exception {
        var fixture = new MigrationsTest(); fixture.root=root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        try(var db=store.open()) {
            assertEquals(false,DeploymentPreflight.inspect(store,db).get("configuration_ready"));
            env.withProperty("COOKIE_SECURE","true").withProperty("WORK_ENABLED","true")
                    .withProperty("DELIVERY_ENABLED","true").withProperty("PUBLIC_BASE_URL","https://mail.example.invalid")
                    .withProperty("MAIL_HOST","smtp.example.invalid").withProperty("MAIL_FROM","sender@example.invalid")
                    .withProperty("MAIL_PASSWORD","synthetic-mail-secret");
            var configured=DeploymentPreflight.inspect(store,db);
            assertEquals(postgres,configured.get("configuration_ready"));
            assertEquals(false,configured.get("shared_release_qualified"));
            assertFalse(configured.toString().contains("synthetic-mail-secret"));
            assertEquals(4,((java.util.List<?>)configured.get("external_verification")).size());
            env.withProperty("MAIL_HOST","");
            assertFalse(DeploymentPreflight.mailConfigured(env));
            env.withProperty("MAIL_HOST","smtp.example.invalid");
            for(String port:new String[]{"0","65536"}) {
                env.withProperty("MAIL_PORT",port);
                assertFalse(DeploymentPreflight.mailConfigured(env));
            }
            env.withProperty("MAIL_PORT","587");
            env.withProperty("COOKIE_SECURE","false").withProperty("WORK_ENABLED","false");
            db.update("UPDATE os_user SET active=0");
            db.update("INSERT INTO os_work(job_id,owner_id,auth_revision,kind,dedupe,fingerprint,envelope,state,attempts,available_at,created_at,lease,error,result) VALUES('preflight-failed','x',0,'notification','x','x','x','failed',5,0,0,'','','')");
            db.update("INSERT INTO os_work(job_id,owner_id,auth_revision,kind,dedupe,fingerprint,envelope,state,attempts,available_at,created_at,lease,error,result) VALUES('preflight-stale','x',0,'notification','y','x','x','processing',1,0,0,'lease','','')");
            assertTrue(((Number)DeploymentPreflight.inspect(store,db).get("actions_required")).intValue()>=5);
        }
        var local=new MockEnvironment().withProperty("DELIVERY_ENABLED","true").withProperty("PUBLIC_BASE_URL","http://localhost")
                .withProperty("MAIL_ALLOW_INSECURE_LOCAL","true");
        assertFalse(DeploymentPreflight.mailConfigured(local));
        local.withProperty("PUBLIC_BASE_URL","not an origin");
        assertFalse(DeploymentPreflight.mailConfigured(local));
        local.withProperty("PUBLIC_BASE_URL","https://example.invalid");
        assertFalse(DeploymentPreflight.mailConfigured(local));
    }
}
