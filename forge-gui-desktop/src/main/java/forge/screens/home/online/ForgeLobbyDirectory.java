package forge.screens.home.online;

import forge.gamemodes.net.ForgeLobbyClient;
import forge.gamemodes.net.ForgeLobbyRoomSync;
import forge.gamemodes.net.ForgeLobbyRoomSync.RoomState;
import forge.gamemodes.net.ForgeLobbyClient.Announcement;
import forge.gamemodes.net.ForgeLobbyClient.Publication;
import forge.gamemodes.net.ForgeLobbyClient.Room;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.toolbox.FOptionPane;
import forge.util.BuildInfo;
import forge.util.Localizer;
import io.netty.channel.ChannelFuture;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class ForgeLobbyDirectory {
    private static final String TITLE = "Forge Lobby";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final ForgeLobbyClient API = new ForgeLobbyClient(ForgeLobbyClient.PUBLIC_ENDPOINT,
            ForgeLobbyDirectory::request);
    private static final ScheduledExecutorService REQUESTS = Executors.newSingleThreadScheduledExecutor(task -> {
        final Thread thread = new Thread(task, "Forge Lobby directory");
        thread.setDaemon(true);
        return thread;
    });

    private static ChannelFuture publishing;
    private static boolean loading;

    private ForgeLobbyDirectory() {}

    private static ForgeLobbyClient.Response request(final URI uri, final String method, final String token,
                                                    final String body, final boolean readBody)
            throws IOException, InterruptedException {
        final var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        final var response = HTTP.send(request.build(), readBody
                ? HttpResponse.BodyHandlers.ofString() : HttpResponse.BodyHandlers.replacing(""));
        return new ForgeLobbyClient.Response(response.statusCode(), response.body());
    }

    static void browse(final Consumer<String> join) {
        if (loading) {
            return;
        }
        loading = true;
        REQUESTS.execute(() -> {
            try {
                final List<RoomChoice> rooms = API.listJoinable().stream()
                        .map(RoomChoice::new)
                        .toList();
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    if (rooms.isEmpty()) {
                        FOptionPane.showMessageDialog(label("lblForgeLobbyEmpty"), TITLE);
                        return;
                    }
                    final RoomChoice choice = FOptionPane.showInputDialog(label("lblForgeLobbyPickRoom"),
                            TITLE, FOptionPane.INFORMATION_ICON, "", rooms);
                    if (choice != null) {
                        join.accept(choice.room().connectionAddress());
                    }
                });
            } catch (final IOException | InterruptedException e) {
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    FOptionPane.showErrorDialog(label("lblForgeLobbyListError"), TITLE);
                });
            }
        });
    }

    static void publish(final String suggestedAddress) {
        final FServerManager server = FServerManager.getInstance();
        final ChannelFuture closed = server.getServerCloseFuture();
        final ServerGameLobby lobby = server.getHostedLobby();
        if (closed == null || closed.isDone() || lobby == null) {
            return;
        }
        if (publishing == closed) {
            FOptionPane.showMessageDialog(label("lblForgeLobbyAlreadyPublishing"), TITLE);
            return;
        }

        if (!(closed.channel().localAddress() instanceof InetSocketAddress listeningAddress)) {
            return;
        }

        final JTextField name = new JTextField(25);
        final JTextField address = new JTextField(suggestedAddress, 25);
        final JComboBox<String> language = new JComboBox<>(
                new String[]{"Español", "Inglés", "Portugués", "Francés", "Alemán", "Otro"});
        final JPanel fields = new JPanel(new GridLayout(0, 2, 8, 4));
        fields.setOpaque(false);
        fields.add(new JLabel(label("lblForgeLobbyRoomName")));
        fields.add(name);
        fields.add(new JLabel(label("lblForgeLobbyPublicHost")));
        fields.add(address);
        fields.add(new JLabel(label("lblForgeLobbyLanguage")));
        fields.add(language);

        final FOptionPane dialog = new FOptionPane(label("lblForgeLobbyConsent"), TITLE,
                FOptionPane.INFORMATION_ICON, fields,
                List.of(label("lblForgeLobbyPublish"), label("lblCancel")), 1);
        dialog.setVisible(true);
        final int result = dialog.getResult();
        dialog.dispose();
        if (result != 0 || closed.isDone()) {
            return;
        }

        String host = address.getText().trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || name.getText().trim().length() > 120) {
            FOptionPane.showErrorDialog(label("lblForgeLobbyInvalidFields"), TITLE);
            return;
        }
        final Announcement details = new Announcement(name.getText().trim(), "Otro",
                (String) language.getSelectedItem(), 2, 1, host, listeningAddress.getPort(), BuildInfo.getVersionString(), "", true);
        final RoomState initial = ForgeLobbyRoomSync.snapshot(details, lobby);
        publishing = closed;
        REQUESTS.execute(() -> {
            try {
                if (closed.isDone()) {
                    return;
                }
                final Publication publication = API.publish(initial.announcement());
                SwingUtilities.invokeLater(() -> track(closed, lobby, publication, initial));
            } catch (final IOException | InterruptedException e) {
                SwingUtilities.invokeLater(() -> {
                    if (publishing == closed) {
                        publishing = null;
                    }
                    FOptionPane.showErrorDialog(label("lblForgeLobbyPublishError"), TITLE);
                });
            }
        });
    }

    private static void track(final ChannelFuture closed, final ServerGameLobby lobby,
                              final Publication publication, final RoomState initial) {
        new ForgeLobbyRoomSync(API, REQUESTS, closed, lobby, publication, initial).start(() -> {
            if (publishing == closed) {
                publishing = null;
            }
        });
        if (!closed.isDone()) {
            FOptionPane.showMessageDialog(label("lblForgeLobbyPublished"), TITLE);
        }
    }

    private static String label(final String key) {
        return Localizer.getInstance().getMessage(key);
    }

    private record RoomChoice(Room room) {
        @Override
        public String toString() {
            final String name = room.name() == null || room.name().isBlank() ? room.connectionAddress() : room.name();
            final String version = room.version() == null || room.version().isBlank() ? "" : " · " + room.version();
            return "[" + room.format() + "] " + name + " · " + room.language() + " · "
                    + Localizer.getInstance().getMessage("lblForgeLobbyAvailable", room.availableSlots()) + version;
        }
    }
}
