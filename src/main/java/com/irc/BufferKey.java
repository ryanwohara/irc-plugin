package com.irc;

import java.util.Locale;
import java.util.Objects;

/**
 * A buffer's identity: the network it lives on and its name there. Names compare exactly, as tab
 * titles always have; case-insensitive lookups (rosters, topics) go through {@link #folded()}.
 */
final class BufferKey {
    private final String networkId;
    private final String name;

    private BufferKey(String networkId, String name) {
        this.networkId = Objects.requireNonNull(networkId, "networkId");
        this.name = Objects.requireNonNull(name, "name");
    }

    static BufferKey of(String networkId, String name) {
        return new BufferKey(networkId, name);
    }

    static BufferKey swiftIrc(String name) {
        return new BufferKey(NetworkConfig.SWIFTIRC_ID, name);
    }

    String getNetworkId() {
        return networkId;
    }

    String getName() {
        return name;
    }

    /** The same buffer with its name lower-cased, for maps that must ignore IRC name casing. */
    BufferKey folded() {
        return new BufferKey(networkId, name.toLowerCase(Locale.ROOT));
    }

    boolean isChannel() {
        return name.startsWith("#") || name.startsWith("&");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BufferKey)) return false;
        BufferKey other = (BufferKey) o;
        return networkId.equals(other.networkId) && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkId, name);
    }

    /** The bare name: JTree and list renderers display a key through toString. */
    @Override
    public String toString() {
        return name;
    }
}
