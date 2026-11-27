package forge.gamemodes.net;

import forge.gamemodes.net.ForgeLobbyClient.Room;
import forge.util.Localizer;

import java.util.Locale;

/** Localizes directory labels while preserving the API's wire values. */
public final class ForgeLobbyLabels {
    public enum Language {
        SPANISH("Español", "Es", "es"),
        ENGLISH("Inglés", "En", "en"),
        PORTUGUESE("Portugués", "Pt", "pt"),
        FRENCH("Francés", "Fr", "fr"),
        GERMAN("Alemán", "De", "de"),
        OTHER("Otro", "Other", "");

        private final String apiValue;
        private final String labelSuffix;
        private final String locale;

        Language(final String apiValue, final String labelSuffix, final String locale) {
            this.apiValue = apiValue;
            this.labelSuffix = labelSuffix;
            this.locale = locale;
        }

        public String apiValue() {
            return apiValue;
        }

        @Override
        public String toString() {
            return label("lblForgeLobbyLanguage" + labelSuffix);
        }

        public static Language forLocale(final String locale) {
            final String language = Locale.forLanguageTag(locale.replace('_', '-')).getLanguage();
            for (final Language value : values()) {
                if (value.locale.equals(language) && value != OTHER) {
                    return value;
                }
            }
            return ENGLISH;
        }
    }

    private ForgeLobbyLabels() {}

    public static String room(final Room room) {
        final String name = room.name() == null || room.name().trim().isEmpty() ? room.connectionAddress() : room.name();
        final String format = switch (room.format() == null ? "" : room.format()) {
            case "Commander" -> label("lblCommander");
            case "Standard" -> label("lblStandard");
            case "Modern", "Pauper", "Legacy", "Vintage" -> label("lblForgeLobbyFormat" + room.format());
            case "Otro", "" -> label("lblOther");
            default -> room.format();
        };
        String language = label("lblForgeLobbyLanguageOther");
        for (final Language value : Language.values()) {
            if (value.apiValue().equals(room.language())) {
                language = value.toString();
                break;
            }
        }
        final String seats = label("lblForgeLobbyAvailable", room.availableSlots());
        return room.version() == null || room.version().trim().isEmpty()
                ? label("lblForgeLobbyRoomEntry", format, name, language, seats)
                : label("lblForgeLobbyRoomEntryWithVersion", format, name, language, seats, room.version());
    }

    private static String label(final String key, final Object... arguments) {
        return Localizer.getInstance().getMessage(key, arguments);
    }
}
