package forge.gamemodes.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** HTTP client for the Forge Lobby room directory. */
public final class ForgeLobbyClient {
    public static final URI PUBLIC_ENDPOINT = URI.create("https://forge-lobby.pages.dev/api/v1/rooms");

    @FunctionalInterface
    public interface Transport {
        Response request(URI uri, String method, String token, String body, boolean readBody)
                throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    private final URI endpoint;
    private final Transport transport;

    public ForgeLobbyClient(final URI endpoint, final Transport transport) {
        this.endpoint = endpoint;
        this.transport = transport;
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

    public List<Room> list() throws IOException, InterruptedException {
        final List<Room> result = new ArrayList<>();
        try {
            for (final JsonElement item : request("GET", null, null, 200, true).getAsJsonArray("rooms")) {
                final JsonObject room = item.getAsJsonObject();
                result.add(new Room(string(room, "name"), string(room, "format"), string(room, "language"),
                        string(room, "address"), room.get("port").getAsInt(), string(room, "version"),
                        string(room, "status"), room.get("availableSlots").getAsInt()));
            }
        } catch (final IllegalStateException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid Forge Lobby room list", e);
        }
        return result;
    }

    public List<Room> listJoinable() throws IOException, InterruptedException {
        return list().stream().filter(room -> room != null && "open".equals(room.status())
                && room.availableSlots() > 0 && room.address() != null
                && !room.address().trim().isEmpty() && room.port() > 0 && room.port() <= 65535)
                .collect(Collectors.toList());
    }

    public Publication publish(final Announcement announcement) throws IOException, InterruptedException {
        final JsonObject room = request("POST", null, toJson(announcement), 201, true);
        final String id = string(room, "id");
        final String token = string(room, "token");
        if (id == null || token == null) {
            throw new IOException("Incomplete Forge Lobby publication");
        }
        return new Publication(id, token);
    }

    public void update(final Publication publication, final Announcement announcement, final String status)
            throws IOException, InterruptedException {
        final JsonObject body = toJson(announcement);
        body.addProperty("status", status);
        request("PATCH", publication, body, 200, false);
    }

    public void close(final Publication publication) throws IOException, InterruptedException {
        request("DELETE", publication, null, 200, false);
    }

    private static JsonObject toJson(final Announcement announcement) {
        final JsonObject body = new JsonObject();
        body.addProperty("name", announcement.name());
        body.addProperty("format", announcement.format());
        body.addProperty("language", announcement.language());
        body.addProperty("capacity", announcement.capacity());
        body.addProperty("players", announcement.players());
        body.addProperty("address", announcement.address());
        body.addProperty("port", announcement.port());
        body.addProperty("version", announcement.version());
        body.addProperty("description", announcement.description());
        body.addProperty("publicAddressAccepted", announcement.publicAddressAccepted());
        return body;
    }

    private static String string(final JsonObject object, final String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private JsonObject request(final String method, final Publication publication, final JsonObject body,
                               final int expected, final boolean readBody) throws IOException, InterruptedException {
        final URI uri = publication == null ? endpoint : URI.create(
                endpoint + "?id=" + URLEncoder.encode(publication.id(), StandardCharsets.UTF_8.name()));
        final Response response = transport.request(uri, method, publication == null ? null : publication.token(),
                body == null ? null : body.toString(), readBody);
        if (response.status() != expected) {
            throw new IOException("Forge Lobby HTTP " + response.status());
        }
        if (!readBody) {
            return null;
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (final JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid Forge Lobby response", e);
        }
    }
}
