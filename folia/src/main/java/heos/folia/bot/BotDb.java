package heos.folia.bot;

import heos.folia.storage.FoliaWhitelistRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Compatibility facade for the QQ bot.
 *
 * SQL belongs to FoliaWhitelistRepository; the bot keeps this small facade so
 * command code remains independent from the storage implementation.
 */
public final class BotDb {
    private final Logger logger;
    private final FoliaWhitelistRepository repository;

    public BotDb(Logger logger, FoliaWhitelistRepository repository) {
        this.logger = logger;
        this.repository = repository;
    }

    public List<String> getWhitelist(long qq) { return repository.getWhitelist(qq); }
    public boolean isNameTaken(String playerName) { return repository.isNameTaken(playerName); }
    public int getWhitelistCount(long qq) { return repository.getWhitelistCount(qq); }
    public boolean hasWhitelist(long qq, String playerName) { return repository.hasWhitelist(qq, playerName); }
    public boolean addWhitelist(long qq, String playerName, String playerUuid) {
        boolean added = repository.addWhitelist(qq, playerName, playerUuid);
        if (!added) logger.warning("[BotDb] whitelist entry was not added: " + playerName);
        return added;
    }
    public boolean removeWhitelist(long qq, String playerName) { return repository.removeWhitelist(qq, playerName); }

    public static final class WhitelistEntry {
        public final String playerName;
        public final String playerUuid;
        public final boolean frozen;

        private WhitelistEntry(String playerName, String playerUuid, boolean frozen) {
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.frozen = frozen;
        }
    }

    public List<WhitelistEntry> getWhitelistEntries(long qq) {
        List<WhitelistEntry> result = new ArrayList<>();
        for (FoliaWhitelistRepository.WhitelistEntry e : repository.getWhitelistEntries(qq)) {
            result.add(new WhitelistEntry(e.playerName, e.playerUuid, e.frozen));
        }
        return result;
    }

    public int freezeWhitelist(long qq) { return repository.freezeWhitelist(qq); }
    public int unfreezeWhitelist(long qq) { return repository.unfreezeWhitelist(qq); }

    public boolean isBlacklisted(long qq) { return repository.isBlacklisted(qq); }
    public boolean blacklist(long qq, Long durationSeconds, String reason) {
        return repository.blacklist(qq, durationSeconds, reason);
    }
    public boolean unblacklist(long qq) { return repository.unblacklist(qq); }

    public List<FoliaWhitelistRepository.BlacklistEntry> getBlacklistEntries(int limit) {
        return repository.getBlacklistEntries(limit);
    }
}
