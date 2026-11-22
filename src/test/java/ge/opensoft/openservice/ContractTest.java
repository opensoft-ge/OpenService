package ge.opensoft.openservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        args = "--spring.main.banner-mode=off",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ContractTest extends ContractSupport {
    private static final java.nio.file.Path FIXTURE_DATABASE = database();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ContractSupport.properties(registry);
        registry.add("DATABASE_URL", () -> System.getenv().getOrDefault(
                "OPEN_SERVICE_TEST_DATABASE_URL", "sqlite:///" + FIXTURE_DATABASE));
    }
}
