package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PendingDictationDraftTest {
    @Test public void roundTripKeepsUnlimitedUnicodeAndOrdering() {
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 10000; i++) textBuilder.append("Привет\nhello ");
        String text = textBuilder.toString();
        PendingDictationDraft draft = new PendingDictationDraft(
                91, 7, PendingDictationDraft.UNCERTAIN, text);
        PendingDictationDraft restored = PendingDictationDraft.decode(draft.encode());
        assertEquals(91, restored.sessionId);
        assertEquals(7, restored.nextSequence);
        assertEquals(PendingDictationDraft.UNCERTAIN, restored.state);
        assertEquals(text, restored.text);
    }

    @Test public void corruptOrUnknownRecordsAreRejected() {
        assertNull(PendingDictationDraft.decode("broken".getBytes()));
        assertNull(PendingDictationDraft.decode("NOTUNE1\n1\n0\nmagic\n".getBytes()));
    }

    @Test public void reviewCandidateSurvivesProcessRestart() {
        PendingDictationDraft draft = new PendingDictationDraft(
                92, 8, PendingDictationDraft.REVIEW, "Complete candidate text.");
        PendingDictationDraft restored = PendingDictationDraft.decode(draft.encode());
        assertEquals(PendingDictationDraft.REVIEW, restored.state);
        assertEquals("Complete candidate text.", restored.text);
    }

    @Test public void attemptedDeliverySurvivesProcessRestart() {
        PendingDictationDraft draft = new PendingDictationDraft(
                93, 9, PendingDictationDraft.ATTEMPTED, "May already be in the field.");
        PendingDictationDraft restored = PendingDictationDraft.decode(draft.encode());
        assertEquals(PendingDictationDraft.ATTEMPTED, restored.state);
        assertTrue(restored.mayAlreadyBeDelivered());
        assertEquals("May already be in the field.", restored.text);
    }

    @Test public void laterStatesCannotEraseDeliveryAmbiguity() {
        PendingDictationDraft attempted = new PendingDictationDraft(
                94, 3, PendingDictationDraft.ATTEMPTED, "one");
        PendingDictationDraft uncertain = new PendingDictationDraft(
                94, 3, PendingDictationDraft.UNCERTAIN, "one");

        PendingDictationDraft afterPiece = attempted.preservingDeliveryRisk(
                PendingDictationDraft.PENDING, "one two", 4);
        assertEquals(PendingDictationDraft.ATTEMPTED, afterPiece.state);
        assertEquals("one two", afterPiece.text);
        assertEquals(4, afterPiece.nextSequence);
        assertEquals(PendingDictationDraft.UNCERTAIN,
                uncertain.preservingDeliveryRisk(
                        PendingDictationDraft.RETRYABLE, "one two", 4).state);
        assertFalse(new PendingDictationDraft(
                94, 3, PendingDictationDraft.PENDING, "one").mayAlreadyBeDelivered());
        assertEquals(PendingDictationDraft.RETRYABLE,
                new PendingDictationDraft(94, 3, PendingDictationDraft.PENDING, "one")
                        .preservingDeliveryRisk(
                                PendingDictationDraft.RETRYABLE, "one", 4).state);
    }
}
