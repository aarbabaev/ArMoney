package com.arman.bank.smssandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** A bounded local fixture store. No delivery provider exists in this module. */
public final class Inbox implements AutoCloseable {
    public record Message(String id, String destination, String text, String purpose, long createdAt) {}
    public record Entry(String key, String digest, long createdAt, Message message) {}
    public record Submission(Message message, boolean replay) {}
    public static final class Rejection extends RuntimeException {
        final int status;
        Rejection(int status, String message) { super(message); this.status = status; }
    }
    private final ObjectMapper json = new ObjectMapper();
    private final Path directory, snapshot;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private List<Entry> entries;
    private static final int CAPACITY = 1000;
    private static final long RETENTION = Duration.ofHours(24).toMillis();

    public Inbox(Path directory, Clock clock) throws IOException {
        this.directory = directory;
        this.clock = clock;
        Files.createDirectories(directory);
        this.snapshot = directory.resolve("inbox.json");
        this.lockChannel = FileChannel.open(directory.resolve("inbox.lock"), CREATE, WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException failure) { lockChannel.close(); throw failure; }
        if (acquired == null) { lockChannel.close(); throw new IOException("Inbox already in use"); }
        this.lock = acquired;
        try {
            if (Files.exists(snapshot) && Files.size(snapshot) > 32_000_000) throw new IOException("Inbox exceeds size bound");
            entries = Files.exists(snapshot)
                    ? new ArrayList<>(Arrays.asList(json.readValue(snapshot.toFile(), Entry[].class))) : new ArrayList<>();
            if (entries.size() > CAPACITY) throw new IOException("Inbox exceeds capacity");
            prune();
        } catch (RuntimeException | IOException failure) { close(); throw failure; }
    }

    public synchronized Submission submit(String key, String destination, String text, String purpose) throws IOException {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new Rejection(400, "Invalid idempotency key");
        if (destination == null || !destination.matches("\\+9715[024568][0-9]{7}")) throw new Rejection(400, "Invalid destination");
        if (text == null || text.isBlank() || text.length() > 2048) throw new Rejection(400, "Invalid message text");
        validateUnicode(text);
        if (purpose == null || !purpose.matches("[a-zA-Z0-9_-]{1,64}")) throw new Rejection(400, "Invalid purpose");
        prune();
        String digest = digest(destination, text, purpose);
        // Only the single authenticated sender principal can submit; keys belong to that principal.
        for (Entry entry : entries) if (entry.key().equals(key)) {
            Message previous = entry.message();
            if (!entry.digest().equals(digest))
                throw new Rejection(409, "Idempotency payload conflict");
            if (previous == null) throw new Rejection(410, "Message cleared; key retained until expiry");
            return new Submission(previous, true);
        }
        if (entries.size() >= CAPACITY) throw new Rejection(507, "Inbox capacity reached");
        Message message = new Message(UUID.randomUUID().toString(), destination, text, purpose, clock.millis());
        var updated = new ArrayList<>(entries);
        updated.add(new Entry(key, digest, message.createdAt(), message));
        persist(updated);
        return new Submission(message, false);
    }

    public synchronized List<Message> list(String query, String purpose) throws IOException {
        prune();
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (needle.length() > 128 || (purpose != null && purpose.length() > 64)) throw new Rejection(400, "Filter too long");
        return entries.reversed().stream().map(Entry::message).filter(Objects::nonNull)
                .filter(m -> purpose == null || purpose.isEmpty() || m.purpose().equals(purpose))
                .filter(m -> (m.destination() + " " + m.text() + " " + m.purpose()).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    public synchronized void clear() throws IOException {
        prune();
        persist(entries.stream().map(e -> new Entry(e.key(), e.digest(), e.createdAt(), null)).toList());
    }
    public synchronized void prune() throws IOException {
        var retained = entries.stream().filter(e -> e.createdAt() > clock.millis() - RETENTION).toList();
        if (retained.size() != entries.size()) persist(retained);
    }

    private static void validateUnicode(String text) {
        // UTF-8 replaces isolated UTF-16 surrogates, which would alias another payload's digest.
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1)))
                    throw new Rejection(400, "Invalid message Unicode");
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new Rejection(400, "Invalid message Unicode");
            }
        }
    }

    private static String digest(String destination, String text, String purpose) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(destination, text, purpose)) {
                byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void persist(List<Entry> updated) throws IOException {
        byte[] bytes = json.writeValueAsBytes(updated);
        Path temporary = directory.resolve("inbox.tmp");
        try (var output = FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
        // Fail closed when the filesystem cannot supply atomic replacement; never fall back to truncation.
        Files.move(temporary, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        entries = new ArrayList<>(updated);
    }

    @Override public synchronized void close() throws IOException {
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
