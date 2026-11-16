package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.List;

class QueueHealthTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void countsDueWorkAndSignalsOnlyActionableConditions(boolean postgres) throws Exception {
        var fixture=new MigrationsTest();fixture.root=root;
        var env=fixture.configuration(postgres);
        var store=new Store(env);
        try(var db=store.open()) {
            long now=1700000000000L;
            var empty=QueueHealth.inspect(store,db,now);
            assertTrue(objects(empty.get("alerts")).isEmpty());
            assertEquals(0L,empty.get("oldest_due_ms"));
            assertEquals(5000L,empty.get("remaining_capacity"));
            for(String state:List.of("queued","processing","failed","succeeded","cancelled")) {
                String id=id();
                db.update("INSERT INTO os_work(job_id,owner_id,auth_revision,kind,dedupe,fingerprint,envelope,state,attempts,available_at,created_at,lease,error,result) VALUES(?,'x',0,'notification',?,'x','secret-payload',?,1,?,?,'','','')",id,id,state,now-300000,now-400000);
            }
            String future=id();
            db.update("INSERT INTO os_work(job_id,owner_id,auth_revision,kind,dedupe,fingerprint,envelope,state,attempts,available_at,created_at,lease,error,result) VALUES(?,'x',0,'connector.read',?,'x','secret-payload','queued',0,?,?,'','','')",future,future,now+1,now);
            var health=QueueHealth.inspect(store,db,now);
            assertEquals(1L,health.get("due"));assertEquals(1L,health.get("scheduled"));
            assertEquals(1L,health.get("processing"));assertEquals(1L,health.get("failed"));
            assertEquals(1L,health.get("expired_leases"));assertEquals(300000L,health.get("oldest_due_ms"));
            assertEquals(4,objects(health.get("alerts")).size());
            assertEquals(6,objects(health.get("counts")).size());
            assertFalse(health.toString().contains("secret-payload"));
            env.withProperty("WORK_ENABLED","true");
            assertEquals(3,objects(QueueHealth.inspect(store,db,now).get("alerts")).size());
            db.update("UPDATE os_work SET available_at=? WHERE state='queued' AND kind='notification'",now-299999);
            assertEquals(2,objects(QueueHealth.inspect(store,db,now).get("alerts")).size());
            db.update("DELETE FROM os_work");
            db.update("WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<5000) INSERT INTO os_work(job_id,owner_id,auth_revision,kind,dedupe,fingerprint,envelope,state,attempts,available_at,created_at,lease,error,result) SELECT CAST(n AS VARCHAR(32)),'x',0,'notification',CAST(n AS VARCHAR(32)),'x','','succeeded',1,0,0,'','','' FROM numbers");
            var full=QueueHealth.inspect(store,db,now);
            assertEquals(0L,full.get("remaining_capacity"));
            assertEquals("retention_full",objects(full.get("alerts")).getFirst().get("id"));
        }
    }
}
