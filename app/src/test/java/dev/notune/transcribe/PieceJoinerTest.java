package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.notune.transcribe.TextFitter.FieldKind;

/**
 * Whole dictation sessions, replayed against a StringBuilder standing in for the field.
 *
 * <p>The question these answer is the one the owner asked: he stops in the middle of a
 * sentence to think, and the sentence must not be cut in two. The recording is still cut
 * at the short silence, because that is what makes the words appear while he speaks; what
 * changes is that a cut is not believed to be a sentence end until the silence before the
 * next piece says so.
 */
public class PieceJoinerTest {

    private static final float THRESHOLD = PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
    private static final int NO_CAPS = 0;

    /** A field with a cursor at the end, written only by appending, as the IME does. */
    private static final class Field {
        final StringBuilder text = new StringBuilder();
        final PieceJoiner joiner = new PieceJoiner();
        final float threshold;

        Field(String initial, float threshold) {
            text.append(initial);
            this.threshold = threshold;
        }

        void say(String piece, float pauseBefore) {
            String committed = joiner.join(piece, pauseBefore, text.toString(), "",
                    FieldKind.PROSE, NO_CAPS, threshold);
            text.append(committed);
        }

        String end() {
            text.append(joiner.finish());
            return text.toString();
        }
    }

    private static Field session() {
        return new Field("", THRESHOLD);
    }

    /**
     * Compares without regard to case, on purpose. What these tests prove is where a
     * sentence ends and where the spaces go; the case of a first letter belongs to
     * fitter rule 3 and is proven in {@link PieceJoinerCapitalTest}, which is deleted
     * together with that rule. Keeping the two apart is what lets rule 3 be reverted
     * without any of this file turning red.
     */
    private static void assertJoined(String expected, String actual) {
        assertEquals(expected.toLowerCase(java.util.Locale.ROOT),
                actual.toLowerCase(java.util.Locale.ROOT));
    }

    // --------------------------------------------------- the case he asked about

    @Test public void aPauseToThinkDoesNotCutTheSentenceInTwo() {
        Field f = session();
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", 2.0f);
        assertJoined("I was thinking that we should go home. ", f.end());
    }

    @Test public void theSameWordsWithALongPauseBecomeTwoSentences() {
        // The opposite case: identical pieces, only the silence differs.
        Field f = session();
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", 5.0f);
        assertJoined("I was thinking. That we should go home. ", f.end());
    }

    @Test public void exactlyTheThresholdEndsTheSentence() {
        Field f = session();
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", THRESHOLD);
        assertJoined("I was thinking. That we should go home. ", f.end());
    }

    @Test public void justUnderTheThresholdDoesNot() {
        Field f = session();
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", THRESHOLD - 0.1f);
        assertJoined("I was thinking that we should go home. ", f.end());
    }

    @Test public void oneSessionMixesHesitationsAndRealSentenceEnds() {
        Field f = session();
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", 1.8f);       // hesitation
        f.say("Then we eat.", 6.0f);                  // real end
        f.say("And then we sleep.", 0.9f);            // hesitation
        assertJoined("I was thinking that we should go home. Then we eat and then we sleep. ",
                f.end());
    }

    @Test public void aFiveWayMixKeepsEveryWordAndEveryPause() {
        Field f = session();
        f.say("First part.", 0f);
        f.say("Second part.", 0.5f);
        f.say("Third part.", 0.5f);
        f.say("Fourth part.", 4.5f);
        f.say("Fifth part.", 0.5f);
        assertJoined("First part second part third part. Fourth part fifth part. ", f.end());
    }

    // ------------------------------------------------------------ the invariants

    @Test public void nothingIsEverRevised() {
        // Every call returns an append, and the field only grows: the text committed
        // for piece N is still there, unchanged, at the end.
        Field f = session();
        f.say("I was thinking.", 0f);
        String afterFirst = f.text.toString();
        f.say("That we should go home.", 1.0f);
        assertTrue(f.text.toString().startsWith(afterFirst));
        f.say("Or not.", 5.0f);
        assertTrue(f.text.toString().startsWith(afterFirst));
        assertTrue(f.end().startsWith(afterFirst));
    }

    @Test public void theLastPieceKeepsTheMarkTheModelGaveIt() {
        Field f = session();
        f.say("Is that right?", 0f);
        assertEquals("Is that right? ", f.end());
    }

    @Test public void aSinglePieceSessionIsExactlyWhatItWasBeforeStreaming() {
        Field f = session();
        f.say("And then we go home.", 0f);
        assertEquals("And then we go home. ", f.end());
    }

    @Test public void anUnfinishedLastPieceGetsNoInventedMark() {
        Field f = session();
        f.say("I was thinking.", 0f);
        f.say("that we should go home", 1.0f);     // the model gave no mark
        assertJoined("I was thinking that we should go home ", f.end());
    }

    @Test public void theHeldMarkIsGoneOnceTheSessionEnds() {
        Field f = session();
        f.say("Hello.", 0f);
        assertTrue(f.joiner.hasHeldTail());
        f.end();
        assertFalse(f.joiner.hasHeldTail());
        assertEquals("", f.joiner.finish());
    }

    @Test public void theFirstPieceOfASessionIsNeverJoinedToAnything() {
        Field f = new Field("I typed this. ", THRESHOLD);
        f.say("And then we go home.", 0f);
        assertEquals("I typed this. And then we go home. ", f.end());
    }

    // -------------------------------------------------------------- other fields

    @Test public void aSearchBoxNeverHoldsAnything() {
        PieceJoiner j = new PieceJoiner();
        String out = j.join("Weather in Moscow.", 0f, "", "", FieldKind.SEARCH, NO_CAPS,
                THRESHOLD);
        assertEquals("Weather in Moscow", out);
        assertFalse(j.hasHeldTail());
        assertEquals("", j.finish());
    }

    @Test public void searchPiecesKeepWordSpacing() {
        PieceJoiner j = new PieceJoiner();
        String first = j.join("Weather in.", 0f, "", "", FieldKind.SEARCH, NO_CAPS,
                THRESHOLD);
        String second = j.join("Moscow.", 1f, first, "", FieldKind.SEARCH, NO_CAPS,
                THRESHOLD);
        assertEquals("Weather in Moscow", first + second);
    }

    @Test public void aPasswordFieldNeverHoldsAnything() {
        PieceJoiner j = new PieceJoiner();
        String out = j.join("Hunter two.", 0f, "abc", "", FieldKind.PASSWORD, NO_CAPS,
                THRESHOLD);
        assertEquals("Hunter two.", out);
        assertFalse(j.hasHeldTail());
    }

    @Test public void aDeadConnectionStillDeliversEveryWord() {
        // before == null: the fitter degrades to today's behaviour and the joiner holds
        // nothing back, because with nothing readable in front of the cursor there is
        // no separating space to be had except the one the fitter adds. The sentence is
        // then cut at the pause, which is what this build did before streaming existed.
        PieceJoiner j = new PieceJoiner();
        StringBuilder out = new StringBuilder();
        out.append(j.join("I was thinking.", 0f, null, null, FieldKind.PROSE, NO_CAPS,
                THRESHOLD));
        out.append(j.join("That we should go.", 1.0f, null, null, FieldKind.PROSE, NO_CAPS,
                THRESHOLD));
        out.append(j.finish());
        assertEquals("I was thinking. That we should go. ", out.toString());
    }

    // ------------------------------------------------------- the owner's own knob

    @Test public void theThresholdIsTheOwnersNumberAndItIsObeyed() {
        // At 1.5 s the same 2 s pause that was a hesitation becomes a sentence end.
        Field slow = new Field("", 4.0f);
        slow.say("I was thinking.", 0f);
        slow.say("That we should go home.", 2.0f);
        assertJoined("I was thinking that we should go home. ", slow.end());

        Field quick = new Field("", 1.5f);
        quick.say("I was thinking.", 0f);
        quick.say("That we should go home.", 2.0f);
        assertJoined("I was thinking. That we should go home. ", quick.end());
    }

    @Test public void theDefaultIsThreeSeconds() {
        assertEquals(3.0f, PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS, 0.0001f);
    }
}
