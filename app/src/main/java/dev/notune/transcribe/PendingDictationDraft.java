package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The one backup-excluded recovery item. It contains dictated text, not editor data. */
final class PendingDictationDraft {
    static final String PENDING = "pending";
    static final String ATTEMPTED = "attempted";
    static final String UNCERTAIN = "uncertain";
    static final String RETRYABLE = "retryable";
    static final String INTERRUPTED = "interrupted";
    static final String REVIEW = "review";

    final long sessionId;
    final long nextSequence;
    final String state;
    final String text;

    PendingDictationDraft(long sessionId, long nextSequence, String state, String text) {
        this.sessionId = sessionId;
        this.nextSequence = nextSequence;
        this.state = state;
        this.text = text == null ? "" : text;
    }

    PendingDictationDraft with(String newState, String newText, long newNextSequence) {
        return new PendingDictationDraft(sessionId, newNextSequence, newState, newText);
    }

    boolean mayAlreadyBeDelivered() {
        return ATTEMPTED.equals(state) || UNCERTAIN.equals(state);
    }

    /** A later piece or terminal result cannot erase an unresolved editor write. */
    PendingDictationDraft preservingDeliveryRisk(
            String requestedState, String newText, long newNextSequence) {
        return with(mayAlreadyBeDelivered() ? state : requestedState, newText, newNextSequence);
    }

    byte[] encode() {
        String body = "NOTUNE1\n" + sessionId + "\n" + nextSequence + "\n" + state + "\n"
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return body.getBytes(StandardCharsets.UTF_8);
    }

    static PendingDictationDraft decode(byte[] bytes) {
        try {
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\n", 5);
            if (parts.length != 5 || !"NOTUNE1".equals(parts[0]) || !validState(parts[3])) {
                return null;
            }
            long sessionId = Long.parseLong(parts[1]);
            long nextSequence = Long.parseLong(parts[2]);
            if (sessionId <= 0 || nextSequence < 0) return null;
            String text = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            return new PendingDictationDraft(sessionId, nextSequence, parts[3], text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean validState(String value) {
        return PENDING.equals(value) || ATTEMPTED.equals(value) || UNCERTAIN.equals(value)
                || RETRYABLE.equals(value) || INTERRUPTED.equals(value)
                || REVIEW.equals(value);
    }
}
