package ge.opensoft.openservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class WorkflowTest extends WorkflowSupport {
    @org.junit.jupiter.api.Test void sqliteContentionRejectsBeforeBusinessWorkAndRecovers() throws Exception { SQLiteContentionAcceptance.run(this); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ContractSupport.properties(registry);
    }
}
