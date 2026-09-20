package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class PendingDictationDraftTest {
    @Test public void roundTripKeepsUnlimitedUnicodeText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10000; i++) text.append("Привет\nhello ");
        PendingDictationDraft restored = PendingDictationDraft.decode(
                new PendingDictationDraft(PendingDictationDraft.PENDING, text.toString()).encode());
        assertEquals(PendingDictationDraft.PENDING, restored.state);
        assertEquals(text.toString(), restored.text);
        assertFalse(restored.mayAlreadyBeDelivered());
    }

    @Test public void attemptedDeliverySurvivesRestart() {
        PendingDictationDraft restored = PendingDictationDraft.decode(
                new PendingDictationDraft(PendingDictationDraft.ATTEMPTED,
                        "May already be in the field.").encode());
        assertEquals(PendingDictationDraft.ATTEMPTED, restored.state);
        assertTrue(restored.mayAlreadyBeDelivered());
    }

    @Test public void corruptOrUnknownRecordsAreRejected() {
        assertNull(PendingDictationDraft.decode("broken".getBytes(StandardCharsets.UTF_8)));
        assertNull(PendingDictationDraft.decode(
                "NOTUNE1\nunknown\ndGV4dA==".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void readableEditorMustShowTheExactCommittedText() {
        RustInputMethodService.EditorSnapshot before =
                new RustInputMethodService.EditorSnapshot("hello world", 0, 6, 11);
        RustInputMethodService.EditorSnapshot committed =
                new RustInputMethodService.EditorSnapshot("hello there ", 0, 12, 12);
        RustInputMethodService.EditorSnapshot rejected =
                new RustInputMethodService.EditorSnapshot("hello world", 0, 6, 11);
        assertTrue(committed.isExactCommitOf(before, "there "));
        assertFalse(rejected.isExactCommitOf(before, "there "));
    }
}
