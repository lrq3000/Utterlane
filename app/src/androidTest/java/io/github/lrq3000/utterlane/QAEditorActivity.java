package io.github.lrq3000.utterlane;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/** Separate test-APK caller. Java avoids Kotlin-runtime deduplication from the target APK. */
public class QAEditorActivity extends Activity {
    private TextView result;
    private SpeechRecognizer speech;
    private ServiceConnection probe;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        EditText first = editor("First editor", "QA first editor");
        EditText second = editor("Second editor", "QA second editor");
        result = new TextView(this);
        result.setText("Waiting");
        result.setContentDescription("QA result");
        layout.addView(first); layout.addView(second); layout.addView(result);
        // A fresh-launch marker prevents instrumentation from selecting an IME
        // against a previous activity's still-visible editor during CLEAR_TASK.
        TextView launchMarker = new TextView(this);
        launchMarker.setText("QA launch " + getIntent().getStringExtra("qa_run_id"));
        launchMarker.setContentDescription(launchMarker.getText());
        layout.addView(launchMarker);
        setContentView(layout);
        first.requestFocus();
        if (getIntent().getBooleanExtra("show_keyboard", false)) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            first.postDelayed(() -> getSystemService(InputMethodManager.class).showSoftInput(first, InputMethodManager.SHOW_FORCED), 300);
        } else getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (getIntent().getBooleanExtra("request_voice", false)) {
            // Register the caller window before starting the non-focusable overlay.
            first.postDelayed(() -> startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .setComponent(new ComponentName("io.github.lrq3000.utterlane", "io.github.lrq3000.utterlane.service.VoiceInputActivity")), 77), 500);
        }
        if (getIntent().getBooleanExtra("request_speech_api", false)) startSpeechApi(layout, first);
    }

    private EditText editor(String hint, String description) {
        EditText view = new EditText(this);
        view.setId(View.generateViewId());
        view.setHint(hint); view.setContentDescription(description);
        return view;
    }

    private void startSpeechApi(LinearLayout layout, View anchor) {
        ComponentName component = new ComponentName("io.github.lrq3000.utterlane", "io.github.lrq3000.utterlane.service.SpeechRecognitionService");
        try {
            android.content.pm.ServiceInfo info = getPackageManager().getServiceInfo(component, 0);
            Log.i("QAEditor", "Speech service=" + info.name + "; enabled=" + info.enabled + "; exported=" + info.exported
                + "; permission=" + info.permission + "; callerRecordPermission=" + checkSelfPermission(android.Manifest.permission.RECORD_AUDIO));
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            result.setText("Error: service missing"); return;
        }
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try { Log.i("QAEditor", "Direct speech binding connected: " + binder.getInterfaceDescriptor()); }
                catch (android.os.RemoteException error) { Log.e("QAEditor", "Probe disconnected", error); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        boolean bound = bindService(new Intent("android.speech.RecognitionService").setComponent(component), connection, BIND_AUTO_CREATE);
        Log.i("QAEditor", "Direct speech bind returned " + bound);
        if (bound) probe = connection; else result.setText("Error: direct speech binding failed");
        speech = SpeechRecognizer.createSpeechRecognizer(this, component);
        speech.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { result.setText("Ready"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float value) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int code) { result.setText("Error: " + code); }
            @Override public void onResults(Bundle results) { result.setText("Final: " + text(results)); }
            @Override public void onPartialResults(Bundle results) { result.setText("Partial: " + text(results)); }
            @Override public void onEvent(int type, Bundle params) {}
        });
        Button stop = new Button(this);
        stop.setText("Stop recognition"); stop.setContentDescription("QA stop recognition");
        stop.setOnClickListener(view -> speech.stopListening()); layout.addView(stop);
        anchor.postDelayed(() -> speech.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)), 500);
    }

    private String text(Bundle data) {
        ArrayList<String> values = data == null ? null : data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        ArrayList<String> values = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        result.setText("Result " + code + ": " + (values == null || values.isEmpty() ? "" : values.get(0)));
    }
    @Override public void onDestroy() {
        if (speech != null) speech.destroy();
        if (probe != null) unbindService(probe);
        super.onDestroy();
    }
}
