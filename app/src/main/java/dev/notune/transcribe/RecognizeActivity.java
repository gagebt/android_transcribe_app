package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.pm.PackageManager;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class RecognizeActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView status;
    private Button recoveryCopy;
    private String recoveryText;
    private boolean isRecording = false;
    private MicLevelView micLevel;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    private long sessionId = 0;
    private long nextPieceSequence = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recognize_activity);

        // Keep the screen awake for the lifetime of this recording screen so it
        // never sleeps mid-capture and cuts the recording short.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        micLevel = findViewById(R.id.mic_level);
        status = findViewById(R.id.txt_status);
        recoveryCopy = findViewById(R.id.btn_copy_recovery);
        recoveryCopy.setOnClickListener(v -> copyRecoveryText());

        findViewById(R.id.btn_close).setOnClickListener(v -> {
            // discard current recording
            if (isRecording) {
                isRecording = false;
                cancelRecording(sessionId);
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Tap anywhere (or on mic) to stop
        findViewById(R.id.root).setOnClickListener(v -> finishRecording());

        // Permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone permission required.\nGrant it in the main app.");
            return;
        }

        initNative(this);
        sessionId = Math.max(1, android.os.SystemClock.elapsedRealtimeNanos());
        isRecording = true;
        status.setText("Listening... (Tap to stop)");
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        if (!startRecording(sessionId, isAutoStopEnabled())) {
            isRecording = false;
            status.setText("Could not start recording");
        }
    }

    /** Stop capture and transcribe — used by both tap-to-stop and auto-stop. */
    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        status.setText("Processing...");
        stopRecording(sessionId);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Called from Rust (monitor thread) when trailing silence is detected.
    public void onAutoStop(long callbackSessionId) {
        if (callbackSessionId == sessionId) runOnUiThread(this::finishRecording);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The popup is no longer visible (user switched apps or went home).
        // It runs in its own task (singleTask), so it would otherwise keep
        // recording invisibly in the background and never reappear. Discard
        // and close so the next mic tap starts fresh. (Background recording
        // is a keyboard-only feature; a popup must not record unseen.)
        if (isRecording && !isFinishing()) {
            isRecording = false;
            try { cancelRecording(sessionId); } catch (Throwable t) { /* ignore */ }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
    }

    // Engine loading status only. Session callbacks below own recording state.
    public void onStatusUpdate(String s) {
        runOnUiThread(() -> {
            final String shown;
            if ("Ready".equals(s)) {
                // The model-ready status can arrive after recording started.
                shown = isRecording ? "Listening... (Tap to stop)" : "Ready";
            } else if ("Listening...".equals(s)) {
                shown = "Listening... (Tap to stop)";
            } else {
                shown = s;
            }
            status.setText(shown);
        });
    }

    public void onDictationStatus(long callbackSessionId, String s) {
        if (callbackSessionId != sessionId) return;
        runOnUiThread(() -> status.setText(s));
    }

    // Called from Rust with 0..1
    public void onDictationLevel(long callbackSessionId, float level) {
        if (callbackSessionId == sessionId) runOnUiThread(() -> micLevel.setLevel(level));
    }

    /**
     * Called from Rust once per finished piece. The popup returns one result to the
     * app that asked for it, so it has nothing to do with a piece: the whole session
     * text still arrives in {@link #onDictationComplete}. Leaving this empty is what
     * keeps this screen behaving exactly as it did before streaming, only faster.
     */
    public boolean onTranscriptPiece(long callbackSessionId, long pieceSequence,
                                     String text, float pauseBeforeSeconds) {
        if (callbackSessionId != sessionId) return false;
        if (pieceSequence < nextPieceSequence) return true;
        // Empty native decodes intentionally produce no callback, so a later
        // nonempty piece can have a higher sequence without any text being lost.
        nextPieceSequence = pieceSequence + 1;
        return true;
    }

    public void onDictationComplete(long callbackSessionId, int outcome,
                                    String text, String error) {
        if (callbackSessionId != sessionId) return;
        runOnUiThread(() -> {
            isRecording = false;
            if (outcome != 0) {
                if (text != null && !text.trim().isEmpty()) {
                    recoveryText = text;
                    recoveryCopy.setVisibility(android.view.View.VISIBLE);
                    String reason = error == null || error.isEmpty()
                            ? "Dictation needs review" : error;
                    status.setText(reason + "\n\n" + text
                            + "\n\nCopy the text or close to cancel.");
                    return;
                }
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }
            if (text == null || text.trim().isEmpty()) {
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }

            ArrayList<String> results = new ArrayList<>();
            results.add(text);

            Intent data = new Intent();
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);

            setResult(Activity.RESULT_OK, data);
            finish();
        });
    }

    private void copyRecoveryText() {
        if (recoveryText == null) return;
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("clipboard unavailable");
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "dictation", recoveryText));
            status.setText("Dictation copied. Close to cancel.\n\n" + recoveryText);
        } catch (Throwable t) {
            status.setText("Could not copy dictation. Close to cancel.\n\n" + recoveryText);
            Log.w(TAG, "clipboard write failed", t);
        }
    }

    private boolean isPauseAudioEnabled() {
        return new java.io.File(getFilesDir(), "pause_audio").exists();
    }

    /** Opt-in via the "Auto-stop after silence" setting (default off). */
    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative();
    private native boolean startRecording(long sessionId, boolean autoStop);
    private native boolean stopRecording(long sessionId);
    private native boolean cancelRecording(long sessionId);
}
