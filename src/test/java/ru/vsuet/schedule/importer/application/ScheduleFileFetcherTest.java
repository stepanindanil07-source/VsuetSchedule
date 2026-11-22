package ru.vsuet.schedule.importer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** Plain unit tests against a throw-away local HTTP server: no Spring context, no network. */
class ScheduleFileFetcherTest {

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // several threads, so that a deliberately slow handler does not block the retry request
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static ScheduleFileFetcher fetcher(int attempts, Duration readTimeout, DataSize maxSize) {
        return new ScheduleFileFetcher(new DownloadProperties(Duration.ofSeconds(2), readTimeout, attempts,
                Duration.ofMillis(5), maxSize));
    }

    private void respond(String path, java.util.function.IntFunction<Integer> statusForCall, byte[] body) {
        server.createContext(path, exchange -> {
            int status = statusForCall.apply(calls.incrementAndGet());
            if (status == 200) {
                exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            } else {
                exchange.sendResponseHeaders(status, -1);
            }
            exchange.close();
        });
    }

    @Test
    void downloadsTheFile() {
        respond("/f.xlsx", call -> 200, new byte[] {1, 2, 3});

        byte[] body = fetcher(3, Duration.ofSeconds(2), DataSize.ofMegabytes(1)).fetch(url("/f.xlsx"));

        assertThat(body).containsExactly(1, 2, 3);
        assertThat(calls).hasValue(1);
    }

    @Test
    void retriesServerErrorsUntilTheServerRecovers() {
        respond("/f.xlsx", call -> call < 3 ? 503 : 200, new byte[] {9});

        byte[] body = fetcher(4, Duration.ofSeconds(2), DataSize.ofMegabytes(1)).fetch(url("/f.xlsx"));

        assertThat(body).containsExactly(9);
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfAttempts() {
        respond("/f.xlsx", call -> 500, new byte[0]);

        assertThatThrownBy(() -> fetcher(3, Duration.ofSeconds(2), DataSize.ofMegabytes(1)).fetch(url("/f.xlsx")))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("after 3 attempts")
                .hasMessageContaining("HTTP 500");
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryWhenTheFileIsNotThere() {
        respond("/f.xlsx", call -> 404, new byte[0]);

        assertThatThrownBy(() -> fetcher(4, Duration.ofSeconds(2), DataSize.ofMegabytes(1)).fetch(url("/f.xlsx")))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(calls).hasValue(1);
    }

    @Test
    void retriesWhenTheServerAnswersTooSlowly() {
        server.createContext("/slow.xlsx", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(800);
                exchange.sendResponseHeaders(200, 1);
                exchange.getResponseBody().write(1);
            } catch (InterruptedException | IOException ignored) {
                // the client gave up and closed the connection: exactly what this test provokes
            } finally {
                exchange.close();
            }
        });

        assertThatThrownBy(() -> fetcher(2, Duration.ofMillis(200), DataSize.ofMegabytes(1)).fetch(url("/slow.xlsx")))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("after 2 attempts");
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsAFileOverTheSizeLimitWithoutRetrying() {
        respond("/big.xlsx", call -> 200, new byte[2048]);

        assertThatThrownBy(() -> fetcher(3, Duration.ofSeconds(2), DataSize.ofBytes(1024)).fetch(url("/big.xlsx")))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("larger than the limit");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsAnEmptyBody() {
        respond("/empty.xlsx", call -> 200, new byte[0]);

        assertThatThrownBy(() -> fetcher(3, Duration.ofSeconds(2), DataSize.ofMegabytes(1)).fetch(url("/empty.xlsx")))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void followsRedirects() {
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", url("/new"));
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        respond("/new", call -> 200, new byte[] {7});

        assertThat(fetcher(1, Duration.ofSeconds(2), DataSize.ofMegabytes(1)).fetch(url("/old"))).containsExactly(7);
    }

    @Test
    void onlyHttpUrlsAreAccepted() {
        ScheduleFileFetcher fetcher = fetcher(1, Duration.ofSeconds(2), DataSize.ofMegabytes(1));

        assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd")).isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> fetcher.fetch("not a url")).isInstanceOf(FileDownloadException.class);
    }

    @Test
    void unreachableServerIsReportedAsDownloadFailure() {
        int closedPort = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> fetcher(2, Duration.ofSeconds(1), DataSize.ofMegabytes(1))
                .fetch("http://127.0.0.1:" + closedPort + "/f.xlsx"))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("after 2 attempts");
    }
}
