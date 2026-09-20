package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** One saved whole-result transcription that still needs a safe disposition. */
final class PendingDictationDraft {
    static final String PENDING = "pending";
    static final String ATTEMPTED = "attempted";

    final String state;
    final String text;

    PendingDictationDraft(String state, String text) {
        this.state = state;
        this.text = text == null ? "" : text;
    }

    PendingDictationDraft withState(String newState) {
        return new PendingDictationDraft(newState, text);
    }

    boolean mayAlreadyBeDelivered() {
        return ATTEMPTED.equals(state);
    }

    byte[] encode() {
        String body = "NOTUNE1\n" + state + "\n"
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return body.getBytes(StandardCharsets.UTF_8);
    }

    static PendingDictationDraft decode(byte[] bytes) {
        try {
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\n", 3);
            if (parts.length != 3 || !"NOTUNE1".equals(parts[0]) || !validState(parts[1])) {
                return null;
            }
            String text = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            return new PendingDictationDraft(parts[1], text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean validState(String value) {
        return PENDING.equals(value) || ATTEMPTED.equals(value);
    }
}
