package com.alenrj.jarvis;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Listens for the wake word with the screen off, entirely on the phone (Vosk, offline, free).
 * Runs as a foreground service with a visible notification, as Android requires for background mic use.
 */
public class WakeService extends Service implements RecognitionListener {
    static final String ACTION_START = "com.alenrj.jarvis.WAKE_START";
    static final String ACTION_STOP = "com.alenrj.jarvis.WAKE_STOP";
    static final String ACTION_PAUSE = "com.alenrj.jarvis.WAKE_PAUSE";
    static final String ACTION_RESUME = "com.alenrj.jarvis.WAKE_RESUME";
    static final String KEY_WORD = "wake_word";
    private static final int NOTIF_ID = 7;
    private static final long AUTO_RESUME_MS = 60_000;

    static volatile boolean running = false;
    static volatile boolean listening = false;

    private static Model model;
    private SpeechService speech;
    private Recognizer recognizer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean paused = false;
    private boolean loading = false;
    private long lastTrigger = 0;
    private List<String> triggers = new ArrayList<>();
    private String word = "jarvis";

    private final Runnable autoResume = () -> { if (running && paused) { paused = false; listen(); } };

    static File modelDir(Context ctx) { return new File(ctx.getFilesDir(), "vosk-model"); }
    static boolean modelReady(Context ctx) { return new File(modelDir(ctx), "am").exists() || new File(modelDir(ctx), "conf").exists(); }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;
        switch (action) {
            case ACTION_STOP:
                shutdown();
                return START_NOT_STICKY;
            case ACTION_PAUSE:
                if (!running) { stopSelf(); return START_NOT_STICKY; }
                paused = true;
                stopListening();
                main.removeCallbacks(autoResume);
                main.postDelayed(autoResume, AUTO_RESUME_MS);
                return START_STICKY;
            case ACTION_RESUME:
                if (!running) { stopSelf(); return START_NOT_STICKY; }
                paused = false;
                main.removeCallbacks(autoResume);
                listen();
                return START_STICKY;
            default:
                if (!startInForeground()) { stopSelf(); return START_NOT_STICKY; }
                running = true;
                paused = false;
                word = getSharedPreferences(ReminderReceiver.PREFS, MODE_PRIVATE).getString(KEY_WORD, "jarvis").toLowerCase(Locale.ROOT).trim();
                triggers = buildTriggers(word);
                listen();
                return START_STICKY;
        }
    }

    private boolean startInForeground() {
        ReminderReceiver.ensureChannels(this);
        try {
            int type = Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Say “Hey " + cap(word) + "”"), type);
            return true;
        } catch (Exception e) {
            // Android refuses background mic services started while the app isn't visible (e.g. after a restart).
            return false;
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 3, new Intent(this, WakeService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, ReminderReceiver.CH_WAKE)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(cap(word) + " is listening")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPi)
            .addAction(0, "Turn off", stopPi)
            .build();
    }

    private void updateNotification(String text) {
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text)); } catch (SecurityException ignored) { }
    }

    private void listen() {
        if (!running || paused || speech != null || loading) return;
        if (!modelReady(this)) { updateNotification("Voice model missing. Open Jarvis to download it."); return; }
        loading = true;
        new Thread(() -> {
            try {
                if (model == null) model = new Model(modelDir(this).getAbsolutePath());
                main.post(() -> {
                    loading = false;
                    if (!running || paused || speech != null) return;
                    try {
                        recognizer = new Recognizer(model, 16000.0f);
                        speech = new SpeechService(recognizer, 16000.0f);
                        speech.startListening(this);
                        listening = true;
                        updateNotification("Say “Hey " + cap(word) + "”");
                    } catch (Exception e) {
                        listening = false;
                        updateNotification("Microphone busy. Will retry.");
                        main.postDelayed(this::listen, 5000);
                    }
                });
            } catch (Exception e) {
                main.post(() -> { loading = false; updateNotification("Couldn't load the voice model."); });
            }
        }, "jarvis-model").start();
    }

    private void stopListening() {
        listening = false;
        if (speech != null) {
            try { speech.stop(); speech.shutdown(); } catch (Exception ignored) { }
            speech = null;
        }
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void shutdown() {
        running = false;
        paused = false;
        main.removeCallbacksAndMessages(null);
        stopListening();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        stopListening();
        super.onDestroy();
    }

    // ---- Vosk callbacks ----
    @Override public void onPartialResult(String hypothesis) { check(hypothesis, "partial"); }
    @Override public void onResult(String hypothesis) { check(hypothesis, "text"); }
    @Override public void onFinalResult(String hypothesis) { check(hypothesis, "text"); }
    @Override public void onError(Exception e) { stopListening(); main.postDelayed(this::listen, 3000); }
    @Override public void onTimeout() { stopListening(); listen(); }

    private void check(String json, String key) {
        String text;
        try { text = new JSONObject(json).optString(key, ""); } catch (Exception e) { return; }
        if (text.isEmpty()) return;
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        for (String w : triggers) {
            if (t.contains(" " + w + " ")) { trigger(); return; }
        }
    }

    private void trigger() {
        long now = System.currentTimeMillis();
        if (now - lastTrigger < 3000) return;
        lastTrigger = now;
        paused = true;
        stopListening();
        main.removeCallbacks(autoResume);
        main.postDelayed(autoResume, AUTO_RESUME_MS);
        buzz();
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra(JarvisPlugin.EXTRA_WAKE, true);
        try { startActivity(open); } catch (Exception ignored) { }
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(40);
        } catch (Exception ignored) { }
    }

    /** The small offline model often hears a name as near-sounding words, so accept a few close variants. */
    static List<String> buildTriggers(String word) {
        List<String> t = new ArrayList<>();
        t.add(word);
        if (word.equals("jarvis")) {
            t.add("jarvis's"); t.add("jervis"); t.add("jarvi"); t.add("javis"); t.add("jarves");
            t.add("jar vis"); t.add("jar this"); t.add("hey jar");
        }
        return t;
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
