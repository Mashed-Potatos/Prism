package dev.prism.crypto;

import java.util.List;
import java.util.UUID;

/** Verified identity from Mojang's sessionserver, or a synthetic offline profile. */
public final class GameProfile {

    public final UUID uuid;
    public final String name;
    public final List<Property> properties;

    public GameProfile(UUID uuid, String name, List<Property> properties) {
        this.uuid = uuid;
        this.name = name;
        this.properties = properties;
    }

    public static final class Property {
        public final String name;
        public final String value;
        /** Nullable: Mojang signs textures, but custom properties may be unsigned. */
        public final String signature;

        public Property(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }
    }
}
