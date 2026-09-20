package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.AtomicFile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusView;
    private TextView hintView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    // One native recording owns every callback and one ordered recovery draft.
    private final PieceJoiner joiner = new PieceJoiner();
    private float sentencePauseSeconds = PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
    private long activeSessionId = 0;
    private long nextPieceSequence = 0;
    private boolean hasAcceptedPiece = false;
    private boolean sessionTerminal = true;
    private boolean nativeRetryAvailable = false;
    private String undeliveredText = "";
    private PendingDictationDraft pendingDraft;
    private AtomicFile pendingDraftFile;
    private String recoveryReadError;

    // What the panel is doing. Explicit, because updateUiState() infers the old
    // states by string-matching the status text and that pattern must not grow.
    private static final int PANEL_IDLE = 0;
    private static final int PANEL_RECORDING = 1;
    private static final int PANEL_FINISHING = 2;
    private int panel = PANEL_IDLE;
    // The tail of the text already committed in this session, shown in the panel
    // while recording. It is a comfort, never the truth: the field is the truth.
    private String panelTail = "";
    private static final int PANEL_TAIL_CHARS = 64;

    // How much of the field is read around the cursor before each insertion.
    // The rule needs the sentence the cursor sits in: behind the cursor it walks
    // back over spaces, closing and opening brackets and quotes to find the last
    // terminal mark, and ahead of it it needs the first character that is not a
    // space plus the punctuation just after it. A long English or Russian prose
    // sentence is about 300 characters, so 2048 behind gives roughly six times
    // the margin, and 512 ahead covers a whole following clause. Both are small
    // reads: 2048 UTF-16 characters are 4 KB, far below the Binder limit, and
    // they happen once per finished piece, not per word.
    //
    // These are ceilings, not demands. getTextBeforeCursor and getTextAfterCursor
    // may legally return less than asked, and a large document or a WebView often
    // does; a short answer is the normal case here, not an error, and the fitter
    // works from whatever came back. A null answer still degrades to today's exact
    // behaviour. The call is never waited on beyond its own return: a target that
    // throws is caught below and the insertion still happens.
    private static final int CONTEXT_BEFORE_CHARS = 2048;
    private static final int CONTEXT_AFTER_CHARS = 512;
    // A one-off message that outlives the session, e.g. "Text copied".
    private String panelMessage = null;
    private View recoveryPanel;
    private TextView recoveryText;
    private Button copyButton;
    private Button discardButton;
    private Button retryButton;
    private Button insertButton;

    // Metadata can veto a target. Authority comes from the same input lifecycle,
    // captured selection, and unchanged surrounding text.
    private android.os.IBinder targetToken = null;
    private String targetSignature = null;
    private boolean targetKnown = false;
    private boolean automaticDeliveryAllowed = false;
    private long inputGeneration = 0;
    private long targetGeneration = -1;
    private int expectedSelectionStart = -1;
    private int expectedSelectionEnd = -1;
    // Android reports the cursor movement caused by commitText through
    // onUpdateSelection. Blind editors cannot expose the starting selection, so
    // one bounded marker distinguishes that owned callback from a user move.
    private int ownedSelectionCharacters = 0;
    private int ownedSelectionBase = -1;
    private boolean ownedSelectionObserved = false;
    private String expectedBefore = null;
    private String expectedAfter = null;
    private int dictationSelectionStart = -1;
    private TextFitter.FieldKind targetFieldKind = TextFitter.FieldKind.PROSE;
    private int targetCapsMode = 0;

    // Characters this session has committed contiguously into the target field,
    // for the opt-in select_transcription setting at the end of the session.
    private int sessionCommittedChars = 0;

    private static final int OUTCOME_SUCCESS = 0;
    private static final int OUTCOME_RETRYABLE = 1;
    private static final int OUTCOME_CANCELLED = 2;
    private static final int OUTCOME_INTERRUPTED = 3;
    private static final int OUTCOME_FATAL = 4;
    private static final int OUTCOME_REVIEW = 5;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        pendingDraftFile = new AtomicFile(new File(getNoBackupFilesDir(), "pending-dictation"));
        pendingDraft = readPendingDraft();
        if (pendingDraft != null) {
            activeSessionId = pendingDraft.sessionId;
            nextPieceSequence = pendingDraft.nextSequence;
            undeliveredText = pendingDraft.text;
            sessionTerminal = true;
            if (PendingDictationDraft.PENDING.equals(pendingDraft.state)
                    || PendingDictationDraft.RETRYABLE.equals(pendingDraft.state)) {
                pendingDraft = pendingDraft.with(
                        PendingDictationDraft.INTERRUPTED, pendingDraft.text,
                        pendingDraft.nextSequence);
                writePendingDraft(pendingDraft);
            }
        } else if (recoveryReadError != null) {
            panelMessage = recoveryReadError;
        }
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Throwable t) {
            // Native may be unavailable (e.g. wrong-ABI emulator); don't crash the IME.
            Log.e(TAG, "Error in initNative", t);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            // The IME is a non-AppCompat Service in a separate process, so
            // AppCompat's delegate can't theme it. Build a context that is
            // night-aware (per the saved preference), wears the Material 3 theme,
            // and picks up Material You dynamic color — matching the app.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = DynamicColors.wrapContextIfAvailable(
                    new ContextThemeWrapper(night, R.style.AppTheme));
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Handle window insets for avoiding navigation bar overlap
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            recoveryPanel = view.findViewById(R.id.ime_recovery_panel);
            recoveryText = view.findViewById(R.id.ime_recovery_text);
            copyButton = view.findViewById(R.id.ime_recovery_copy);
            discardButton = view.findViewById(R.id.ime_recovery_discard);
            retryButton = view.findViewById(R.id.ime_recovery_retry);
            insertButton = view.findViewById(R.id.ime_recovery_insert);

            copyButton.setOnClickListener(v -> copyPendingDraft());
            discardButton.setOnClickListener(v -> discardPendingDraft());
            retryButton.setOnClickListener(v -> retryPendingInference());
            insertButton.setOnClickListener(v -> insertPendingDraft());

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    stopDictation();
                } else {
                    switchBackToPreviousKeyboard();
                }
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                        }
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.commitText(" ", 1);
                        }
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText("No mic permission - grant in app");
                    if (hintView != null) hintView.setText("Open the app to grant permission");
                    return;
                }

                if (isRecording) {
                    // Everything must be committed before the keyboard hands
                    // itself back, so the switch waits for the end of session.
                    if (isSwitchBackEnabled()) pendingSwitchBack = true;
                    stopDictation();
                } else {
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    startDictation();
                }
            });

            tintRecordButton(false);
            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        boolean wasVisible = windowVisible;
        windowVisible = true;
        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        if (!sessionTerminal) {
            updateRecordButtonUI(false);
            return;
        }
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        if (isAutoRecordEnabled()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                startDictation();
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        if (isRecording) {
            if (isStopOnHideEnabled()) {
                // Opt-in behavior: discard the recording when the keyboard hides.
                // Pieces already committed stay in the field — he did say them.
                // Only the piece still being recorded is discarded.
                panel = PANEL_IDLE;
                pendingSwitchBack = false;
                try {
                    cancelRecording(activeSessionId);
                } catch (Throwable t) {
                    Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                    panel = PANEL_FINISHING;
                    try { stopRecording(activeSessionId); } catch (Throwable ignored) { }
                }
                updateRecordButtonUI(false);
            } else {
                // Default: keep recording in the background. The transcription
                // remains bound to this session and target.
                return;
            }
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputGeneration++;
        if (!sessionTerminal) automaticDeliveryAllowed = false;
        clearOwnedSelectionUpdate();
        inputActive = true;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) IME process stayed alive, so it matches the app setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            setInputView(onCreateInputView());
        }
        renderRecovery();
    }

    /**
     * Records the field dictation starts in and resets the per-session state.
     * Called immediately before native capture starts.
     */
    private boolean beginSession(long sessionId) {
        if (!sessionTerminal) {
            panelMessage = "Finishing the previous dictation";
            renderStatus();
            return false;
        }
        if (pendingDraft != null || recoveryReadError != null) {
            panelMessage = "Resolve saved dictation first";
            renderRecovery();
            renderStatus();
            return false;
        }
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (inputActive && ic != null) {
            try { ic.finishComposingText(); } catch (Throwable t) {
                Log.w(TAG, "could not finish existing composition", t);
            }
        }
        activeSessionId = sessionId;
        nextPieceSequence = 0;
        hasAcceptedPiece = false;
        sessionTerminal = false;
        nativeRetryAvailable = false;
        undeliveredText = "";
        panel = PANEL_RECORDING;
        joiner.finish();
        sentencePauseSeconds = readSentencePauseSeconds();
        panelTail = "";
        panelMessage = null;
        sessionCommittedChars = 0;
        clearOwnedSelectionUpdate();
        pendingSwitchBack = false;
        expectedBefore = readBefore(ic);
        expectedAfter = readAfter(ic);
        int[] selection = readSelection(ic);
        if (inputActive && info != null && ic != null) {
            targetToken = connectionToken();
            targetSignature = fieldSignature(info);
            targetKnown = true;
            automaticDeliveryAllowed = true;
            targetGeneration = inputGeneration;
            expectedSelectionStart = selection == null ? -1 : selection[0];
            expectedSelectionEnd = selection == null ? -1 : selection[1];
            dictationSelectionStart = selection == null ? -1 : selection[0];
            targetFieldKind = FieldKinds.of(info);
            try { targetCapsMode = ic.getCursorCapsMode(info.inputType); }
            catch (Throwable t) { targetCapsMode = 0; }
        } else {
            targetToken = null;
            targetSignature = null;
            targetKnown = false;
            automaticDeliveryAllowed = false;
            targetGeneration = -1;
            expectedSelectionStart = -1;
            expectedSelectionEnd = -1;
            dictationSelectionStart = -1;
            targetFieldKind = TextFitter.FieldKind.PROSE;
            targetCapsMode = 0;
        }
        return true;
    }

    private long newSessionId() {
        return Math.max(activeSessionId + 1, Math.max(1, SystemClock.elapsedRealtimeNanos()));
    }

    private void startDictation() {
        long sessionId = newSessionId();
        if (!beginSession(sessionId)) {
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            return;
        }
        boolean started = false;
        try { started = startRecording(sessionId); } catch (Throwable t) {
            Log.e(TAG, "startRecording failed", t);
        }
        if (started) {
            updateRecordButtonUI(true);
        } else {
            panel = PANEL_FINISHING;
            updateRecordButtonUI(false);
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        }
    }

    /** Ends capture. The switch back to the previous keyboard waits for the text. */
    private void stopDictation() {
        panel = PANEL_FINISHING;
        stopRecording(activeSessionId);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        updateRecordButtonUI(false);
    }

    /**
     * True when the focused field is the one dictation started in. A wrong-field
     * write is the one failure the owner cannot repair without noticing it, so a
     * field this session did not start in never receives text.
     */
    private boolean isTargetField(EditorInfo info) {
        if (!targetKnown || !automaticDeliveryAllowed || targetGeneration != inputGeneration) {
            return false;
        }
        if (info == null) return false;
        // The input-connection token is a veto, never a pass. Measured on the
        // emulator bench: one token serves every field of one window, so the same
        // token says nothing about which field is focused, while a different token
        // is certainly a different client and so certainly not the target field.
        android.os.IBinder token = connectionToken();
        if (targetToken != null && token != null && !targetToken.equals(token)) return false;
        // What the editor says about itself decides. This is a signature, not an
        // identity: two fields in one window that agree on every one of these
        // would still pass. It rejects the cases that matter — another app, and
        // another kind of field, including the password field next to the prose
        // one that package plus fieldId alone let through.
        String sig = fieldSignature(info);
        return targetSignature != null && targetSignature.equals(sig);
    }

    private android.os.IBinder connectionToken() {
        try {
            android.view.inputmethod.InputBinding binding = getCurrentInputBinding();
            return binding == null ? null : binding.getConnectionToken();
        } catch (Throwable t) {
            return null;
        }
    }

    private String fieldSignature(EditorInfo info) {
        if (info == null) return null;
        return info.packageName + "|" + info.fieldId + "|" + info.inputType + "|"
                + info.imeOptions + "|" + info.fieldName + "|"
                + String.valueOf(info.hintText) + "|" + String.valueOf(info.label);
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        inputGeneration++;
        if (!sessionTerminal) automaticDeliveryAllowed = false;
        clearOwnedSelectionUpdate();
        inputActive = false;
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
                                  int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        if (!sessionTerminal && automaticDeliveryAllowed) {
            if (ownedSelectionCharacters > 0 && EditorContext.isOwnedCommitSelection(
                    ownedSelectionBase, ownedSelectionCharacters, oldSelStart,
                    newSelStart, newSelEnd)) {
                expectedSelectionStart = newSelStart;
                expectedSelectionEnd = newSelEnd;
                ownedSelectionObserved = true;
                ownedSelectionCharacters = 0;
                ownedSelectionBase = -1;
                return;
            }
            if (expectedSelectionStart < 0 || newSelStart != expectedSelectionStart
                    || newSelEnd != expectedSelectionEnd) {
                automaticDeliveryAllowed = false;
                clearOwnedSelectionUpdate();
            }
        }
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
        }
        tintRecordButton(recording);
        if (!recording && micLevelView != null) micLevelView.setLevel(0f);
        renderStatus();
    }

    /**
     * The panel's one line. It is driven by the explicit panel state, never by
     * string-matching the native status text.
     */
    private void renderStatus() {
        if (statusView == null) return;
        if (panel == PANEL_RECORDING) {
            statusView.setText(panelMessage != null
                    ? panelMessage : panelTail.isEmpty() ? "Listening..." : panelTail);
            if (hintView != null) hintView.setText("Tap to Stop");
            return;
        }
        if (hintView != null) hintView.setText("Tap to Record");
        if (panel == PANEL_FINISHING) {
            statusView.setText("Finishing...");
            return;
        }
        if (lastStatus != null && lastStatus.startsWith("Error")) {
            statusView.setText(lastStatus);
        } else if (panelMessage != null) {
            statusView.setText(panelMessage);
        } else {
            statusView.setText("Tap to Record");
        }
    }

    /** Adds a committed piece to the panel's tail, newest words last. */
    private void appendPanelTail(String piece) {
        String joined = panelTail.isEmpty() ? piece : panelTail + " " + piece;
        panelTail = joined.length() > PANEL_TAIL_CHARS
                ? joined.substring(joined.length() - PANEL_TAIL_CHARS)
                : joined;
        renderStatus();
    }

    /** Tints the round record button + mic: idle = primary, recording = error. */
    private void tintRecordButton(boolean recording) {
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorPrimaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnPrimaryContainer;
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native boolean startRecording(long sessionId);
    private native boolean stopRecording(long sessionId);
    private native boolean cancelRecording(long sessionId);
    private native boolean retryRecording(long sessionId);

    // Engine loading is not a dictation event and cannot settle a session.
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Engine status: " + status);
            if (sessionTerminal && pendingDraft == null && recoveryReadError == null) {
                lastStatus = status;
                updateUiState();
            }
        });
    }

    public void onDictationStatus(long sessionId, String status) {
        mainHandler.post(() -> {
            if (sessionId != activeSessionId) return;
            lastStatus = status == null ? "" : status;
            updateUiState();
        });
    }

    // Called from Rust for both silence endpointing and a capture-stream failure.
    public void onAutoStop(long sessionId) {
        if (sessionId != activeSessionId || sessionTerminal) return;
        mainHandler.post(() -> {
            if (sessionId != activeSessionId || sessionTerminal || !isRecording) return;
            if (isSwitchBackEnabled()) pendingSwitchBack = true;
            try {
                stopDictation();
            } catch (Throwable t) {
                Log.e(TAG, "automatic stop failed", t);
                pendingSwitchBack = false;
                panel = PANEL_IDLE;
                panelMessage = "Could not stop recording";
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                updateRecordButtonUI(false);
            }
        });
    }

    private void updateUiState() {
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean isTranscribing = lastStatus.contains("Transcribing")
                || lastStatus.contains("Processing") || lastStatus.contains("Retrying");
        renderStatus();
        renderRecovery();
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (recordContainer != null) {
            boolean disable = (isTranscribing || isWaiting)
                    && panel != PANEL_RECORDING;
            boolean unresolvedBlocksStart = (pendingDraft != null || recoveryReadError != null)
                    && panel != PANEL_RECORDING;
            recordContainer.setEnabled(!disable && !unresolvedBlocksStart);
            recordContainer.setAlpha((disable || unresolvedBlocksStart) ? 0.5f : 1.0f);
        }
    }

    public boolean onTranscriptPiece(long sessionId, long pieceSequence,
                                     String text, float pauseBeforeSeconds) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return acceptPiece(sessionId, pieceSequence, text, pauseBeforeSeconds);
        }
        final boolean[] accepted = {false};
        CountDownLatch done = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                accepted[0] = acceptPiece(sessionId, pieceSequence, text, pauseBeforeSeconds);
            } finally {
                done.countDown();
            }
        });
        try {
            return done.await(15, TimeUnit.SECONDS) && accepted[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean acceptPiece(long sessionId, long pieceSequence,
                                String text, float pauseBeforeSeconds) {
        if (sessionId != activeSessionId || sessionTerminal || text == null
                || text.trim().isEmpty()) return false;
        if (pieceSequence < nextPieceSequence) return true;
        String oldTail = joiner.pendingTail();
        int capsMode = PieceJoiner.capsModeForPiece(targetCapsMode, hasAcceptedPiece);
        // The focused editor can stop being the target while native inference keeps
        // producing pieces. Extend the captured context with this session's saved,
        // undelivered text instead of consulting whichever editor is focused now.
        CharSequence ownedBefore = expectedBefore;
        if (expectedBefore != null && !undeliveredText.isEmpty()) {
            ownedBefore = expectedBefore + undeliveredText;
        }
        String candidate = joiner.join(text, pauseBeforeSeconds, ownedBefore, expectedAfter,
                targetFieldKind, capsMode, sentencePauseSeconds);
        String stagedText = undeliveredText + candidate + joiner.pendingTail();
        PendingDictationDraft staged = pendingDraft == null
                ? new PendingDictationDraft(
                        sessionId, pieceSequence + 1, PendingDictationDraft.PENDING, stagedText)
                : pendingDraft.preservingDeliveryRisk(
                        PendingDictationDraft.PENDING, stagedText, pieceSequence + 1);
        if (!writePendingDraft(staged)) {
            joiner.restorePendingTail(oldTail);
            panelMessage = "Could not save dictated text. Retry is available.";
            renderStatus();
            return false;
        }
        pendingDraft = staged;
        hasAcceptedPiece = true;
        if (!staged.mayAlreadyBeDelivered()) panelMessage = null;
        nextPieceSequence = pieceSequence + 1;
        undeliveredText += candidate;
        appendPanelTail(candidate);
        if (!undeliveredText.isEmpty() && automaticDeliveryAllowed) deliverStagedText();
        renderRecovery();
        return true;
    }

    public void onDictationComplete(long sessionId, int outcome, String text, String error) {
        mainHandler.post(() -> finishSession(sessionId, outcome, text, error));
    }

    private void finishSession(long sessionId, int outcome, String text, String error) {
        if (sessionId != activeSessionId || sessionTerminal) return;
        sessionTerminal = true;
        panel = PANEL_IDLE;
        updateRecordButtonUI(false);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        if (outcome == OUTCOME_SUCCESS) {
            String tail = joiner.finish();
            if (!tail.isEmpty()) {
                undeliveredText += tail;
                PendingDictationDraft staged = pendingDraft == null
                        ? new PendingDictationDraft(sessionId, nextPieceSequence,
                                PendingDictationDraft.PENDING, undeliveredText)
                        : pendingDraft.preservingDeliveryRisk(
                                PendingDictationDraft.PENDING, undeliveredText,
                                nextPieceSequence);
                if (writePendingDraft(staged)) pendingDraft = staged;
                else panelMessage = "Could not save final punctuation";
            }
            if (!undeliveredText.isEmpty() && automaticDeliveryAllowed
                    && (pendingDraft == null
                    || !pendingDraft.mayAlreadyBeDelivered())) {
                deliverStagedText();
            }
            if (pendingDraft == null) {
                selectTranscriptionIfEnabled();
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchBackToPreviousKeyboard();
                }
            } else {
                pendingSwitchBack = false;
            }
        } else if (outcome == OUTCOME_REVIEW) {
            joiner.finish();
            automaticDeliveryAllowed = false;
            nativeRetryAvailable = false;
            undeliveredText = "";
            PendingDictationDraft review = new PendingDictationDraft(
                    sessionId, nextPieceSequence, PendingDictationDraft.REVIEW, text);
            boolean saved = writePendingDraft(review);
            // Keep the complete native review candidate available to Copy even when
            // storage fails. AtomicFile leaves the previous saved draft intact.
            pendingDraft = review;
            panelMessage = !saved
                    ? "Could not save review text. Copy it now; earlier words may already be in the field."
                    : error == null || error.isEmpty()
                    ? "Review final words. Earlier words may already be in the field."
                    : error;
            pendingSwitchBack = false;
        } else if (outcome == OUTCOME_RETRYABLE) {
            nativeRetryAvailable = true;
            String recovery = pendingDraft == null ? undeliveredText : pendingDraft.text;
            PendingDictationDraft retry = pendingDraft == null
                    ? new PendingDictationDraft(sessionId, nextPieceSequence,
                            PendingDictationDraft.RETRYABLE, recovery)
                    : pendingDraft.preservingDeliveryRisk(
                            PendingDictationDraft.RETRYABLE, recovery, nextPieceSequence);
            if (writePendingDraft(retry)) pendingDraft = retry;
            else pendingDraft = retry;
            panelMessage = error == null || error.isEmpty() ? "Dictation failed" : error;
        } else if (outcome == OUTCOME_CANCELLED) {
            joiner.finish();
            if (pendingDraft != null && pendingDraft.text.isEmpty()) clearPendingDraft();
            pendingSwitchBack = false;
        } else {
            String recovery = pendingDraft == null ? undeliveredText : pendingDraft.text;
            if (!recovery.isEmpty()) {
                PendingDictationDraft interrupted = pendingDraft == null
                        ? new PendingDictationDraft(sessionId, nextPieceSequence,
                                PendingDictationDraft.INTERRUPTED, recovery)
                        : pendingDraft.preservingDeliveryRisk(
                                PendingDictationDraft.INTERRUPTED, recovery,
                                nextPieceSequence);
                if (writePendingDraft(interrupted)) pendingDraft = interrupted;
            }
            panelMessage = error == null || error.isEmpty() ? "Dictation interrupted" : error;
            pendingSwitchBack = false;
        }
        updateUiState();
    }

    public void onDictationLevel(long sessionId, float level) {
        if (sessionId != activeSessionId) return;
        mainHandler.post(() -> {
            if (sessionId == activeSessionId && micLevelView != null) micLevelView.setLevel(level);
        });
    }

    private void deliverStagedText() {
        if (undeliveredText.isEmpty() || pendingDraft == null) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (!inputActive || ic == null || !isTargetField(info) || !sameExpectedContext(ic)) {
            automaticDeliveryAllowed = false;
            return;
        }
        EditorSnapshot before = readEditorSnapshot(ic);
        PendingDictationDraft attempted = pendingDraft.with(
                PendingDictationDraft.ATTEMPTED, pendingDraft.text, nextPieceSequence);
        if (!writePendingDraft(attempted)) {
            automaticDeliveryAllowed = false;
            panelMessage = "Could not save the delivery attempt. Text was not sent.";
            renderStatus();
            renderRecovery();
            return;
        }
        pendingDraft = attempted;
        String sentText = undeliveredText;
        int selectionBeforeCommit = expectedSelectionStart;
        ownedSelectionCharacters = sentText.length();
        ownedSelectionBase = selectionBeforeCommit;
        ownedSelectionObserved = false;
        boolean accepted;
        try {
            accepted = ic.commitText(sentText, 1);
        } catch (Throwable t) {
            Log.w(TAG, "editor commit failed", t);
            markDeliveryUncertain();
            return;
        }
        if (!accepted) {
            if (ownedSelectionObserved) markDeliveryUncertain();
            else markDeliveryRejected();
            return;
        }
        EditorSnapshot after = readEditorSnapshot(ic);
        if (before != null && after != null && !after.isExactCommitOf(before, sentText)) {
            markDeliveryUncertain();
            return;
        }
        sessionCommittedChars += sentText.length();
        if (after != null) {
            expectedSelectionStart = after.selectionStart;
            expectedSelectionEnd = after.selectionEnd;
            clearOwnedSelectionUpdate();
        } else if (ownedSelectionObserved) {
            clearOwnedSelectionUpdate();
        } else if (selectionBeforeCommit >= 0) {
            int newCursor = selectionBeforeCommit + sentText.length();
            expectedSelectionStart = newCursor;
            expectedSelectionEnd = newCursor;
            clearOwnedSelectionUpdate();
        }
        String actualBefore = readBefore(ic);
        String actualAfter = readAfter(ic);
        expectedBefore = actualBefore != null ? actualBefore
                : expectedBefore == null ? null : expectedBefore + sentText;
        if (actualAfter != null) expectedAfter = actualAfter;
        undeliveredText = "";
        String heldTail = joiner.pendingTail();
        if (heldTail.isEmpty()) {
            if (!clearPendingDraft()) automaticDeliveryAllowed = false;
        } else {
            PendingDictationDraft tail = new PendingDictationDraft(
                    activeSessionId, nextPieceSequence, PendingDictationDraft.PENDING, heldTail);
            if (writePendingDraft(tail)) pendingDraft = tail;
            else markDeliveryUncertain();
        }
    }

    private void markDeliveryUncertain() {
        clearOwnedSelectionUpdate();
        automaticDeliveryAllowed = false;
        String text = pendingDraft == null ? undeliveredText + joiner.pendingTail() : pendingDraft.text;
        PendingDictationDraft uncertain = new PendingDictationDraft(
                activeSessionId, nextPieceSequence, PendingDictationDraft.UNCERTAIN, text);
        if (writePendingDraft(uncertain)) pendingDraft = uncertain;
        else pendingDraft = uncertain;
        panelMessage = "Editor delivery could not be confirmed";
    }

    private void markDeliveryRejected() {
        clearOwnedSelectionUpdate();
        automaticDeliveryAllowed = false;
        PendingDictationDraft rejected = pendingDraft == null ? null : pendingDraft.with(
                PendingDictationDraft.PENDING, pendingDraft.text, nextPieceSequence);
        if (rejected != null && writePendingDraft(rejected)) pendingDraft = rejected;
        panelMessage = "Editor rejected dictated text";
    }

    private void clearOwnedSelectionUpdate() {
        ownedSelectionCharacters = 0;
        ownedSelectionBase = -1;
        ownedSelectionObserved = false;
    }

    private boolean sameExpectedContext(InputConnection ic) {
        int[] selection = readSelection(ic);
        String before = readBefore(ic);
        String after = readAfter(ic);
        return EditorContext.matchesKnown(expectedSelectionStart, expectedSelectionEnd,
                selection, expectedBefore, before, expectedAfter, after);
    }

    private static final class EditorSnapshot {
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
            String expected = before.text.substring(0, start) + inserted + before.text.substring(end);
            int cursor = before.selectionStart + inserted.length();
            return startOffset == before.startOffset && expected.equals(text)
                    && selectionStart == cursor && selectionEnd == cursor;
        }
    }

    private EditorSnapshot readEditorSnapshot(InputConnection ic) {
        try {
            android.view.inputmethod.ExtractedTextRequest request =
                    new android.view.inputmethod.ExtractedTextRequest();
            request.hintMaxChars = 1024 * 1024;
            android.view.inputmethod.ExtractedText extracted = ic.getExtractedText(request, 0);
            if (extracted == null || extracted.text == null) return null;
            return new EditorSnapshot(extracted.text.toString(), extracted.startOffset,
                    extracted.selectionStart, extracted.selectionEnd);
        } catch (Throwable t) {
            return null;
        }
    }

    private int[] readSelection(InputConnection ic) {
        EditorSnapshot snapshot = ic == null ? null : readEditorSnapshot(ic);
        return snapshot == null ? null : new int[]{snapshot.selectionStart, snapshot.selectionEnd};
    }

    private String readBefore(InputConnection ic) {
        if (ic == null) return null;
        try {
            CharSequence value = ic.getTextBeforeCursor(CONTEXT_BEFORE_CHARS, 0);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private String readAfter(InputConnection ic) {
        if (ic == null) return null;
        try {
            CharSequence value = ic.getTextAfterCursor(CONTEXT_AFTER_CHARS, 0);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private PendingDictationDraft readPendingDraft() {
        try {
            PendingDictationDraft restored =
                    PendingDictationDraft.decode(pendingDraftFile.readFully());
            if (restored == null) {
                recoveryReadError = "Saved dictation is damaged. Discard it to continue.";
                Log.e(TAG, "pending dictation record is malformed");
                return null;
            }
            recoveryReadError = null;
            return restored;
        } catch (FileNotFoundException e) {
            if (!pendingDraftArtifactsExist()) return null;
            recoveryReadError = "Saved dictation could not be read. Discard it to continue.";
            Log.e(TAG, "pending dictation artifacts exist but cannot be opened", e);
            return null;
        } catch (Throwable t) {
            recoveryReadError = "Saved dictation could not be read. Discard it to continue.";
            Log.e(TAG, "could not read pending dictation", t);
            return null;
        }
    }

    private boolean writePendingDraft(PendingDictationDraft draft) {
        FileOutputStream output = null;
        try {
            output = pendingDraftFile.startWrite();
            output.write(draft.encode());
            pendingDraftFile.finishWrite(output);
            output = null;
            PendingDictationDraft check = PendingDictationDraft.decode(pendingDraftFile.readFully());
            boolean matches = check != null && check.sessionId == draft.sessionId
                    && check.nextSequence == draft.nextSequence
                    && check.state.equals(draft.state) && check.text.equals(draft.text);
            if (matches) recoveryReadError = null;
            return matches;
        } catch (Throwable t) {
            if (output != null) pendingDraftFile.failWrite(output);
            Log.e(TAG, "could not persist pending dictation", t);
            return false;
        }
    }

    private boolean pendingDraftArtifactsExist() {
        File base = pendingDraftFile.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists()
                || new File(base.getPath() + ".new").exists();
    }

    private boolean clearPendingDraft() {
        try {
            pendingDraftFile.delete();
        } catch (Throwable t) {
            Log.e(TAG, "could not delete pending dictation", t);
        }
        if (pendingDraftArtifactsExist()) {
            panelMessage = "Could not discard saved dictation. Try again.";
            if (pendingDraft == null && recoveryReadError == null) {
                recoveryReadError = "Saved dictation could not be cleared.";
            }
            renderRecovery();
            renderStatus();
            return false;
        }
        pendingDraft = null;
        recoveryReadError = null;
        undeliveredText = "";
        nativeRetryAvailable = false;
        renderRecovery();
        return true;
    }

    private void renderRecovery() {
        if (recoveryPanel == null) return;
        if (recoveryReadError != null) {
            recoveryPanel.setVisibility(View.VISIBLE);
            recoveryText.setText(recoveryReadError);
            copyButton.setVisibility(View.GONE);
            insertButton.setVisibility(View.GONE);
            retryButton.setVisibility(View.GONE);
            discardButton.setVisibility(View.VISIBLE);
            return;
        }
        if (pendingDraft == null || (!sessionTerminal && automaticDeliveryAllowed
                && PendingDictationDraft.PENDING.equals(pendingDraft.state))) {
            recoveryPanel.setVisibility(View.GONE);
            return;
        }
        recoveryPanel.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
        insertButton.setVisibility(isInsertButtonEnabled() ? View.VISIBLE : View.GONE);
        discardButton.setVisibility(View.VISIBLE);
        String label = PendingDictationDraft.REVIEW.equals(pendingDraft.state)
                ? "Review final words; earlier words may already be in the field\n"
                : pendingDraft.mayAlreadyBeDelivered()
                ? "Delivery uncertain; this text may already be in the field\n"
                : PendingDictationDraft.INTERRUPTED.equals(pendingDraft.state)
                ? "Interrupted dictation\n" : "Saved dictation\n";
        recoveryText.setText(label + pendingDraft.text);
        retryButton.setVisibility(nativeRetryAvailable
                && pendingDraft.sessionId == activeSessionId ? View.VISIBLE : View.GONE);
    }

    private void copyPendingDraft() {
        if (pendingDraft == null) return;
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("clipboard unavailable");
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "dictation", pendingDraft.text));
            panelMessage = "Dictation copied. Discard it when safe.";
        } catch (Throwable t) {
            panelMessage = "Could not copy dictation";
            Log.w(TAG, "clipboard write failed", t);
        }
        renderStatus();
    }

    /** A tap explicitly grants one insertion of the saved draft at the current cursor. */
    private void insertPendingDraft() {
        if (pendingDraft == null || pendingDraft.text.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (!inputActive || ic == null) {
            panelMessage = "No text field is available";
            renderStatus();
            return;
        }
        PendingDictationDraft original = pendingDraft;
        PendingDictationDraft attempted = original.with(
                PendingDictationDraft.ATTEMPTED, original.text, original.nextSequence);
        if (!writePendingDraft(attempted)) {
            panelMessage = "Could not save the insertion attempt. Text was not sent.";
            renderStatus();
            return;
        }
        pendingDraft = attempted;
        boolean accepted;
        try {
            accepted = ic.commitText(attempted.text, 1);
        } catch (Throwable t) {
            Log.w(TAG, "explicit editor commit failed", t);
            markDeliveryUncertain();
            updateUiState();
            return;
        }
        if (!accepted) {
            if (writePendingDraft(original)) pendingDraft = original;
            panelMessage = "Editor rejected dictated text";
            updateUiState();
            return;
        }
        joiner.finish();
        if (nativeRetryAvailable) {
            try { cancelRecording(activeSessionId); } catch (Throwable ignored) { }
        }
        if (clearPendingDraft()) panelMessage = "Dictation inserted";
        updateUiState();
    }

    private void discardPendingDraft() {
        if (nativeRetryAvailable) {
            try { cancelRecording(activeSessionId); } catch (Throwable ignored) { }
        }
        joiner.finish();
        if (clearPendingDraft()) panelMessage = "Saved dictation discarded";
        renderStatus();
        updateUiState();
    }

    private void retryPendingInference() {
        if (!nativeRetryAvailable || pendingDraft == null) return;
        if (retryRecording(activeSessionId)) {
            nativeRetryAvailable = false;
            sessionTerminal = false;
            panel = PANEL_FINISHING;
            PendingDictationDraft retrying = pendingDraft.preservingDeliveryRisk(
                    PendingDictationDraft.PENDING, pendingDraft.text, nextPieceSequence);
            if (writePendingDraft(retrying)) pendingDraft = retrying;
            updateUiState();
        }
    }

    private float readSentencePauseSeconds() {
        File file = new File(getFilesDir(), "pause_sentence_seconds");
        if (!file.exists()) return PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
        try (java.io.BufferedReader reader =
                     new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = reader.readLine();
            return SentencePauseSetting.parse(line);
        } catch (Throwable t) {
            Log.w(TAG, "could not read the sentence pause setting", t);
        }
        return PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
    }

    private void selectTranscriptionIfEnabled() {
        if (sessionCommittedChars <= 0 || pendingSwitchBack || dictationSelectionStart < 0) return;
        if (!new File(getFilesDir(), "select_transcription").exists()) return;
        InputConnection ic = getCurrentInputConnection();
        if (!inputActive || ic == null || !automaticDeliveryAllowed) return;
        try {
            ic.setSelection(dictationSelectionStart, expectedSelectionEnd);
        } catch (Throwable t) {
            Log.w(TAG, "could not select the transcription", t);
        }
    }

    /**
     * Returns to the keyboard that handed over. {@code switchToPreviousInputMethod()}
     * arrived in API 28 while minSdk is 26, where it raises NoSuchMethodError; that
     * was latent in a button few people press and now runs after every dictation.
     */
    private boolean switchBackToPreviousKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                return switchToPreviousInputMethod();
            } catch (Throwable t) {
                Log.w(TAG, "switchToPreviousInputMethod failed", t);
                return false;
            }
        }
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            android.os.IBinder token = null;
            if (getWindow() != null && getWindow().getWindow() != null) {
                token = getWindow().getWindow().getAttributes().token;
            }
            if (imm != null && token != null) {
                return imm.switchToLastInputMethod(token);
            }
        } catch (Throwable t) {
            Log.w(TAG, "switchToLastInputMethod failed", t);
        }
        return false;
    }
    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }

    /** "Auto-start recording" is default ON in this build; the marker is the opt-out. */
    private boolean isAutoRecordEnabled() {
        return !new File(getFilesDir(), "no_auto_record").exists();
    }

    /**
     * "Return to the previous keyboard after dictation" is default ON; the marker
     * is the opt-out. A voice keyboard reached through another keyboard's mic key
     * is a mic button, not a keyboard.
     */
    private boolean isSwitchBackEnabled() {
        return !new File(getFilesDir(), "no_switch_back").exists();
    }

    /** The contextual Insert recovery action is default ON; the marker is the opt-out. */
    private boolean isInsertButtonEnabled() {
        return !new File(getFilesDir(), "no_insert_button").exists();
    }
}
