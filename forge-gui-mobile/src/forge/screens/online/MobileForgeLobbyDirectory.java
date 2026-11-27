package forge.screens.online;

import forge.Forge;
import forge.gamemodes.net.ForgeLobbyClient;
import forge.gamemodes.net.ForgeLobbyClient.Announcement;
import forge.gamemodes.net.ForgeLobbyClient.Room;
import forge.gamemodes.net.ForgeLobbyRoomSync;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.util.SGuiChoose;
import forge.toolbox.FOptionPane;
import io.netty.channel.ChannelFuture;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

final class MobileForgeLobbyDirectory {
    private static final String TITLE = "Forge Lobby";
    private static final ForgeLobbyClient API = new ForgeLobbyClient(ForgeLobbyClient.PUBLIC_ENDPOINT,
            MobileForgeLobbyDirectory::request);
    private static final ScheduledExecutorService REQUESTS = Executors.newSingleThreadScheduledExecutor(task -> {
        final Thread thread = new Thread(task, "Game Forge Lobby directory");
        thread.setDaemon(true);
        return thread;
    });
    private static ChannelFuture publishing;
    private static boolean loading;

    private MobileForgeLobbyDirectory() {}

    private static ForgeLobbyClient.Response request(final URI uri, final String method, final String token,
                                                    final String body, final boolean readBody) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (var output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            final int status = connection.getResponseCode();
            final StringBuilder responseBody = new StringBuilder();
            if (readBody && status >= 200 && status < 300) {
                try (var reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                    final char[] buffer = new char[4096];
                    int length;
                    while ((length = reader.read(buffer)) != -1) {
                        responseBody.append(buffer, 0, length);
                    }
                }
            }
            return new ForgeLobbyClient.Response(status, responseBody.toString());
        } finally {
            connection.disconnect();
        }
    }

    static void browse(final Consumer<String> join) {
        if (loading) {
            return;
        }
        loading = true;
        REQUESTS.execute(() -> {
            try {
                final List<Room> rooms = API.listJoinable();
                if (rooms.isEmpty()) {
                    FThreads.invokeInEdtLater(() -> {
                        loading = false;
                        FOptionPane.showMessageDialog(label("lblForgeLobbyEmpty"), TITLE);
                    });
                    return;
                }
                // The synchronous chooser uses the render thread for its dialog.
                FThreads.invokeInBackgroundThread(() -> {
                    final Room room = SGuiChoose.oneOrNone(label("lblForgeLobbyPickRoom"), rooms, null,
                            MobileForgeLobbyDirectory::roomLabel);
                    FThreads.invokeInEdtLater(() -> {
                        loading = false;
                        if (room != null) {
                            join.accept(room.connectionAddress());
                        }
                    });
                });
            } catch (final IOException | InterruptedException e) {
                FThreads.invokeInEdtLater(() -> {
                    loading = false;
                    FOptionPane.showErrorDialog(label("lblForgeLobbyListError"), TITLE);
                });
            }
        });
    }

    static void publish(final String suggestedAddress) {
        final FServerManager server = FServerManager.getInstance();
        final ChannelFuture closed = server.getServerCloseFuture();
        if (closed == null || closed.isDone()) {
            return;
        }
        if (publishing == closed) {
            FOptionPane.showMessageDialog(label("lblForgeLobbyAlreadyPublishing"), TITLE);
            return;
        }
        FOptionPane.showInputDialog(label("lblForgeLobbyPublicHost"), TITLE, suggestedAddress, null, address -> {
            if (address != null) {
                FOptionPane.showInputDialog(label("lblForgeLobbyRoomName"), TITLE, "", null, name -> {
                    if (name != null) {
                        FOptionPane.showInputDialog(label("lblForgeLobbyLanguage"), TITLE, "Español",
                                List.of("Español", "Inglés", "Portugués", "Francés", "Alemán", "Otro"), language -> {
                                    if (language != null) {
                                        FOptionPane.showConfirmDialog(label("lblForgeLobbyConsent"), TITLE, false,
                                                accepted -> {
                                                    if (accepted) {
                                                        publish(closed, address, name, language);
                                                    }
                                                });
                                    }
                                }, false);
                    }
                }, false);
            }
        }, false);
    }

    private static void publish(final ChannelFuture closed, final String address, final String name, final String language) {
        final FServerManager server = FServerManager.getInstance();
        final var lobby = server.getHostedLobby();
        if (closed != server.getServerCloseFuture() || closed.isDone() || publishing == closed || lobby == null
                || !(closed.channel().localAddress() instanceof InetSocketAddress listeningAddress)) {
            return;
        }
        String host = address.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || name.trim().length() > 120) {
            FOptionPane.showErrorDialog(label("lblForgeLobbyInvalidFields"), TITLE);
            return;
        }

        final var details = new Announcement(name.trim(), "Otro", language, 2, 1, host, listeningAddress.getPort(),
                GuiBase.getInterface().getCurrentVersion(), "", true);
        final var initial = ForgeLobbyRoomSync.snapshot(details, lobby);
        publishing = closed;
        REQUESTS.execute(() -> {
            try {
                if (closed.isDone()) {
                    return;
                }
                final var publication = API.publish(initial.announcement());
                FThreads.invokeInEdtLater(() -> {
                    new ForgeLobbyRoomSync(API, REQUESTS, closed, lobby, publication, initial).start(() -> {
                        if (publishing == closed) {
                            publishing = null;
                        }
                    });
                    if (!closed.isDone()) {
                        FOptionPane.showMessageDialog(label("lblForgeLobbyPublished"), TITLE);
                    }
                });
            } catch (final IOException | InterruptedException e) {
                FThreads.invokeInEdtLater(() -> {
                    if (publishing == closed) {
                        publishing = null;
                    }
                    if (!closed.isDone()) {
                        FOptionPane.showErrorDialog(label("lblForgeLobbyPublishError"), TITLE);
                    }
                });
            }
        });
    }

    private static String roomLabel(final Room room) {
        final String name = room.name() == null || room.name().trim().isEmpty() ? room.connectionAddress() : room.name();
        final String version = room.version() == null || room.version().trim().isEmpty() ? "" : " · " + room.version();
        return "[" + room.format() + "] " + name + " · " + room.language() + " · "
                + Forge.getLocalizer().getMessage("lblForgeLobbyAvailable", room.availableSlots()) + version;
    }

    private static String label(final String key) {
        return Forge.getLocalizer().getMessage(key);
    }
}
