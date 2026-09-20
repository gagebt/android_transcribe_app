package dev.notune.transcribe;

/**
 * Joins the pieces of one dictation session into the field, one append at a time.
 *
 * <p>Pure, like {@link TextFitter}: no {@code android.*} import, so a whole session
 * can be replayed in a unit test with a {@link StringBuilder} standing in for the
 * field.
 *
 * <p>Why it exists. The recording is cut into pieces at silences so that the text can
 * appear while the owner is still speaking. Each piece is transcribed on its own, so
 * the model ends every piece with a full stop and capitalises the first word of the
 * next one. A pause taken to think would therefore cut a sentence in two:
 * "I was thinking" + "that we should go" becomes "I was thinking. That we should go."
 *
 * <p>The cure is to wait before believing the full stop. A piece is committed without
 * its terminal mark; the mark is held. The silence before the next piece then decides:
 * a short one was a hesitation and the mark is dropped, so the next piece continues the
 * sentence; a long one was a real sentence end and the mark is emitted first. At the
 * end of the session the last held mark is emitted as the model produced it.
 *
 * <p>Nothing here ever revises text that is already in the field. Emitting a held mark
 * is an append at the cursor, exactly where the mark would have gone. How the next
 * piece joins what is now in front of the cursor is not a second mechanism: it is the
 * same question {@link TextFitter} already answers, so it is asked of {@link TextFitter}
 * with the text the field will hold at that moment.
 */
public final class PieceJoiner {

    /** The owner's number: how long a pause has to be before it ends a sentence. */
    public static final float DEFAULT_SENTENCE_PAUSE_SECONDS = 3.0f;

    /** The terminal mark of the last committed piece, plus the space that followed it. */
    private String heldTail = "";

    /** The editor's start-of-sentence vote applies only before this session has text. */
    static int capsModeForPiece(int initialCapsMode, boolean hasAcceptedPiece) {
        return hasAcceptedPiece ? 0 : initialCapsMode;
    }

    /**
     * The exact string to commit for one piece. It is one append; the caller writes it
     * with a single {@code commitText} and reads nothing else.
     *
     * @param raw                  the model's own output for this piece
     * @param pauseBeforeSeconds   silence before this piece, measured from the audio by
     *                             the transcriber. 0 for the first piece of a session
     * @param before               the text in front of the cursor, or null if unreadable
     * @param after                the text behind the cursor, or null if unreadable
     * @param sentencePauseSeconds the threshold a pause must reach to end a sentence
     */
    public String join(String raw, float pauseBeforeSeconds, CharSequence before,
                       CharSequence after, TextFitter.FieldKind kind, int capsMode,
                       float sentencePauseSeconds) {
        // 1. Does the silence before this piece mean the last sentence really ended?
        String emit = "";
        if (!heldTail.isEmpty()) {
            if (pauseBeforeSeconds >= sentencePauseSeconds) emit = heldTail;
            heldTail = "";
        }

        // 2. Ask the fitter how the piece joins the field as it will then be. The mark
        //    just emitted is part of that text, so the fitter sees it and treats what
        //    follows as a new sentence.
        CharSequence effectiveBefore = before;
        if (before != null && !emit.isEmpty()) effectiveBefore = before.toString() + emit;
        TextFitter.Fit fit = TextFitter.fit(raw, effectiveBefore, after, kind, capsMode);
        if (fit.isEmpty()) return emit;

        // 3. Hold this piece's own terminal mark until the next piece, or the end of
        //    the session, says what it meant. Only prose has sentences: a search box, a
        //    number or a password is never held back.
        //    A field that cannot be read is left exactly as it was before streaming:
        //    with no text in front of the cursor to judge by, the fitter's own
        //    "spoken + space" is all there is, and holding its mark back would run two
        //    pieces together with no space at all.
        if (kind == TextFitter.FieldKind.PROSE && before != null) {
            String t = fit.text;
            if (t.length() >= 2 && TextFitter.endsWithTerminator(t)) {
                heldTail = t.substring(t.length() - 1) + fit.suffix;
                return emit + fit.prefix + t.substring(0, t.length() - 1);
            }
        }
        return emit + fit.inserted();
    }

    /**
     * The string to commit when the session ends: the last piece's own terminal mark,
     * exactly as the model produced it, and the space that goes after it. Empty when
     * there is nothing held.
     */
    public String finish() {
        String tail = heldTail;
        heldTail = "";
        return tail;
    }

    /** True while a terminal mark is waiting for the next piece to explain it. */
    public boolean hasHeldTail() {
        return !heldTail.isEmpty();
    }

    String pendingTail() {
        return heldTail;
    }

    void restorePendingTail(String tail) {
        heldTail = tail == null ? "" : tail;
    }
}
