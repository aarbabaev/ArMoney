package com.arman.bank.userservice.infrastructure;

import com.arman.bank.runtime.Database;
import java.util.*;

/** Owns user-service database pools; configuration contains no client-controlled identifiers. */
public final class ProfileShards implements AutoCloseable {
    private final Map<String, Database> databases;
    private final Map<String, String> prefixes;
    private final String defaultShard;

    public ProfileShards(Map<String, Database> databases, Map<String, String> prefixes, String defaultShard) {
        this.databases = Map.copyOf(databases);
        this.prefixes = Map.copyOf(prefixes);
        this.defaultShard = defaultShard;
        if (!databases.containsKey("primary") || !databases.containsKey(defaultShard)) throw new IllegalArgumentException("Unknown default shard");
        databases.keySet().forEach(ProfileShards::validateId);
        prefixes.forEach((prefix, shard) -> {
            if (!prefix.matches("[a-z0-9]{2}") || !databases.containsKey(shard)) throw new IllegalArgumentException("Invalid prefix mapping");
        });
    }

    public static ProfileShards open(Map<String, String> env) {
        var ids = new LinkedHashSet<String>();
        String configured = env.getOrDefault("USER_SHARD_IDS", "");
        if (!configured.isBlank()) for (String id : configured.split(",", -1)) {
            validateId(id);
            if (id.equals("primary") || !ids.add(id)) throw new IllegalArgumentException("Invalid shard ID list");
        }
        var mapping = new LinkedHashMap<String, String>();
        String mappings = env.getOrDefault("USER_SHARD_PREFIX_MAP", "");
        if (!mappings.isBlank()) for (String entry : mappings.split(",", -1)) {
            String[] pair = entry.split(":", -1);
            if (pair.length != 2 || !pair[0].matches("[a-z0-9]{2}") || (!pair[1].equals("primary") && !ids.contains(pair[1])) || mapping.putIfAbsent(pair[0], pair[1]) != null)
                throw new IllegalArgumentException("Invalid prefix mapping");
        }
        String fallback = env.getOrDefault("USER_SHARD_DEFAULT", "primary");
        if (!fallback.equals("primary") && !ids.contains(fallback)) throw new IllegalArgumentException("Unknown default shard");
        var pools = new LinkedHashMap<String, Database>();
        try {
            pools.put("primary", new Database(required(env, "DB_URL"), required(env, "DB_USER"), required(env, "DB_PASSWORD")));
            for (String id : ids) {
                String key = "USER_SHARD_" + id.toUpperCase(Locale.ROOT) + "_";
                pools.put(id, new Database(required(env, key + "DB_URL"), required(env, key + "DB_USER"), required(env, key + "DB_PASSWORD")));
            }
            return new ProfileShards(pools, mapping, fallback);
        } catch (RuntimeException e) { pools.values().forEach(Database::close); throw e; }
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + key);
        return value;
    }
    private static void validateId(String id) {
        if (id == null || !id.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("Invalid shard ID");
    }
    public String select(String email) {
        if (email == null) return defaultShard;
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"))
            throw new IllegalArgumentException("Invalid trusted identity email");
        int at = normalized.indexOf('@');
        // Non-alphanumeric and one-character prefixes intentionally use the default.
        return at < 2 ? defaultShard : prefixes.getOrDefault(normalized.substring(0, 2), defaultShard);
    }
    public Database primary() { return database("primary"); }
    public Database database(String shard) {
        var result = databases.get(shard);
        if (result == null) throw new IllegalStateException("Pinned profile shard unavailable");
        return result;
    }
    public boolean ready() { return databases.values().stream().allMatch(Database::ready); }
    @Override public void close() { databases.values().forEach(Database::close); }
}
