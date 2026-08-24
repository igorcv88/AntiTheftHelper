package com.igorcv.antithefthelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class TelegramClient {
    static final class Result {
        final boolean ok;
        final int code;
        final String body;

        Result(boolean ok, int code, String body) {
            this.ok = ok;
            this.code = code;
            this.body = body;
        }
    }

    static Result sendMessage(String token, String chatId, String text) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            String body = "chat_id=" + enc(chatId)
                    + "&text=" + enc(text)
                    + "&disable_web_page_preview=true";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(bytes);
            }

            return result(connection);
        } catch (Exception e) {
            return new Result(false, -1, describe(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static Result sendPhoto(String token, String chatId, String caption, File photo) {
        if (photo == null || !photo.isFile() || photo.length() == 0) {
            return new Result(false, -1, "Photo file missing or empty");
        }

        HttpURLConnection connection = null;
        String boundary = "----AntiTheftHelper" + System.currentTimeMillis();
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendPhoto");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(8192);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream out = connection.getOutputStream()) {
                writeField(out, boundary, "chat_id", chatId);
                writeField(out, boundary, "caption", caption == null ? "" : caption);

                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"photo\"; filename=\"antitheft-front.jpg\"\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                try (FileInputStream in = new FileInputStream(photo)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            return result(connection);
        } catch (Exception e) {
            return new Result(false, -1, describe(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static Result result(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = readAll(stream);
        return new Result(code >= 200 && code < 300 && response.contains("\"ok\":true"), code, response);
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
