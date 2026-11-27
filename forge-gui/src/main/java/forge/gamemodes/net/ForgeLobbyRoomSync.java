package forge.gamemodes.net;

import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ForgeLobbyClient.Announcement;
import forge.gamemodes.net.ForgeLobbyClient.Publication;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.FThreads;
import io.netty.channel.ChannelFuture;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Keeps the directory announcement in sync with the current hosted room. */
public final class ForgeLobbyRoomSync {
    private final ForgeLobbyClient api;
    private final ScheduledExecutorService requests;
    private final ChannelFuture closed;
    private final ServerGameLobby lobby;
    private final Publication publication;
    private final Announcement details;
    private ScheduledFuture<?> changes;
    private ScheduledFuture<?> refresh;
    private RoomState saved;
    private boolean updating;
    private boolean pending;

    public record RoomState(Announcement announcement, String status) {}

    public ForgeLobbyRoomSync(final ForgeLobbyClient api, final ScheduledExecutorService requests,
                             final ChannelFuture closed, final ServerGameLobby lobby,
                             final Publication publication, final RoomState initial) {
        this.api = api;
        this.requests = requests;
        this.closed = closed;
        this.lobby = lobby;
        this.publication = publication;
        details = initial.announcement();
        // POST always creates an open announcement.
        saved = new RoomState(details, "open");
    }

    // Called from the UI thread. Network callbacks post changes to that same thread.
    public void start(final Runnable onClosed) {
        closed.addListener(ignored -> {
            FThreads.invokeInEdtLater(() -> {
                if (changes != null) {
                    changes.cancel(false);
                }
                if (refresh != null) {
                    refresh.cancel(false);
                }
                lobby.setRoomStateListener(null);
                onClosed.run();
            });
            requests.execute(() -> {
                try {
                    api.close(publication);
                } catch (final IOException | InterruptedException e) {
                    // The directory expires the listing if removal could not be delivered.
                }
            });
        });
        if (closed.isDone()) {
            return;
        }
        lobby.setRoomStateListener(() -> FThreads.invokeInEdtLater(this::scheduleChange));
        // Include changes made while the original POST was in flight.
        sync(false);
        refresh = requests.scheduleWithFixedDelay(() -> FThreads.invokeInEdtLater(() -> sync(true)),
                60, 60, TimeUnit.SECONDS);
    }

    private void scheduleChange() {
        if (!closed.isDone()) {
            if (changes != null) {
                changes.cancel(false);
            }
            changes = requests.schedule(() -> FThreads.invokeInEdtLater(() -> sync(false)),
                    400, TimeUnit.MILLISECONDS);
        }
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
        requests.execute(() -> {
            boolean success = false;
            try {
                if (!closed.isDone()) {
                    api.update(publication, state.announcement(), state.status());
                    success = true;
                }
            } catch (final IOException | InterruptedException e) {
                // The next refresh retries with the current state.
            }
            final boolean updated = success;
            FThreads.invokeInEdtLater(() -> {
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

    public static RoomState snapshot(final Announcement details, final ServerGameLobby lobby) {
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
}
