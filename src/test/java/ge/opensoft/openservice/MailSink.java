package ge.opensoft.openservice;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable loopback SMTP fixture; never forwards mail. */
final class MailSink implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final AtomicInteger reject = new AtomicInteger();
    final Thread worker;

    MailSink() throws IOException {
        worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (!server.isClosed()) {
                                        try (var socket = server.accept();
                                                var in =
                                                        new BufferedReader(
                                                                new InputStreamReader(
                                                                        socket.getInputStream(),
                                                                        StandardCharsets.UTF_8));
                                                var out =
                                                        new PrintWriter(
                                                                new OutputStreamWriter(
                                                                        socket.getOutputStream(),
                                                                        StandardCharsets.UTF_8),
                                                                true)) {
                                            out.print("220 localhost disposable SMTP\r\n");
                                            out.flush();
                                            String line;
                                            while ((line = in.readLine()) != null) {
                                                String response = "250 OK";
                                                if (line.equals("DATA")) {
                                                    out.print("354 End with dot\r\n");
                                                    out.flush();
                                                    var body = new StringBuilder();
                                                    while ((line = in.readLine()) != null
                                                            && !line.equals("."))
                                                        body.append(line).append('\n');
                                                    if (reject.getAndUpdate(n -> Math.max(0, n - 1))
                                                            > 0)
                                                        response = "451 Temporary test failure";
                                                    else messages.add(body.toString());
                                                } else if (line.equals("QUIT")) {
                                                    out.print("221 Bye\r\n");
                                                    out.flush();
                                                    break;
                                                }
                                                out.print(response + "\r\n");
                                                out.flush();
                                            }
                                        } catch (IOException error) {
                                            if (!server.isClosed())
                                                throw new UncheckedIOException(error);
                                        }
                                    }
                                });
    }

    String receive() throws InterruptedException {
        String message = messages.poll(5, TimeUnit.SECONDS);
        if (message == null) throw new AssertionError("No SMTP message received");
        return message;
    }

    public void close() throws Exception {
        server.close();
        worker.join(1000);
    }
}
