package ge.opensoft.openservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

class MigrationsTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void connectorActionUpgradePreservesExistingAliasesAndWork(boolean postgres) throws Exception {
        var environment=configuration(postgres).withProperty("CONNECTOR_ALLOWED_ORIGINS","https://integration.example.invalid");var store=new Store(environment);String job,envelope;
        try(var db=store.open()){
            String actor=PlatformFoundationTest.bind(db);Connectors.save(store,db,actor,Values.map("alias","retained","origin","https://integration.example.invalid","token","retained-synthetic-token"));envelope=db.one("SELECT envelope FROM os_connector WHERE alias='retained'").get("envelope").toString();job=WorkJobs.enqueue(store,db,actor,Values.map("kind","notification","key","legacy-connector-upgrade","payload",Values.map("username","admin","message","Retained pending work"))).get("job_id").toString();
            db.update("DROP TABLE os_connector_resolution");db.update("DROP TABLE os_connector_action");db.update("DELETE FROM os_migration WHERE version>=47");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_action"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_resolution"));db.connection.rollback();assertEquals(46,db.count("SELECT MAX(version) FROM os_migration"));
        }
        for(int n=0;n<2;n++)try(var db=new Store(environment).open()){
            assertEquals(envelope,db.one("SELECT envelope FROM os_connector WHERE alias='retained'").get("envelope"));assertEquals("queued",db.one("SELECT state FROM os_work WHERE job_id=?",job).get("state"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_action"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_resolution"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void attemptHistoryUpgradeDoesNotInventPastExecutions(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);String job;
        try(var db=store.open()){
            String actor=PlatformFoundationTest.bind(db);job=WorkJobs.enqueue(store,db,actor,Values.map("kind","notification","key","legacy-attempt","payload",Values.map("username","admin","message","Retained work"))).get("job_id").toString();
            db.update("UPDATE os_work SET state='processing',attempts=2,lease=?,available_at=0 WHERE job_id=?",Values.id(),job);db.update("DROP TABLE os_work_attempt");db.update("DELETE FROM os_migration WHERE version>=46");db.connection.commit();
            Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_work_attempt"));db.connection.rollback();assertEquals(45,db.count("SELECT MAX(version) FROM os_migration"));
        }
        var upgraded=new Store(environment);try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db);assertEquals(0L,WorkAttempts.page(db,actor,job,Map.of()).get("total"));}
        assertTrue(new WorkWorker(upgraded).runOne(System.currentTimeMillis()+100));
        try(var db=new Store(environment).open()){
            String actor=PlatformFoundationTest.bind(db);var attempts=Values.objects(WorkAttempts.page(db,actor,job,Map.of()).get("result"));assertEquals(1,attempts.size());assertEquals(1L,((Number)attempts.getFirst().get("ordinal")).longValue());assertEquals(3,attempts.getFirst().get("attempt_number"));assertEquals("succeeded",attempts.getFirst().get("state"));
            assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void catalogueQuestionsUpgradePreservesLegacyOrdersAndNewAnswers(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);String offering,legacy;
        try(var db=store.open()){
            String actor=PlatformFoundationTest.bind(db);offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained questions",0,true)).get("offering_id").toString();legacy=Catalogue.order(db,offering,Values.map("revision",1,"details","Legacy order","key",Values.id())).get("sys_id").toString();
            db.update("ALTER TABLE os_offering DROP COLUMN questions");db.update("ALTER TABLE os_catalog_order DROP COLUMN answers");db.update("DELETE FROM os_migration WHERE version>=45");db.connection.commit();Migrations.apply(db);assertEquals("[]",db.one("SELECT answers FROM os_catalog_order WHERE request_id=?",legacy).get("answers"));db.connection.rollback();assertEquals(44,db.count("SELECT MAX(version) FROM os_migration"));
        }
        String request;
        try(var db=new Store(environment).open()){
            String actor=PlatformFoundationTest.bind(db);assertTrue(CatalogueQuestions.answers(db,legacy).isEmpty());assertTrue(Values.objects(Catalogue.find(db,offering,true).get("questions")).isEmpty());
            CatalogueQuestions.save(db,actor,offering,CatalogueQuestionsAcceptance.schema(1,List.of(CatalogueQuestionsAcceptance.question("reason","string",true,List.of()))));request=Catalogue.order(db,offering,Values.map("revision",2,"details","","key",Values.id(),"answers",Values.map("reason","Retained answer"))).get("sys_id").toString();db.connection.commit();
        }
        try(var db=new Store(environment).open()){
            PlatformFoundationTest.bind(db);assertEquals("Retained answer",CatalogueQuestions.answers(db,request).getFirst().get("value"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void routedFulfilmentUpgradeIsAtomicAndRetainsTasks(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);
        try(var db=store.open()){
            db.update("DROP TABLE os_fulfilment");db.update("ALTER TABLE os_offering DROP COLUMN fulfilment_group_id");db.update("DELETE FROM os_migration WHERE version>=44");db.connection.commit();
            Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_fulfilment"));db.connection.rollback();assertEquals(43,db.count("SELECT MAX(version) FROM os_migration"));
        }
        String offering,request;
        try(var db=new Store(environment).open()){
            String actor=PlatformFoundationTest.bind(db),group=Service.create(db,"sys_user_group",Values.map("short_description","Retained fulfilment team"),"admin").get("sys_id").toString();
            offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained routed offering",0,true)).get("offering_id").toString();assertEquals("",Catalogue.find(db,offering,true).get("fulfilment_group_id"));
            CatalogueRouting.save(db,actor,offering,Values.map("group_id",group,"revision",1));request=Catalogue.order(db,offering,Values.map("revision",2,"key",Values.id(),"details","Retain delivery")).get("sys_id").toString();db.connection.commit();
        }
        try(var db=new Store(environment).open()){
            PlatformFoundationTest.bind(db);assertEquals("queued",Fulfilment.detail(db,request).get("state"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void catalogueEligibilityUpgradePreservesExistingOfferings(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);String offering,group;
        try(var db=store.open()) {
            String actor=PlatformFoundationTest.bind(db);
            offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained offering",0,true)).get("offering_id").toString();
            group=Service.create(db,"sys_user_group",Values.map("short_description","Required group"),"admin").get("sys_id").toString();
            db.update("ALTER TABLE os_offering DROP COLUMN group_id");db.update("DELETE FROM os_migration WHERE version>=43");db.connection.commit();
            Migrations.apply(db);assertEquals("",db.one("SELECT group_id FROM os_offering WHERE offering_id=?",offering).get("group_id"));
            db.connection.rollback();assertEquals(42,db.count("SELECT MAX(version) FROM os_migration"));
        }
        try(var db=new Store(environment).open()) {
            String actor=PlatformFoundationTest.bind(db);
            assertEquals("Retained offering",Catalogue.find(db,offering,true).get("name"));
            CatalogueEligibility.save(db,actor,offering,Values.map("group_id",group,"revision",1));db.connection.commit();
        }
        try(var db=new Store(environment).open()) {
            PlatformFoundationTest.bind(db);assertEquals(group,Catalogue.find(db,offering,true).get("group_id"));
            assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));
            assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void configurationHistoryUpgradeIsAtomicAndPreservesReceipts(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);
        try(var db=store.open()) {
            db.update("DROP TABLE os_configuration_install");db.update("DELETE FROM os_migration WHERE version>=42");db.connection.commit();
            Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_configuration_install"));
            db.connection.rollback();assertEquals(41,db.count("SELECT MAX(version) FROM os_migration"));
        }
        String receipt;
        try(var db=new Store(environment).open()) {
            String actor=PlatformFoundationTest.bind(db);
            receipt=Configuration.install(db,ConfigurationAcceptance.bundle("u_retained_history",ConfigurationAcceptance.definition()),actor,false).get("installation_id").toString();
            db.connection.commit();
        }
        try(var db=new Store(environment).open()) {
            PlatformFoundationTest.bind(db);
            assertEquals(receipt,ConfigurationHistory.detail(db,receipt).get("install_id"));
            assertEquals(1,Service.schema(db,"u_retained_history").get("version"));
            assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));
            assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void clockPurposeUpgradeAndConcurrentStartsRemainSafe(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);String record,calendar="purpose-upgrade";
        try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);record=PlatformFoundationTest.record(db,"incident","Retained clock");ServiceClocks.calendar(db,actor,Values.map("name",calendar,"calendar",PlatformFoundationTest.calendar()));ServiceClocks.start(db,actor,Values.map("table","incident","record_id",record,"calendar",calendar,"minutes",120),System.currentTimeMillis());db.update("DROP INDEX ix_os_sla_active_purpose");db.update("ALTER TABLE os_sla DROP COLUMN purpose");db.update("DELETE FROM os_migration WHERE version>=41");db.connection.commit();Migrations.apply(db);assertEquals("general",db.one("SELECT purpose FROM os_sla").get("purpose"));db.connection.rollback();assertEquals(40,db.count("SELECT MAX(version) FROM os_migration"));}
        var upgraded=new Store(env);try(var executor=Executors.newFixedThreadPool(2)){var begin=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(executor.submit(()->{begin.await();try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db);try{ServiceClocks.start(db,actor,Values.map("table","incident","record_id",record,"calendar",calendar,"minutes",120,"purpose","response"),System.currentTimeMillis());db.connection.commit();return 200;}catch(Values.Failure conflict){return conflict.status;}}}));begin.countDown();assertEquals(Set.of(200,409),Set.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS)));}
        try(var db=new Store(env).open()){assertEquals(2,db.count("SELECT COUNT(*) FROM os_sla"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_sla WHERE purpose='general'"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_sla WHERE purpose='response'"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void knowledgeFeedbackUpgradeRollsBackAndRetainsReviews(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){db.update("DROP TABLE os_knowledge_feedback");db.update("DELETE FROM os_migration WHERE version>=39");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_knowledge_feedback"));db.connection.rollback();assertEquals(38,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){db.update("INSERT INTO os_knowledge_feedback(feedback_id,article_id,version,user_id,helpful,details,updated_at,reviewed,review_note) VALUES('feedback','article',1,'user',0,'Retained feedback',1,1,'Retained review')");db.connection.commit();}
        try(var db=new Store(env).open()){assertEquals("Retained review",db.one("SELECT review_note FROM os_knowledge_feedback WHERE feedback_id='feedback'").get("review_note"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void profileUpgradePreservesAccountsAndRepeatsSafely(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){
            db.update("ALTER TABLE os_user DROP COLUMN display_name");db.update("ALTER TABLE os_user DROP COLUMN job_title");db.update("DELETE FROM os_migration WHERE version>=36");db.connection.commit();
        }
        new Store(env);
        try(var db=store.open()){
            assertEquals("",db.one("SELECT display_name FROM os_user WHERE name='admin'").get("display_name"));
            db.update("UPDATE os_user SET display_name='Retained profile',job_title='Operator' WHERE name='admin'");db.connection.commit();
        }
        new Store(env);
        try(var db=store.open()){assertEquals("Retained profile",db.one("SELECT display_name FROM os_user WHERE name='admin'").get("display_name"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void catalogueUpgradeAndConcurrentOrdersPreserveExactlyOneRequest(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){
            db.update("DROP TABLE os_catalog_order");db.update("DROP TABLE os_offering");db.update("DELETE FROM os_migration WHERE version>=37");db.connection.commit();
            Migrations.apply(db);assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));db.connection.rollback();assertEquals(36,db.count("SELECT MAX(version) FROM os_migration"));
        }
        var upgraded=new Store(env);String offering,key=Values.id();
        try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db);offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained offering",0,true)).get("offering_id").toString();db.connection.commit();}
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);List<Future<String>> outcomes=new ArrayList<>();
            for(int i=0;i<2;i++)outcomes.add(executor.submit(()->{start.await();try(var db=upgraded.open()){PlatformFoundationTest.bind(db);String result=Catalogue.order(db,offering,Values.map("revision",1,"details","Concurrent order","key",key)).get("sys_id").toString();db.connection.commit();return result;}}));
            start.countDown();assertEquals(outcomes.get(0).get(30,TimeUnit.SECONDS),outcomes.get(1).get(30,TimeUnit.SECONDS));
        }
        try(var db=new Store(env).open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_catalog_order"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_requester_record"));assertEquals("Retained offering",db.one("SELECT name FROM os_offering").get("name"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void attachmentHoldUpgradePreservesBytesAndRollsBack(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);byte[] content={0,1,-1};
        try(var db=store.open()){db.update("INSERT INTO os_attachment(sys_id,record_id,name,content,content_type) VALUES('retained','parent','file.bin',?,'application/octet-stream')",content);db.update("ALTER TABLE os_attachment DROP COLUMN quarantined");db.update("ALTER TABLE os_attachment DROP COLUMN hold_revision");db.update("DELETE FROM os_migration WHERE version>=38");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT quarantined FROM os_attachment"));db.connection.rollback();assertEquals(37,db.count("SELECT MAX(version) FROM os_migration"));}
        for(int i=0;i<2;i++){try(var db=new Store(env).open()){var file=db.one("SELECT * FROM os_attachment WHERE sys_id='retained'");assertArrayEquals(content,(byte[])file.get("content"));assertEquals(0,file.get("quarantined"));assertEquals(0,file.get("hold_revision"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void eventCaseUpgradeAndConcurrentEscalationsKeepOneIncident(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){db.update("DROP TABLE os_event_case");db.update("DELETE FROM os_migration WHERE version>=40");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_event_case"));db.connection.rollback();assertEquals(39,db.count("SELECT MAX(version) FROM os_migration"));}
        var upgraded=new Store(env);String review;
        try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db),asset=Service.create(db,"cmdb_ci",Values.map("short_description","Concurrent event asset"),"admin").get("sys_id").toString();AssetOperations.event(db,actor,Values.map("key","concurrent-event","asset_id",asset,"severity","warning","summary","Review retained event"),1);review=EventCases.page(db,"concurrent-event").get("review").toString();db.connection.commit();}
        try(var executor=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);List<Future<String>> outcomes=new ArrayList<>();for(int i=0;i<2;i++)outcomes.add(executor.submit(()->{start.await();try(var db=upgraded.open()){PlatformFoundationTest.bind(db);String result=EventCases.create(db,Values.map("key","concurrent-event","review",review)).get("sys_id").toString();db.connection.commit();return result;}}));start.countDown();assertEquals(outcomes.get(0).get(30,TimeUnit.SECONDS),outcomes.get(1).get(30,TimeUnit.SECONDS));}
        try(var db=new Store(env).open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_event_case"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='incident'"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));}
    }

    @TempDir Path root;

    MockEnvironment configuration(boolean postgres) throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("migration.db");
        if (postgres) {
            URI uri =
                    URI.create(DockerDatabase.url().replace("postgresql+psycopg:", "postgresql:"));
            String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
            url =
                    "jdbc:postgresql://"
                            + uri.getHost()
                            + ":"
                            + uri.getPort()
                            + uri.getPath()
                            + "?user=openservice&password=disposable-integration-password";
            try (var connection = DriverManager.getConnection(url);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            url += "&currentSchema=" + schema;
        }
        return new MockEnvironment()
                .withProperty("DATABASE_URL", url)
                .withProperty("SECRET_KEY", "migration-test-secret")
                .withProperty("ADMIN_PASSWORD", "migration-test-password");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void browserInventoryUpgradeIsTransactionalAndRepeatable(boolean postgres) throws Exception {
        var environment = configuration(postgres);
        var store = new Store(environment);
        try (var db = store.open()) {
            db.update("DROP TABLE os_browser_session");
            db.update("DELETE FROM os_migration WHERE version>=21");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            db.connection.rollback();
            assertEquals(20, db.count("SELECT COUNT(*) FROM os_migration"));
        }
        store = new Store(environment);
        try (var db = store.open()) {
            BrowserSessions.register(db, db.one("SELECT * FROM os_user WHERE name='admin'"),
                    "persisted-test-session", System.currentTimeMillis());
            db.connection.commit();
        }
        store = new Store(environment);
        try (var db = store.open()) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void eventDispositionUpgradePreservesExistingOccurrences(boolean postgres) throws Exception {
        var env=configuration(postgres);
        var store=new Store(env);
        try(var db=store.open()) {
            db.update("ALTER TABLE os_operational_event DROP COLUMN state");
            db.update("DELETE FROM os_migration WHERE version>=34");
            db.update("INSERT INTO os_operational_event(event_key,asset_id,severity,summary,occurrences,first_seen,last_seen) VALUES('retained','asset','critical','Preserve this',7,100,200)");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals("open",db.one("SELECT state FROM os_operational_event").get("state"));
            db.connection.rollback();
            assertEquals(33,db.count("SELECT MAX(version) FROM os_migration"));
        }
        for(int i=0;i<2;i++) {
            store=new Store(env);
            try(var db=store.open()) {
                var event=db.one("SELECT * FROM os_operational_event WHERE event_key='retained'");
                assertEquals("open",event.get("state"));
                assertEquals(7,((Number)event.get("occurrences")).intValue());
                assertEquals("Preserve this",event.get("summary"));
                assertEquals(200,((Number)event.get("last_seen")).intValue());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordRecoveryIsAtomicAndSingleUseUnderConcurrency(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String token, target;
        try (var db = store.open()) {
            target =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            token =
                    RecoveryLinks.issue(
                                    db,
                                    db.one("SELECT * FROM os_user WHERE user_id=?", target),
                                    target,
                                    Values.map("current_password", "migration-test-password"))
                            .get("reset_path")
                            .toString()
                            .substring(9);
            db.connection.commit();
        }
        try (var db = store.open()) {
            RecoveryLinks.recover(db, token, "rolled-back-password");
            db.connection.rollback();
            assertTrue(
                    Passwords.matches(
                            db.one("SELECT password FROM os_user WHERE user_id=?", target)
                                    .get("password")
                                    .toString(),
                            "migration-test-password"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_recovery"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event"));
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int n = 0; n < 2; n++)
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    RecoveryLinks.recover(db, token, "accepted-reset-password");
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(1, statuses.stream().filter(status -> status == 400).count());
        }
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_recovery"));
            assertEquals(2, db.count("SELECT COUNT(*) FROM os_security_event"));
            var user =
                    db.one(
                            "SELECT password,auth_revision,active FROM os_user WHERE user_id=?",
                            target);
            assertTrue(
                    Passwords.matches(user.get("password").toString(), "accepted-reset-password"));
            assertEquals(1L, ((Number) user.get("auth_revision")).longValue());
            assertEquals(1, ((Number) user.get("active")).intValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyOneConcurrentInvitationAcceptanceCreatesAnAccount(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String token;
        try (var db = store.open()) {
            String actor =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            token =
                    Invitations.issue(
                                    db,
                                    actor,
                                    Values.map("name", "ConcurrentInvite", "role", "agent"))
                            .get("accept_path")
                            .toString()
                            .substring(6);
            db.connection.commit();
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int n = 0; n < 2; n++)
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Invitations.accept(db, token, "invitation-test-password");
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(1, statuses.stream().filter(status -> status == 400).count());
        }
        try (var db = store.open()) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_user WHERE name='ConcurrentInvite'"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_invitation WHERE state='accepted'"));
            assertEquals(3, db.count("SELECT COUNT(*) FROM os_security_event"));
            for (String action : List.of("invitation.issued", "account.created", "account.invitation_accepted"))
                assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event WHERE action=?", action));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordChangeRechecksCallerAndRollsBackBusinessMutation(boolean postgres)
            throws Exception {
        var store = new Store(configuration(postgres));
        try (var db = store.open()) {
            var caller = db.one("SELECT * FROM os_user WHERE name='admin'");
            var body =
                    Values.map(
                            "current_password",
                            "migration-test-password",
                            "new_password",
                            "next-private-password");
            String originalHash = caller.get("password").toString();
            db.update("UPDATE os_user SET active=0 WHERE name='admin'");
            db.connection.commit();
            assertEquals(
                    401,
                    assertThrows(
                                    Values.Failure.class,
                                    () -> Service.changePassword(db, caller, body))
                            .status);
            db.update("UPDATE os_user SET active=1 WHERE name='admin'");
            db.connection.commit();
            caller.put("auth_revision", -1L);
            assertEquals(
                    401,
                    assertThrows(
                                    Values.Failure.class,
                                    () -> Service.changePassword(db, caller, body))
                            .status);
            db.connection.rollback();
            assertEquals(
                    originalHash,
                    db.one("SELECT password FROM os_user WHERE name='admin'").get("password"));
            caller.put("auth_revision", 0L);
            Service.changePassword(db, caller, body);
            db.connection.rollback();
            assertEquals(
                    originalHash,
                    db.one("SELECT password FROM os_user WHERE name='admin'").get("password"));
            assertEquals(
                    0L,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.password_changed'"));
            assertEquals(
                    2L,
                    db.count(
                            "SELECT COUNT(*) FROM os_auth_event WHERE mechanism='password-change'"
                                + " AND outcome='succeeded'"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentPasswordChangesCannotReuseThePreviousSecret(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String id;
        Map<String, Object> caller;
        try (var db = store.open()) {
            caller = db.one("SELECT * FROM os_user WHERE name='admin'");
            id = caller.get("user_id").toString();
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String next : List.of("first-new-password", "second-new-password"))
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var db = store.open()) {
                                        try {
                                            Service.changePassword(
                                                    db,
                                                    caller,
                                                    Values.map(
                                                            "current_password",
                                                            "migration-test-password",
                                                            "new_password",
                                                            next));
                                            db.connection.commit();
                                            return 200;
                                        } catch (Values.Failure failure) {
                                            db.connection.rollback();
                                            return failure.status;
                                        }
                                    }
                                }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(
                    1, statuses.stream().filter(status -> status == 400 || status == 401).count());
        }
        try (var db = store.open()) {
            var user = db.one("SELECT password,auth_revision FROM os_user WHERE user_id=?", id);
            assertFalse(
                    Passwords.matches(user.get("password").toString(), "migration-test-password"));
            assertEquals(1, ((Number) user.get("auth_revision")).longValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyUpgradeIsAtomicRepeatableAndPreservesData(boolean postgres) throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                    "legacy",
                    "parent",
                    "file.bin",
                    new byte[] {0, 1, -1});
            db.update("ALTER TABLE os_attachment DROP COLUMN content_type");
            db.update("DROP TABLE os_migration");
            db.connection.commit();
        }
        new Store(config);
        new Store(config.withProperty("ADMIN_PASSWORD", "changed-bootstrap-password"));
        try (var db = store.open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            var file = db.one("SELECT * FROM os_attachment WHERE sys_id='legacy'");
            assertArrayEquals(new byte[] {0, 1, -1}, (byte[]) file.get("content"));
            assertEquals("application/octet-stream", file.get("content_type"));
            assertTrue(
                    Passwords.matches(
                            (String)
                                    db.one("SELECT password FROM os_user WHERE name='admin'")
                                            .get("password"),
                            "migration-test-password"));
            db.update("INSERT INTO os_migration(version,name) VALUES(99,'future')");
            db.connection.commit();
        }
        assertThrows(SQLException.class, () -> new Store(config));
        try (var db = store.open()) {
            db.update("DELETE FROM os_migration");
            db.update("INSERT INTO os_migration(version,name) VALUES(0,'invalid')");
            db.connection.commit();
        }
        assertThrows(SQLException.class, () -> new Store(config));
        try (var db = store.open()) {
            db.update("DELETE FROM os_migration");
            db.update("ALTER TABLE os_attachment DROP COLUMN content_type");
            db.connection.commit();
        }
        // Failure after DDL must roll back both the schema change and its journal entry.
        try (var db = store.open()) {
            Migrations.lock(db);
            Migrations.apply(db);
            assertThrows(
                    SQLException.class, () -> db.update("INSERT INTO missing_table VALUES(1)"));
            db.connection.rollback();
        }
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_migration"));
            try (var columns =
                    db.connection
                            .getMetaData()
                            .getColumns(
                                    null,
                                    db.connection.getSchema(),
                                    "os_attachment",
                                    "content_type")) {
                assertFalse(columns.next());
            }
        }
        new Store(config);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyIdentitiesBackfillWithoutChangingCredentials(boolean postgres) throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        String hash;
        try (var db = store.open()) {
            hash =
                    (String)
                            db.one("SELECT password FROM os_user WHERE name='admin'")
                                    .get("password");
            db.update("DROP INDEX ix_os_user_identity");
            db.update("ALTER TABLE os_user DROP COLUMN user_id");
            db.update("DELETE FROM os_migration WHERE version>=2");
            db.update("INSERT INTO os_user(name,password,role) VALUES('legacy',?,'reader')", hash);
            db.connection.commit();
        }
        new Store(config);
        List<Map<String, Object>> before;
        try (var db = store.open()) {
            before = db.all("SELECT * FROM os_user ORDER BY name");
            assertEquals(2, before.size());
            for (var user : before) {
                assertTrue(user.get("user_id").toString().matches("[0-9a-f]{32}"));
                assertEquals(hash, user.get("password"));
            }
            assertNotEquals(before.get(0).get("user_id"), before.get(1).get("user_id"));
            assertThrows(
                    SQLException.class,
                    () ->
                            db.update(
                                    "UPDATE os_user SET user_id=? WHERE name='legacy'",
                                    before.get(0).get("user_id")));
            db.connection.rollback();
        }
        new Store(config);
        try (var db = store.open()) {
            assertEquals(before, db.all("SELECT * FROM os_user ORDER BY name"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adoptsAlreadyUpgradedInstallationsWithoutReplacingMetadata(boolean postgres)
            throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content,content_type)"
                            + " VALUES(?,?,?,?,?)",
                    "existing",
                    "parent",
                    "file.pdf",
                    new byte[] {7},
                    "application/pdf");
            db.update("DROP TABLE os_migration");
            db.connection.commit();
        }
        new Store(config);
        try (var db = store.open()) {
            assertEquals(
                    "application/pdf",
                    db.one("SELECT content_type FROM os_attachment WHERE sys_id='existing'")
                            .get("content_type"));
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDeactivationKeepsAnActiveAdministrator(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String admin, second;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    Values.map(
                            "name",
                            "SecondAdmin",
                            "password",
                            "test-only-password-long",
                            "role",
                            "admin"));
            admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            second =
                    db.one("SELECT user_id FROM os_user WHERE name='SecondAdmin'")
                            .get("user_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Values.Failure.class,
                                    () ->
                                            Service.changeActive(
                                                    db,
                                                    "missing",
                                                    second,
                                                    Values.map("active", false)))
                            .status);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String target : List.of(admin, second))
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Service.changeActive(
                                            db, admin, target, Values.map("active", false));
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertTrue(
                    statuses.stream().allMatch(status -> Set.of(200, 403, 409).contains(status)));
        }
        try (var db = store.open()) {
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin'"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDemotionKeepsAnActiveAdministrator(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String admin, second;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    Values.map(
                            "name",
                            "SecondAdmin",
                            "password",
                            "test-only-password-long",
                            "role",
                            "admin"));
            admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            second =
                    db.one("SELECT user_id FROM os_user WHERE name='SecondAdmin'")
                            .get("user_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Values.Failure.class,
                                    () ->
                                            Service.changeRole(
                                                    db,
                                                    "missing",
                                                    second,
                                                    Values.map("role", "reader")))
                            .status);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String target : List.of(admin, second))
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Service.changeRole(
                                            db, admin, target, Values.map("role", "reader"));
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertTrue(
                    statuses.stream().allMatch(status -> Set.of(200, 403, 409).contains(status)));
        }
        try (var db = store.open()) {
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin'"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFreshStartupSerializesBootstrapAndMigration(boolean postgres) throws Exception {
        var config = configuration(postgres);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            List<Future<Store>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        executor.submit(() -> {
                            start.await();
                            return new Store(config);
                        }));
            start.countDown();
            for (var future : futures) {
                try (var db = future.get(30, TimeUnit.SECONDS).open()) {
                    assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
                    assertEquals(1, db.count("SELECT COUNT(*) FROM os_user"));
                    assertEquals(7, db.count("SELECT COUNT(*) FROM os_schema"));
                }
            }
        }
    }
}
