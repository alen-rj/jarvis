package com.alenrj.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.telephony.SmsManager;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Native bridge for Jarvis: speech in/out, wake word, phone actions. Everything here is free and on-device. */
@CapacitorPlugin(
    name = "Jarvis",
    permissions = {
        @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO }),
        @Permission(alias = "contacts", strings = { Manifest.permission.READ_CONTACTS }),
        @Permission(alias = "calendar", strings = { Manifest.permission.READ_CALENDAR }),
        @Permission(alias = "phone", strings = { Manifest.permission.CALL_PHONE }),
        @Permission(alias = "sms", strings = { Manifest.permission.SEND_SMS }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class JarvisPlugin extends Plugin {
    static final String EXTRA_WAKE = "jarvis_wake";
    static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String SMS_SENT = "com.alenrj.jarvis.SMS_SENT";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private PluginCall listenCall;
    private long lastRms = 0;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Map<String, PluginCall> speakCalls = new HashMap<>();
    private volatile boolean modelDownloading = false;

    @Override
    public void load() {
        ReminderReceiver.ensureChannels(getContext());
        tts = new TextToSpeech(getContext(), status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { notifyListeners("ttsStart", new JSObject()); }
                @Override public void onDone(String id) { finishSpeak(id); }
                @Override public void onError(String id) { finishSpeak(id); }
                @Override public void onStop(String id, boolean interrupted) { finishSpeak(id); }
                @Override public void onRangeStart(String id, int start, int end, int frame) {
                    JSObject o = new JSObject(); o.put("start", start); o.put("end", end);
                    notifyListeners("ttsRange", o);
                }
            });
        });
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_WAKE, false)) {
            intent.removeExtra(EXTRA_WAKE);
            notifyListeners("wake", new JSObject(), true);
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
        super.handleOnDestroy();
    }

    // ================= permissions =================

    @PluginMethod
    public void requestPerms(PluginCall call) {
        JSArray arr = call.getArray("aliases", new JSArray());
        List<String> wanted = new ArrayList<>();
        try {
            for (Object o : arr.toList()) {
                String a = String.valueOf(o);
                if (a.equals("notifications") && Build.VERSION.SDK_INT < 33) continue;
                if (getPermissionState(a) != PermissionState.GRANTED) wanted.add(a);
            }
        } catch (Exception e) { call.reject("bad aliases"); return; }
        if (wanted.isEmpty()) { call.resolve(states()); return; }
        requestPermissionForAliases(wanted.toArray(new String[0]), call, "permsCallback");
    }

    @PermissionCallback
    private void permsCallback(PluginCall call) { call.resolve(states()); }

    @PluginMethod
    public void checkPerms(PluginCall call) { call.resolve(states()); }

    private JSObject states() {
        JSObject o = new JSObject();
        for (String a : new String[] { "microphone", "contacts", "calendar", "phone", "sms", "notifications" }) {
            if (a.equals("notifications") && Build.VERSION.SDK_INT < 33) { o.put(a, "granted"); continue; }
            PermissionState s = getPermissionState(a);
            o.put(a, s == null ? "prompt" : s.toString());
        }
        o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName()));
        o.put("wakeModel", WakeService.modelReady(getContext()));
        o.put("wakeRunning", WakeService.running);
        return o;
    }

    private static int intOf(PluginCall call, String key, int def) {
        Object v = call.getData().opt(key);
        return v instanceof Number ? (int) Math.round(((Number) v).doubleValue()) : def;
    }

    private boolean granted(String perm) {
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ================= speech to text =================

    @PluginMethod
    public void listen(PluginCall call) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { call.reject("microphone permission denied", "NO_MIC"); return; }
        final String lang = call.getString("lang", Locale.getDefault().toLanguageTag());
        main.post(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) { call.reject("No speech recognition service on this phone. Install or update the Google app.", "NO_SR"); return; }
            if (listenCall != null) { JSObject r = new JSObject(); r.put("text", ""); r.put("error", "replaced"); listenCall.resolve(r); }
            listenCall = call;
            if (recognizer != null) recognizer.destroy();
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { notifyListeners("listenStart", new JSObject()); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rms) {
                    long now = System.currentTimeMillis();
                    if (now - lastRms < 70) return;
                    lastRms = now;
                    JSObject o = new JSObject(); o.put("rms", rms); notifyListeners("rms", o);
                }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { notifyListeners("listenEnd", new JSObject()); }
                @Override public void onError(int error) { finishListen("", errorName(error)); }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> m = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    finishListen(m != null && !m.isEmpty() ? m.get(0) : "", null);
                }
                @Override public void onPartialResults(Bundle partial) {
                    ArrayList<String> m = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (m != null && !m.isEmpty()) { JSObject o = new JSObject(); o.put("text", m.get(0)); notifyListeners("partial", o); }
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
            try { recognizer.startListening(i); } catch (Exception e) { finishListen("", "start_failed"); }
        });
    }

    private void finishListen(String text, String error) {
        PluginCall c = listenCall;
        listenCall = null;
        if (c == null) return;
        JSObject r = new JSObject();
        r.put("text", text);
        if (error != null) r.put("error", error);
        c.resolve(r);
    }

    @PluginMethod
    public void stopListening(PluginCall call) {
        main.post(() -> {
            if (recognizer != null) recognizer.cancel();
            finishListen("", "cancelled");
            call.resolve();
        });
    }

    private static String errorName(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "no-match";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no-speech";
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network";
            case SpeechRecognizer.ERROR_AUDIO: return "audio";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "not-allowed";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "busy";
            case SpeechRecognizer.ERROR_CLIENT: return "client";
            default: return "error-" + e;
        }
    }

    // ================= text to speech =================

    @PluginMethod
    public void speak(PluginCall call) {
        String text = call.getString("text", "");
        if (!ttsReady || tts == null) { call.reject("Text to speech isn't ready. Install a voice in Settings > Text-to-speech.", "NO_TTS"); return; }
        if (text == null || text.isEmpty()) { call.resolve(); return; }
        String lang = call.getString("lang", null);
        String voice = call.getString("voice", null);
        boolean voiceSet = false;
        if (voice != null && !voice.isEmpty()) {
            try { for (Voice v : tts.getVoices()) if (v.getName().equals(voice)) { tts.setVoice(v); voiceSet = true; break; } } catch (Exception ignored) { }
        }
        if (!voiceSet && lang != null) tts.setLanguage(Locale.forLanguageTag(lang));
        tts.setSpeechRate(call.getFloat("rate", 1.0f));
        tts.setPitch(call.getFloat("pitch", 1.0f));
        String id = UUID.randomUUID().toString();
        synchronized (speakCalls) { speakCalls.put(id, call); }
        Bundle params = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
    }

    private void finishSpeak(String id) {
        PluginCall c;
        synchronized (speakCalls) { c = speakCalls.remove(id); }
        if (c != null) c.resolve();
        notifyListeners("ttsEnd", new JSObject());
    }

    @PluginMethod
    public void stopSpeaking(PluginCall call) {
        if (tts != null) tts.stop();
        List<PluginCall> pending;
        synchronized (speakCalls) { pending = new ArrayList<>(speakCalls.values()); speakCalls.clear(); }
        for (PluginCall c : pending) c.resolve();
        call.resolve();
    }

    @PluginMethod
    public void getVoices(PluginCall call) {
        JSArray out = new JSArray();
        if (ttsReady && tts != null) {
            try {
                for (Voice v : tts.getVoices()) {
                    String l = v.getLocale().toLanguageTag();
                    if (!(l.startsWith("en") || l.startsWith("hi") || l.startsWith("ta") || l.startsWith("ml"))) continue;
                    if (v.isNetworkConnectionRequired()) continue;
                    JSObject o = new JSObject(); o.put("name", v.getName()); o.put("lang", l); out.put(o);
                }
            } catch (Exception ignored) { }
        }
        JSObject r = new JSObject(); r.put("voices", out); call.resolve(r);
    }

    // ================= wake word =================

    @PluginMethod
    public void prepareWakeModel(PluginCall call) {
        if (WakeService.modelReady(getContext())) { JSObject r = new JSObject(); r.put("ready", true); call.resolve(r); return; }
        if (modelDownloading) { call.reject("Already downloading", "BUSY"); return; }
        modelDownloading = true;
        new Thread(() -> {
            File dir = getContext().getFilesDir();
            File zip = new File(dir, "vosk.zip");
            File tmp = new File(dir, "vosk-tmp");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
                long total = c.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(zip)) {
                    byte[] buf = new byte[65536]; long got = 0; int n; int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != lastPct && pct % 5 == 0) { lastPct = pct; JSObject p = new JSObject(); p.put("pct", pct); notifyListeners("modelProgress", p); }
                    }
                }
                deleteRec(tmp); tmp.mkdirs();
                String tmpCanon = tmp.getCanonicalPath();
                try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
                    ZipEntry e; byte[] buf = new byte[65536];
                    while ((e = zin.getNextEntry()) != null) {
                        File f = new File(tmp, e.getName());
                        if (!f.getCanonicalPath().startsWith(tmpCanon + File.separator)) continue; // zip-slip guard
                        if (e.isDirectory()) { f.mkdirs(); continue; }
                        File parent = f.getParentFile(); if (parent != null) parent.mkdirs();
                        try (OutputStream out = new FileOutputStream(f)) { int n; while ((n = zin.read(buf)) > 0) out.write(buf, 0, n); }
                    }
                }
                File root = tmp;
                File[] kids = tmp.listFiles();
                if (kids != null && kids.length == 1 && kids[0].isDirectory()) root = kids[0];
                File target = WakeService.modelDir(getContext());
                deleteRec(target);
                if (!root.renameTo(target)) throw new Exception("Couldn't install the voice model");
                deleteRec(tmp); zip.delete();
                modelDownloading = false;
                JSObject r = new JSObject(); r.put("ready", true); call.resolve(r);
            } catch (Exception ex) {
                modelDownloading = false;
                zip.delete(); deleteRec(tmp);
                call.reject("Voice model download failed: " + ex.getMessage(), "DOWNLOAD");
            }
        }, "jarvis-model-dl").start();
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    @PluginMethod
    public void startWake(PluginCall call) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { call.reject("microphone permission denied", "NO_MIC"); return; }
        if (!WakeService.modelReady(getContext())) { call.reject("Voice model not downloaded", "NO_MODEL"); return; }
        String word = call.getString("word", "jarvis");
        getContext().getSharedPreferences(ReminderReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putString(WakeService.KEY_WORD, word == null ? "jarvis" : word.toLowerCase(Locale.ROOT).trim()).apply();
        Intent i = new Intent(getContext(), WakeService.class).setAction(WakeService.ACTION_START);
        try { ContextCompat.startForegroundService(getContext(), i); call.resolve(); }
        catch (Exception e) { call.reject("Couldn't start background listening: " + e.getMessage(), "FGS"); }
    }

    @PluginMethod
    public void stopWake(PluginCall call) { sendWake(WakeService.ACTION_STOP); call.resolve(); }

    @PluginMethod
    public void pauseWake(PluginCall call) { if (WakeService.running) sendWake(WakeService.ACTION_PAUSE); call.resolve(); }

    @PluginMethod
    public void resumeWake(PluginCall call) { if (WakeService.running) sendWake(WakeService.ACTION_RESUME); call.resolve(); }

    private void sendWake(String action) {
        try { getContext().startService(new Intent(getContext(), WakeService.class).setAction(action)); } catch (Exception ignored) { }
    }

    @PluginMethod
    public void wakeStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("running", WakeService.running);
        r.put("listening", WakeService.listening);
        r.put("modelReady", WakeService.modelReady(getContext()));
        call.resolve(r);
    }

    // ================= phone actions =================

    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        Intent i;
        try {
            if (url.startsWith("intent:")) i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            else i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            JSObject r = new JSObject(); r.put("opened", true); call.resolve(r);
        } catch (ActivityNotFoundException e) {
            try {
                if (url.startsWith("intent:")) {
                    Intent parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    String fb = parsed.getStringExtra("browser_fallback_url");
                    if (fb != null) {
                        getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        JSObject r = new JSObject(); r.put("opened", true); r.put("fallback", true); call.resolve(r); return;
                    }
                }
            } catch (Exception ignored) { }
            JSObject r = new JSObject(); r.put("opened", false); call.resolve(r);
        } catch (Exception e) {
            call.reject("Couldn't open that: " + e.getMessage());
        }
    }

    @PluginMethod
    public void openApp(PluginCall call) {
        String name = call.getString("name", "").toLowerCase(Locale.ROOT).trim();
        PackageManager pm = getContext().getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        String bestPkg = null, bestLabel = null; int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).trim();
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(name)) score = 100;
            else if (label.startsWith(name)) score = 70;
            else if (name.length() >= 3 && label.contains(name)) score = 50;
            else if (name.length() >= 4 && name.contains(label) && label.length() >= 3) score = 40;
            else if (name.length() >= 4 && pkg.contains(name.replace(" ", ""))) score = 30;
            if (score > bestScore) { bestScore = score; bestPkg = ri.activityInfo.packageName; bestLabel = String.valueOf(ri.loadLabel(pm)); }
        }
        JSObject r = new JSObject();
        if (bestPkg == null) { r.put("opened", false); call.resolve(r); return; }
        Intent i = pm.getLaunchIntentForPackage(bestPkg);
        if (i == null) { r.put("opened", false); call.resolve(r); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); r.put("opened", true); r.put("label", bestLabel); }
        catch (Exception e) { r.put("opened", false); }
        call.resolve(r);
    }

    @PluginMethod
    public void setAlarm(PluginCall call) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_HOUR, intOf(call, "hour", 7));
        i.putExtra(AlarmClock.EXTRA_MINUTES, intOf(call, "minute", 0));
        i.putExtra(AlarmClock.EXTRA_MESSAGE, call.getString("label", "Jarvis"));
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        startClock(call, i);
    }

    @PluginMethod
    public void setTimer(PluginCall call) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
        i.putExtra(AlarmClock.EXTRA_LENGTH, intOf(call, "seconds", 60));
        i.putExtra(AlarmClock.EXTRA_MESSAGE, call.getString("label", "Jarvis timer"));
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        startClock(call, i);
    }

    private void startClock(PluginCall call, Intent i) {
        Activity a = getActivity();
        JSObject r = new JSObject();
        try {
            if (a != null) a.startActivity(i); else { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); }
            r.put("ok", true);
        } catch (ActivityNotFoundException e) { r.put("ok", false); r.put("error", "No clock app handles this"); }
        catch (Exception e) { r.put("ok", false); r.put("error", e.getMessage()); }
        call.resolve(r);
    }

    @PluginMethod
    public void findContacts(PluginCall call) {
        if (!granted(Manifest.permission.READ_CONTACTS)) { call.reject("contacts permission denied", "NO_PERM"); return; }
        String q = call.getString("name", "").trim();
        JSArray out = new JSArray();
        Set<String> seen = new HashSet<>();
        ContentResolver cr = getContext().getContentResolver();
        String[] proj = { ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER };
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[] { "%" + q + "%" },
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            while (c != null && c.moveToNext() && out.length() < 6) {
                String n = c.getString(0), num = c.getString(1);
                if (num == null) continue;
                String key = n + "|" + num.replaceAll("[^0-9+]", "");
                if (!seen.add(key)) continue;
                JSObject o = new JSObject(); o.put("name", n); o.put("number", num); out.put(o);
            }
        } catch (Exception e) { call.reject("Couldn't read contacts"); return; }
        JSObject r = new JSObject(); r.put("contacts", out); call.resolve(r);
    }

    @PluginMethod
    public void todayEvents(PluginCall call) {
        if (!granted(Manifest.permission.READ_CALENDAR)) { call.reject("calendar permission denied", "NO_PERM"); return; }
        int days = intOf(call, "days", 1);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long begin = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_YEAR, Math.max(1, days));
        long end = cal.getTimeInMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, begin); ContentUris.appendId(b, end);
        String[] proj = { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION };
        JSArray out = new JSArray();
        try (Cursor c = getContext().getContentResolver().query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext() && out.length() < 25) {
                JSObject o = new JSObject();
                o.put("title", c.getString(0)); o.put("begin", c.getLong(1)); o.put("end", c.getLong(2));
                o.put("allDay", c.getInt(3) == 1); o.put("location", c.getString(4));
                out.put(o);
            }
        } catch (Exception e) { call.reject("Couldn't read the calendar"); return; }
        JSObject r = new JSObject(); r.put("events", out); call.resolve(r);
    }

    @PluginMethod
    public void callNumber(PluginCall call) {
        String number = call.getString("number", "");
        boolean direct = granted(Manifest.permission.CALL_PHONE);
        Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        JSObject r = new JSObject();
        try { getContext().startActivity(i); r.put("calling", direct); r.put("dialer", !direct); }
        catch (Exception e) { r.put("calling", false); r.put("error", e.getMessage()); }
        call.resolve(r);
    }

    @PluginMethod
    public void sendSms(PluginCall call) {
        if (!granted(Manifest.permission.SEND_SMS)) { call.reject("sms permission denied", "NO_PERM"); return; }
        String number = call.getString("number", "");
        String body = call.getString("body", "");
        Context ctx = getContext();
        final boolean[] done = { false };
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (done[0]) return;
                done[0] = true;
                try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
                JSObject r = new JSObject();
                r.put("sent", getResultCode() == Activity.RESULT_OK);
                if (getResultCode() != Activity.RESULT_OK) r.put("error", "carrier code " + getResultCode());
                call.resolve(r);
            }
        };
        ContextCompat.registerReceiver(ctx, rx, new IntentFilter(SMS_SENT), ContextCompat.RECEIVER_NOT_EXPORTED);
        PendingIntent sentPi = PendingIntent.getBroadcast(ctx, (int) (System.currentTimeMillis() & 0xffff),
            new Intent(SMS_SENT).setPackage(ctx.getPackageName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
        try {
            SmsManager sms = Build.VERSION.SDK_INT >= 31 ? ctx.getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(body);
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sent = new ArrayList<>();
                for (int k = 0; k < parts.size(); k++) sent.add(k == parts.size() - 1 ? sentPi : null);
                sms.sendMultipartTextMessage(number, null, parts, sent, null);
            } else {
                sms.sendTextMessage(number, null, body, sentPi, null);
            }
        } catch (Exception e) {
            done[0] = true;
            try { ctx.unregisterReceiver(rx); } catch (Exception ignored) { }
            call.reject("Couldn't send: " + e.getMessage());
            return;
        }
        main.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            try { ctx.unregisterReceiver(rx); } catch (Exception ignored) { }
            JSObject r = new JSObject(); r.put("sent", false); r.put("pending", true); call.resolve(r);
        }, 20000);
    }

    @PluginMethod
    public void scheduleReminder(PluginCall call) {
        String id = call.getString("id");
        Long at = call.getData().opt("at") instanceof Number ? ((Number) call.getData().opt("at")).longValue() : null;
        if (id == null || at == null) { call.reject("id and at required"); return; }
        ReminderReceiver.schedule(getContext(), id, at, call.getString("title", "Reminder"), call.getString("body", ""), true);
        call.resolve();
    }

    @PluginMethod
    public void cancelReminder(PluginCall call) {
        String id = call.getString("id");
        if (id != null) ReminderReceiver.cancel(getContext(), id);
        call.resolve();
    }

    @PluginMethod
    public void saveImage(PluginCall call) {
        String b64 = call.getString("base64", "");
        String name = call.getString("name", "jarvis-" + System.currentTimeMillis() + ".jpg");
        if (Build.VERSION.SDK_INT < 29) { call.reject("Saving needs Android 10 or newer"); return; }
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Jarvis");
            Uri uri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("no media store");
            try (OutputStream out = getContext().getContentResolver().openOutputStream(uri)) { out.write(data); }
            JSObject r = new JSObject(); r.put("saved", true); call.resolve(r);
        } catch (Exception e) { call.reject("Couldn't save: " + e.getMessage()); }
    }

    // ================= device state & settings shortcuts =================

    @PluginMethod
    public void isLocked(PluginCall call) {
        KeyguardManager km = (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        JSObject r = new JSObject(); r.put("locked", km != null && km.isKeyguardLocked()); call.resolve(r);
    }

    @PluginMethod
    public void requestUnlock(PluginCall call) {
        Activity a = getActivity();
        KeyguardManager km = (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (a == null || km == null || Build.VERSION.SDK_INT < 26) { call.resolve(); return; }
        main.post(() -> km.requestDismissKeyguard(a, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { JSObject r = new JSObject(); r.put("unlocked", true); call.resolve(r); }
            @Override public void onDismissCancelled() { JSObject r = new JSObject(); r.put("unlocked", false); call.resolve(r); }
            @Override public void onDismissError() { JSObject r = new JSObject(); r.put("unlocked", false); call.resolve(r); }
        }));
    }

    @PluginMethod
    public void openSetting(PluginCall call) {
        String which = call.getString("which", "app");
        String pkg = getContext().getPackageName();
        Intent i;
        switch (which) {
            case "overlay": i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)); break;
            case "battery": i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)); break;
            case "assistant": i = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS); break;
            case "exactAlarm":
                i = Build.VERSION.SDK_INT >= 31 ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg))
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                break;
            default: i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); }
        catch (Exception e) {
            try {
                Intent fb = which.equals("assistant") ? new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                getContext().startActivity(fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
        call.resolve();
    }
}
