package dev.dedsec.micbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Device-wide handler for RecognizerIntent.ACTION_RECOGNIZE_SPEECH.
 * This firmware ships no such activity, which is why SmartTube's own
 * microphone button fails with "Could not find voice search activity".
 */
public class RecognizeActivity extends Activity implements RecognitionListener {

    private static final String TAG = "MicBridge";

    private SpeechRecognizer sr;
    private TextView label;
    private final Handler h = new Handler();
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.BLACK);

        label = new TextView(this);
        label.setText("Listening…");
        label.setTextSize(34);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        box.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(box);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        try {
            if (sr != null) sr.destroy();
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                L.f(this, "RecognizeActivity: no recognizer");
                finishCancelled();
                return;
            }
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(this);

            Intent in = getIntent();
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    in != null && in.hasExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
                            ? in.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
                            : RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    in != null && in.hasExtra(RecognizerIntent.EXTRA_LANGUAGE)
                            ? in.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
                            : "en-IN");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra("isGB", true);
            sr.startListening(i);
            L.f(this, "RecognizeActivity started listening");

            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!done) {
                        L.f(RecognizeActivity.this, "RecognizeActivity timeout");
                        finishCancelled();
                    }
                }
            }, 15000);
        } catch (Throwable t) {
            L.f(this, "RecognizeActivity failed: " + t);
            finishCancelled();
        }
    }

    private void finishCancelled() {
        if (done) return;
        done = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    private void finishWith(String text) {
        if (done) return;
        done = true;
        Intent out = new Intent();
        ArrayList<String> list = new ArrayList<>();
        list.add(text);
        out.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, list);
        setResult(RESULT_OK, out);
        finish();
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> list =
                results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        L.f(this, "RecognizeActivity results: " + list);
        if (list != null && !list.isEmpty() && list.get(0) != null
                && !list.get(0).trim().isEmpty()) {
            finishWith(list.get(0).trim());
        } else {
            finishCancelled();
        }
    }

    @Override
    public void onError(int error) {
        L.f(this, "RecognizeActivity error " + error);
        finishCancelled();
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        if (label != null) label.setText("Listening…");
    }

    @Override public void onBeginningOfSpeech() { if (label != null) label.setText("…"); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { if (label != null) label.setText("…"); }
    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override
    protected void onDestroy() {
        try { if (sr != null) { sr.cancel(); sr.destroy(); } } catch (Throwable ignored) {}
        sr = null;
        super.onDestroy();
    }
}
