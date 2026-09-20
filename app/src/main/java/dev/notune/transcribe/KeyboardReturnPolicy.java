package dev.notune.transcribe;

final class KeyboardReturnPolicy {
    private KeyboardReturnPolicy() { }

    enum InsertionResult {
        ACCEPTED,
        NOT_SENT,
        POSSIBLY_SENT
    }

    static boolean shouldSwitch(boolean manuallyRequested, boolean automaticEnabled,
                                boolean insertionAccepted) {
        return manuallyRequested || automaticEnabled && insertionAccepted;
    }

    static InsertionResult classifyInsertion(boolean commitReturned, boolean commitThrew,
                                              EditorSnapshot before, EditorSnapshot after,
                                              String inserted) {
        if (commitThrew) return InsertionResult.POSSIBLY_SENT;
        if (!commitReturned) return InsertionResult.NOT_SENT;
        if (before != null && after != null && !after.isExactCommitOf(before, inserted)) {
            return InsertionResult.POSSIBLY_SENT;
        }
        return InsertionResult.ACCEPTED;
    }

    static boolean shouldAutoReplay(InsertionResult result) {
        return result == InsertionResult.NOT_SENT;
    }

    static final class EditorSnapshot {
        final String text;
        final int startOffset;
        final int selectionStart;
        final int selectionEnd;

        EditorSnapshot(String text, int startOffset, int selectionStart, int selectionEnd) {
            this.text = text;
            this.startOffset = startOffset;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
        }

        boolean isExactCommitOf(EditorSnapshot before, String inserted) {
            int start = before.selectionStart - before.startOffset;
            int end = before.selectionEnd - before.startOffset;
            if (start < 0 || end < start || end > before.text.length()) return false;
            String expected = before.text.substring(0, start) + inserted
                    + before.text.substring(end);
            int cursor = before.selectionStart + inserted.length();
            return startOffset == before.startOffset && expected.equals(text)
                    && selectionStart == cursor && selectionEnd == cursor;
        }
    }
}
