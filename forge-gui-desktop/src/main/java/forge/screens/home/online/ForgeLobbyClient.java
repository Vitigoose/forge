package forge.screens.home.online;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** HTTP client for the Forge Lobby room directory. */
final class ForgeLobbyClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final Gson JSON = new Gson();

    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public ForgeLobbyClient(final URI endpoint) {
        this.endpoint = endpoint;
    }

    public record Announcement(String name, String format, String language, int capacity,
                               int players, String address, int port, String version, String description,
                               boolean publicAddressAccepted) {}

    public record Publication(String id, String token) {
        @Override
        public String toString() {
            return id;
        }
    }

    public record Room(String name, String format, String language, String address, int port,
                       String version, String status, int availableSlots) {
        public String connectionAddress() {
            return (address.contains(":") ? "[" + address + "]" : address) + ":" + port;
        }
    }

    private record RoomList(List<Room> rooms) {}

    public List<Room> list() throws IOException, InterruptedException {
        return request("GET", null, null, 200, RoomList.class).rooms();
    }

    public Publication publish(final Announcement announcement) throws IOException, InterruptedException {
        return request("POST", null, announcement, 201, Publication.class);
    }

    public void update(final Publication publication, final Announcement announcement, final String status)
            throws IOException, InterruptedException {
        final var body = JSON.toJsonTree(announcement).getAsJsonObject();
        body.addProperty("status", status);
        request("PATCH", publication, body, 200, Void.class);
    }

    public void close(final Publication publication) throws IOException, InterruptedException {
        request("DELETE", publication, null, 200, Void.class);
    }

    private <T> T request(final String method, final Publication publication, final Object body,
                          final int expected, final Class<T> responseType) throws IOException, InterruptedException {
        final URI uri = publication == null ? endpoint : URI.create(
                endpoint + "?id=" + URLEncoder.encode(publication.id(), StandardCharsets.UTF_8));
        final HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (publication != null) {
            request.header("Authorization", "Bearer " + publication.token());
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        final HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected) {
            throw new IOException("Forge Lobby HTTP " + response.statusCode());
        }
        if (responseType == Void.class) {
            return null;
        }
        try {
            final T value = JSON.fromJson(response.body(), responseType);
            if (value == null
                    || value instanceof RoomList list && list.rooms() == null
                    || value instanceof Publication room && (room.id() == null || room.token() == null)) {
                throw new IOException("Incomplete Forge Lobby response");
            }
            return value;
        } catch (final JsonParseException e) {
            throw new IOException("Invalid Forge Lobby response", e);
        }
    }
}
