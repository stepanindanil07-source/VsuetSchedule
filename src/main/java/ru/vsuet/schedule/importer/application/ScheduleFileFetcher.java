package ru.vsuet.schedule.importer.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Downloads a schedule file into memory with timeouts, a size limit and retries.
 *
 * <p>Retried: connection problems, timeouts, HTTP 5xx and 429. Not retried (it would not help): 4xx such as 404,
 * a non-http(s) URL, an empty body or a file over the size limit.
 */
@Component
public class ScheduleFileFetcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduleFileFetcher.class);

    private final DownloadProperties properties;
    private final RestClient client;

    ScheduleFileFetcher(DownloadProperties properties) {
        this.properties = properties;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** @throws FileDownloadException when no attempt delivered a usable file */
    public byte[] fetch(String url) {
        URI uri = parse(url);
        FileDownloadException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                return fetchOnce(uri);
            } catch (FileDownloadException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                last = e;
                log.warn("Download of {} failed (attempt {}/{}): {}", uri, attempt, properties.maxAttempts(), e.getMessage());
                if (attempt < properties.maxAttempts()) {
                    pause(attempt);
                }
            }
        }
        throw new FileDownloadException("Download of " + uri + " failed after " + properties.maxAttempts()
                + " attempts: " + (last == null ? "unknown error" : last.getMessage()), false, last);
    }

    private byte[] fetchOnce(URI uri) {
        try {
            return client.get().uri(uri).exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.is5xxServerError() || status.value() == 429) {
                    throw new FileDownloadException("HTTP " + status.value(), true);
                }
                if (!status.is2xxSuccessful()) {
                    throw new FileDownloadException("HTTP " + status.value(), false);
                }
                long limit = properties.maxSize().toBytes();
                byte[] body = response.getBody().readNBytes((int) limit + 1);
                if (body.length > limit) {
                    throw new FileDownloadException("File is larger than the limit of " + properties.maxSize(), false);
                }
                if (body.length == 0) {
                    throw new FileDownloadException("Server returned an empty file", false);
                }
                return body;
            });
        } catch (RestClientException e) {
            // connect/read timeouts and other I/O problems surface as RestClientException subclasses
            throw new FileDownloadException(e.getMessage(), true, e);
        }
    }

    private static URI parse(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new FileDownloadException("Only http(s) URLs are supported: " + url, false);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new FileDownloadException("Invalid URL: " + url, false, e);
        }
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(properties.retryDelay().multipliedBy(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileDownloadException("Interrupted while waiting to retry", false, e);
        }
    }
}
