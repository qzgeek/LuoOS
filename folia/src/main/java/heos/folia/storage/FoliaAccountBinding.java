package heos.folia.storage;

import heos.folia.utils.FoliaMessages;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * High-level account binding operations.
 * Delegates storage to FoliaStorage but adds validation logic.
 */
public final class FoliaAccountBinding {
    private final FoliaStorage storage;
    private final Logger logger;

    public FoliaAccountBinding(FoliaStorage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    /**
     * Request a binding from newPlayer (the one requesting) to oldPlayerName.
     * Returns BindingEntry on success, null with message on failure.
     */
    public BindResult requestBinding(UUID newUuid, String newName, String oldName) {
        if (oldName == null || oldName.isEmpty()) {
            return BindResult.fail(FoliaMessages.bindOldNameRequired());
        }
        if (newName == null || newName.isEmpty()) {
            return BindResult.fail("Invalid player name");
        }

        // Check if new player already has a pending or active binding
        java.util.List<FoliaStorage.BindingEntry> existing = storage.listAllBindings();
        for (FoliaStorage.BindingEntry entry : existing) {
            if (entry.newUuid.equals(newUuid) && "active".equals(entry.status)) {
                return BindResult.fail(FoliaMessages.bindAlreadyBound());
            }
            if (entry.newUuid.equals(newUuid) && "pending".equals(entry.status)) {
                return BindResult.fail(FoliaMessages.bindPendingExists());
            }
        }

        // Check if there's already a pending request for this old name from this new UUID
        // deduplicate
        FoliaStorage.BindingEntry entry = storage.requestBinding(newUuid, newName, oldName);
        if (entry == null) {
            return BindResult.fail(FoliaMessages.bindRequestFailed());
        }
        logger.info("Binding requested: " + newName + " -> " + oldName);
        return BindResult.ok(entry, FoliaMessages.bindRequestSent(oldName));
    }

    /**
     * Accept a binding: oldPlayer confirms by specifying the new player's name.
     */
    public BindResult acceptBinding(UUID oldUuid, String oldName, String newName) {
        if (newName == null || newName.isEmpty()) {
            return BindResult.fail(FoliaMessages.bindNewNameRequired());
        }

        FoliaStorage.BindingEntry entry = storage.acceptBinding(oldUuid, oldName, newName);
        if (entry == null) {
            return BindResult.fail(FoliaMessages.bindNoPendingRequest(newName));
        }
        logger.info("Binding accepted: " + entry.oldName + " <- " + entry.newName + " (UUID: " + entry.oldUuid + " <- " + entry.newUuid + ")");
        return BindResult.ok(entry, FoliaMessages.bindAccepted(newName));
    }

    /**
     * Look up the effective UUID for a connecting player.
     * If the player has an active binding, returns the old UUID.
     * Otherwise returns the original UUID.
     */
    public UUID resolveEffectiveUuid(UUID rawUuid) {
        UUID boundUuid = storage.getBoundUuid(rawUuid);
        return boundUuid != null ? boundUuid : rawUuid;
    }

    public boolean isNewUuidBound(UUID uuid) {
        return storage.getBoundUuid(uuid) != null;
    }

    public static final class BindResult {
        public final boolean success;
        public final String message;
        public final FoliaStorage.BindingEntry entry;

        private BindResult(boolean success, String message, FoliaStorage.BindingEntry entry) {
            this.success = success;
            this.message = message;
            this.entry = entry;
        }

        public static BindResult ok(FoliaStorage.BindingEntry entry, String message) {
            return new BindResult(true, message, entry);
        }

        public static BindResult fail(String message) {
            return new BindResult(false, message, null);
        }
    }
}
