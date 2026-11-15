package ge.opensoft.openservice;

import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.net.URI;
import java.util.*;

final class SmtpDelivery implements DeliveryWorker.Sender {
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final String base;

    SmtpDelivery(Environment environment) {
        base = base(environment);
        from = EmailOnboarding.email(environment.getProperty("MAIL_FROM", ""));
        String host = environment.getRequiredProperty("MAIL_HOST");
        boolean tls = environment.getProperty("MAIL_STARTTLS", Boolean.class, true);
        if (!tls
                && !(environment.getProperty("MAIL_ALLOW_INSECURE_LOCAL", Boolean.class, false)
                        && Set.of("localhost", "127.0.0.1", "::1").contains(host)))
            throw new IllegalArgumentException(
                    "SMTP requires STARTTLS outside an explicit loopback mail sink");
        sender.setHost(host);
        sender.setPort(environment.getProperty("MAIL_PORT", Integer.class, 587));
        sender.setUsername(environment.getProperty("MAIL_USERNAME"));
        sender.setPassword(environment.getProperty("MAIL_PASSWORD"));
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(sender.getUsername() != null));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(tls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(tls));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
    }

    static String base(Environment environment) {
        String value = environment.getProperty("PUBLIC_BASE_URL", "");
        URI uri = URI.create(value);
        if (!("https".equals(uri.getScheme())
                        || "http".equals(uri.getScheme())
                                && environment.getProperty(
                                        "MAIL_ALLOW_INSECURE_LOCAL", Boolean.class, false)
                                && Set.of("localhost", "127.0.0.1", "[::1]")
                                        .contains(Objects.toString(uri.getHost(), "")))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !Set.of("", "/").contains(uri.getPath()))
            throw new IllegalArgumentException(
                    "PUBLIC_BASE_URL must be an HTTPS origin (or an explicit loopback test"
                        + " origin)");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public void send(String id, Map<String, Object> payload) throws Exception {
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(from);
        helper.setTo(payload.get("email").toString());
        helper.setSubject("OpenService account access");
        helper.setText(
                "Use this single-use link to complete your OpenService account request:\n\n"
                        + base
                        + payload.get("path")
                        + "\n\nIf you did not expect this message, ignore it.");
        message.saveChanges();
        message.setHeader("Message-ID", "<" + id + "@openservice.invalid>");
        sender.send(message);
    }
}
