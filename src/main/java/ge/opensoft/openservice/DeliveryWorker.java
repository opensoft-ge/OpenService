package ge.opensoft.openservice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@EnableScheduling
final class DeliveryWorker {
    interface Sender {
        void send(String id, Map<String, Object> payload) throws Exception;
    }

    private final Store store;
    private final Sender sender;

    @Autowired
    DeliveryWorker(Store store, Environment environment) {
        this(
                store,
                environment.getProperty("DELIVERY_ENABLED", Boolean.class, false)
                        ? new SmtpDelivery(environment)
                        : null);
    }

    DeliveryWorker(Store store, Sender sender) {
        this.store = store;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${DELIVERY_POLL_MS:1000}")
    void tick() {
        if (sender == null) return;
        try {
            runOne(System.currentTimeMillis());
        } catch (Exception failure) {
            // Do not log transport messages, email addresses, credentials or token payloads.
            org.slf4j.LoggerFactory.getLogger(DeliveryWorker.class)
                    .warn("Delivery worker unavailable; durable jobs remain pending");
        }
    }

    boolean runOne(long now) throws Exception {
        Map<String, Object> job;
        try (var db = store.open()) {
            job = Outbox.claim(db, now);
            db.connection.commit();
        }
        if (job == null) return false;
        String state = "delivered", error = "";
        try {
            var payload = Outbox.decrypt(store.secret, job.get("envelope").toString());
            boolean valid;
            try (var db = store.open()) {
                valid = EmailOnboarding.valid(db, job.get("kind").toString(), payload);
                db.connection.commit();
            }
            if (valid) sender.send(job.get("job_id").toString(), payload);
            else {
                state = "cancelled";
                error = "LINK_UNAVAILABLE";
            }
        } catch (Exception failure) {
            state = "queued";
            error = "DELIVERY_FAILED";
        }
        try (var db = store.open()) {
            Outbox.finish(db, job, state, error, System.currentTimeMillis());
            db.connection.commit();
        }
        return true;
    }
}
