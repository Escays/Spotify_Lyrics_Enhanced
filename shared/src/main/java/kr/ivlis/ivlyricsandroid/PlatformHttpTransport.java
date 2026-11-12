package kr.ivlis.ivlyricsandroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP transport shared by the platform lyric sources. The transport is an interface so the matching
 * and merging logic can be exercised on the JVM with recorded payloads instead of the network.
 */
final class PlatformHttpTransport {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36 Edg/152.0.0.0";

    interface Transport {
        Response get(String url, Map<String, String> headers) throws Exception;

        Response post(String url, Map<String, String> headers, String body) throws Exception;
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private PlatformHttpTransport() {
    }

    static Transport http() {
        return new Transport() {
            @Override
            public Response get(String url, Map<String, String> headers) throws Exception {
                return request(url, "GET", headers, null);
            }

            @Override
            public Response post(String url, Map<String, String> headers, String body) throws Exception {
                return request(url, "POST", headers, body);
            }
        };
    }

    static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            headers.put(pairs[index], pairs[index + 1]);
        }
        return headers;
    }

    private static Response request(String endpoint, String method, Map<String, String> headers,
            String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String payload = read(stream);
        connection.disconnect();
        return new Response(status, payload);
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8_192];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, count);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** QQ answers some endpoints with a JSONP wrapper even when JSON was requested. */
    static String stripJsonp(String body) {
        String value = body == null ? "" : body.trim();
        int open = value.indexOf('(');
        int close = value.lastIndexOf(')');
        if (!value.startsWith("{") && !value.startsWith("[") && open > 0 && close > open) {
            return value.substring(open + 1, close).trim();
        }
        return value;
    }
}
