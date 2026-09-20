package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.notune.transcribe.TextFitter.FieldKind;

/**
 * What fitter rule 3, the leading-capital change, does at the join between two pieces
 * of one dictation.
 *
 * <p>This file exists separately because rule 3 is a revertible unit: delete
 * {@code TextFitter.applyLeadingCapital} and the one marked call to it, delete
 * {@link TextFitterCapitalTest} and this file, and everything else keeps working and
 * stays green. The model's own capital is then always kept.
 *
 * <p>The case that decides whether rule 3 is worth having is here: a pause taken to
 * think, followed by a piece that really does begin with a name.
 */
public class PieceJoinerCapitalTest {

    private static final float THRESHOLD = PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
    private static final int NO_CAPS = 0;
    private static final int SENTENCE_CAPS = TextFitter.CAP_MODE_SENTENCES;

    /** A field with a cursor at the end, written only by appending, as the IME does. */
    private static final class Field {
        final StringBuilder text = new StringBuilder();
        final PieceJoiner joiner = new PieceJoiner();

        void say(String piece, float pauseBefore) {
            text.append(joiner.join(piece, pauseBefore, text.toString(), "",
                    FieldKind.PROSE, NO_CAPS, THRESHOLD));
        }

        String end() {
            text.append(joiner.finish());
            return text.toString();
        }
    }

    private static Field session() {
        return new Field();
    }

    private static final class SentenceCapsField {
        final StringBuilder text = new StringBuilder();
        final PieceJoiner joiner = new PieceJoiner();
        boolean hasAcceptedPiece;

        void say(String piece, float pauseBefore) {
            int capsMode = PieceJoiner.capsModeForPiece(SENTENCE_CAPS, hasAcceptedPiece);
            String accepted = joiner.join(piece, pauseBefore, text.toString(), "",
                    FieldKind.PROSE, capsMode, THRESHOLD);
            text.append(accepted);
            hasAcceptedPiece = true;
        }

        void sayWhileDeliveryIsPending(String piece, float pauseBefore) {
            int capsMode = PieceJoiner.capsModeForPiece(SENTENCE_CAPS, hasAcceptedPiece);
            // The editor itself is still empty. The saved accepted text is the owned
            // context for later pieces while delivery is pending.
            String accepted = joiner.join(piece, pauseBefore, text.toString(), "",
                    FieldKind.PROSE, capsMode, THRESHOLD);
            text.append(accepted);
            hasAcceptedPiece = true;
        }

        String end() {
            text.append(joiner.finish());
            return text.toString();
        }
    }

    // ------------------------------------------- the case that embarrasses the rule

    @Test public void aHesitationBeforeARealProperNounLowersIt() {
        // The accepted loss, stated out loud so nobody discovers it in use. The model
        // capitalises the first word of every piece because it transcribed that piece
        // alone, so a capital at a piece start carries no information at all; it cannot
        // be told apart from a name. Continuing the sentence is the common case and
        // wins. If this test ever has to change, it is this trade that changed.
        Field f = session();
        f.say("I was talking to.", 0f);
        f.say("Sarah called me yesterday.", 1.0f);
        assertEquals("I was talking to sarah called me yesterday. ", f.end());
    }

    @Test public void afterARealPauseTheSameProperNounKeepsItsCapital() {
        // The other half of the same trade: a real sentence end protects the name.
        Field f = session();
        f.say("I was talking to her.", 0f);
        f.say("Sarah called me yesterday.", 5.0f);
        assertEquals("I was talking to her. Sarah called me yesterday. ", f.end());
    }

    @Test public void anAcronymSurvivesAHesitation() {
        // rule 3 protects a word that is not merely capitalised.
        Field f = session();
        f.say("We talked about.", 0f);
        f.say("NASA and its budget.", 1.0f);
        assertEquals("We talked about NASA and its budget. ", f.end());
    }

    @Test public void theWordIsurvivesAHesitation() {
        Field f = session();
        f.say("She said that.", 0f);
        f.say("I would go.", 1.0f);
        assertEquals("She said that I would go. ", f.end());
    }

    @Test public void staleSentenceCapsDoesNotCapitalizeAContinuation() {
        SentenceCapsField f = new SentenceCapsField();
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", 2.0f);
        assertEquals("I was thinking that we should go home. ", f.end());
    }

    @Test public void sentenceCapsStillKeepsANewSentenceAfterALongPause() {
        SentenceCapsField f = new SentenceCapsField();
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", 5.0f);
        assertEquals("I was thinking. That we should go home. ", f.end());
    }

    @Test public void pendingDeliveryStillUsesTheAcceptedPieceAsContext() {
        SentenceCapsField f = new SentenceCapsField();
        f.sayWhileDeliveryIsPending("I was thinking.", 0f);
        f.sayWhileDeliveryIsPending("That we should go home.", 2.0f);
        assertEquals("I was thinking that we should go home. ", f.end());
    }

}
