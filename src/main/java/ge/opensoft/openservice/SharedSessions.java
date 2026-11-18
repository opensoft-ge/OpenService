package ge.opensoft.openservice;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
@Configuration(proxyBeanMethods=false)
@EnableSpringHttpSession
@ConditionalOnProperty(name="SHARED_BROWSER_SESSIONS",havingValue="true")
class SharedSessions {
 @Bean SqlSessions sessionRepository(Store store){return new SqlSessions(store);}
 @Bean DefaultCookieSerializer cookieSerializer(Environment environment){var cookie=new DefaultCookieSerializer();cookie.setCookieName("JSESSIONID");cookie.setCookiePath("/");cookie.setUseBase64Encoding(false);cookie.setUseHttpOnlyCookie(true);cookie.setSameSite("Lax");cookie.setUseSecureCookie(Boolean.parseBoolean(environment.getProperty("COOKIE_SECURE","true")));return cookie;}
}
