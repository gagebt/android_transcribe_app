package dev.notune.transcribe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EditorContextTest {
    @Test public void unavailableReadsDoNotInventAnEditorChange() {
        assertTrue(EditorContext.matchesKnown(4, 4, null, "left", null, "right", null));
    }

    @Test public void knownSelectionOrTextChangeStillVetoesAutomaticInsertion() {
        assertFalse(EditorContext.matchesKnown(4, 4, new int[]{5, 5},
                "left", "left", "right", "right"));
        assertFalse(EditorContext.matchesKnown(4, 4, new int[]{4, 4},
                "left", "changed", "right", "right"));
        assertFalse(EditorContext.matchesKnown(4, 4, new int[]{4, 4},
                "left", "left", "right", "changed"));
    }

    @Test public void allKnownUnchangedFactsPass() {
        assertTrue(EditorContext.matchesKnown(4, 4, new int[]{4, 4},
                "left", "left", "right", "right"));
    }

    @Test public void ownedCommitSelectionCanAnchorAnUnreadableEditor() {
        assertTrue(EditorContext.isOwnedCommitSelection(-1, 7, 0, 7, 7));
        assertTrue(EditorContext.isOwnedCommitSelection(10, 4, 10, 14, 14));
    }

    @Test public void unrelatedOrNonCollapsedSelectionIsNotOwned() {
        assertFalse(EditorContext.isOwnedCommitSelection(-1, 7, 0, 3, 3));
        assertFalse(EditorContext.isOwnedCommitSelection(-1, 7, 0, 7, 8));
        assertFalse(EditorContext.isOwnedCommitSelection(-1, 7, -1, 7, 7));
    }
}
