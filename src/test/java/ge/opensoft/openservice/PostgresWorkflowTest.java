package ge.opensoft.openservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class PostgresWorkflowTest extends WorkflowSupport {
    @org.junit.jupiter.api.Test void investigationReadingDoesNotLockRelatedRecords() throws Exception {
        String incident=add("incident").get("sys_id").toString(),problem=add("problem").get("sys_id").toString();
        var input=ProblemInvestigationsAcceptance.link(this,incident,"link");input.put("problem_id",problem);
        ProblemInvestigationsAcceptance.result(json("POST",ProblemInvestigationsAcceptance.api("incident",incident),input,auth("admin")));
        try(var held=store.open()){
            held.one("SELECT sys_id FROM os_record WHERE sys_id=? FOR UPDATE",problem);
            var response=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return get(ProblemInvestigationsAcceptance.api("incident",incident),auth("admin"));}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
            try{org.junit.jupiter.api.Assertions.assertEquals(200,response.get(3,java.util.concurrent.TimeUnit.SECONDS).statusCode());}
            finally{held.connection.rollback();response.get(10,java.util.concurrent.TimeUnit.SECONDS);}
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ContractSupport.properties(registry);
        registry.add("DATABASE_URL", DockerDatabase::url);
    }
}
