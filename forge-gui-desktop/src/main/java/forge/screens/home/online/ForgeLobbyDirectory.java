package forge.screens.home.online;

import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.match.LobbySlotType;
import forge.screens.home.online.ForgeLobbyClient.Announcement;
import forge.screens.home.online.ForgeLobbyClient.Publication;
import forge.screens.home.online.ForgeLobbyClient.Room;
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
import javax.swing.Timer;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class ForgeLobbyDirectory {
    private static final String TITLE = "Forge Lobby";
    private static final ForgeLobbyClient API = new ForgeLobbyClient(
            URI.create("https://forge-lobby.pages.dev/api/v1/rooms"));
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, "Forge Lobby directory");
        thread.setDaemon(true);
        return thread;
    });

    private static ChannelFuture publishing;
    private static boolean loading;

    private ForgeLobbyDirectory() {}

    static void browse(final Consumer<String> join) {
        if (loading) {
            return;
        }
        loading = true;
        REQUESTS.execute(() -> {
            try {
                final List<RoomChoice> rooms = API.list().stream()
                        .filter(room -> room != null && "open".equals(room.status())
                                && room.availableSlots() > 0 && room.address() != null
                                && !room.address().isBlank() && room.port() > 0 && room.port() <= 65535)
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
        final RoomState initial = snapshot(details, lobby);
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
        new RoomSync(closed, lobby, publication, initial).start();
    }

    // Swing owns the snapshots and scheduling. HTTP requests are serialized off the EDT.
    private static final class RoomSync {
        private final ChannelFuture closed;
        private final ServerGameLobby lobby;
        private final Publication publication;
        private final Announcement details;
        private final Timer changes = new Timer(400, e -> sync(false));
        private final Timer refresh = new Timer(60_000, e -> sync(true));
        private RoomState saved;
        private boolean updating;
        private boolean pending;

        RoomSync(final ChannelFuture closed, final ServerGameLobby lobby,
                 final Publication publication, final RoomState initial) {
            this.closed = closed;
            this.lobby = lobby;
            this.publication = publication;
            details = initial.announcement();
            // POST always creates an open announcement.
            saved = new RoomState(details, "open");
            changes.setRepeats(false);
        }

        void start() {
            closed.addListener(ignored -> {
                SwingUtilities.invokeLater(() -> {
                    changes.stop();
                    refresh.stop();
                    lobby.setRoomStateListener(null);
                    if (publishing == closed) {
                        publishing = null;
                    }
                });
                REQUESTS.execute(() -> {
                    try {
                        API.close(publication);
                    } catch (final IOException | InterruptedException e) {
                        // The directory expires the listing if removal could not be delivered.
                    }
                });
            });
            if (closed.isDone()) {
                return;
            }
            lobby.setRoomStateListener(() -> SwingUtilities.invokeLater(() -> {
                if (!closed.isDone()) {
                    changes.restart();
                }
            }));
            // Include changes made while the original POST was in flight.
            sync(false);
            refresh.start();
            FOptionPane.showMessageDialog(label("lblForgeLobbyPublished"), TITLE);
        }

        private void sync(final boolean force) {
            if (closed.isDone()) {
                return;
            }
            if (updating) {
                pending = true;
                return;
            }
            final RoomState state = snapshot(details, lobby);
            if (!force && state.equals(saved)) {
                return;
            }
            updating = true;
            REQUESTS.execute(() -> {
                boolean success = false;
                try {
                    if (!closed.isDone()) {
                        API.update(publication, state.announcement(), state.status());
                        success = true;
                    }
                } catch (final IOException | InterruptedException e) {
                    // The next refresh retries with the current state.
                }
                final boolean updated = success;
                SwingUtilities.invokeLater(() -> {
                    updating = false;
                    if (updated) {
                        saved = state;
                    }
                    if (pending) {
                        pending = false;
                        sync(false);
                    }
                });
            });
        }
    }

    private static RoomState snapshot(final Announcement details, final ServerGameLobby lobby) {
        final int slots = lobby.getNumberOfSlots();
        int occupied = 0;
        int open = 0;
        for (int i = 0; i < slots; i++) {
            final var slot = lobby.getSlot(i);
            if (slot != null) {
                if (slot.getType() == LobbySlotType.OPEN) {
                    open++;
                } else {
                    occupied++;
                }
            }
        }
        final int players = Math.max(1, occupied);
        final int capacity = Math.max(2, occupied + open);
        final String status = lobby.isMatchActive() ? "started" : open == 0 ? "full" : "open";
        final var event = lobby.getCurrentEvent();
        final String formatName;
        if (!lobby.getData().isLimitedMode()) {
            formatName = formatName(lobby.getFormatChoice());
        } else if (event == null) {
            formatName = "Limited";
        } else {
            formatName = switch (event.getFormat()) {
                case BOOSTER_DRAFT -> "Booster Draft";
                case SEALED -> "Sealed";
            };
        }
        final String category = formatCategory(formatName);
        return new RoomState(new Announcement(details.name(), category, details.language(), capacity, players,
                details.address(), details.port(), details.version(), "Otro".equals(category) ? formatName : "", true), status);
    }

    private static String formatName(final Object choice) {
        if (choice instanceof GameFormat format) {
            return format.getName();
        }
        if (choice instanceof GameType type) {
            return type.getEnglishName();
        }
        return "";
    }

    private static String formatCategory(final String name) {
        return switch (name) {
            case "Commander", "Modern", "Standard", "Pauper", "Legacy", "Vintage" -> name;
            default -> "Otro";
        };
    }

    private static String label(final String key) {
        return Localizer.getInstance().getMessage(key);
    }

    private record RoomState(Announcement announcement, String status) {}

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
