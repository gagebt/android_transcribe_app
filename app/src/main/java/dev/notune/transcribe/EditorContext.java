package dev.notune.transcribe;

/** Compares only editor facts that both sides could read. */
final class EditorContext {
    private EditorContext() { }

    static boolean matchesKnown(
            int expectedSelectionStart,
            int expectedSelectionEnd,
            int[] actualSelection,
            String expectedBefore,
            String actualBefore,
            String expectedAfter,
            String actualAfter) {
        if (expectedSelectionStart >= 0 && actualSelection != null
                && (actualSelection[0] != expectedSelectionStart
                || actualSelection[1] != expectedSelectionEnd)) return false;
        if (expectedBefore != null && actualBefore != null
                && !expectedBefore.equals(actualBefore)) return false;
        return expectedAfter == null || actualAfter == null
                || expectedAfter.equals(actualAfter);
    }

    /** True only for the cursor advance produced by one owned commit. */
    static boolean isOwnedCommitSelection(
            int selectionBeforeCommit,
            int insertedCharacters,
            int oldSelectionStart,
            int newSelectionStart,
            int newSelectionEnd) {
        if (insertedCharacters <= 0 || newSelectionStart != newSelectionEnd) return false;
        int base = selectionBeforeCommit >= 0 ? selectionBeforeCommit : oldSelectionStart;
        return base >= 0 && newSelectionStart == base + insertedCharacters;
    }
}
