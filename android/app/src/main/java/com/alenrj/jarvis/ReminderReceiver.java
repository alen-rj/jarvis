package com.alenrj.jarvis;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fires scheduled reminders as notifications, even when Jarvis is closed. Also stores them so they survive a reboot. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CH_REMINDERS = "jarvis_reminders";
    static final String CH_WAKE = "jarvis_wake";
    static final String PREFS = "jarvis_native";
    static final String KEY_REMINDERS = "reminders";
    static final String ACTION_FIRE = "com.alenrj.jarvis.REMINDER";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra("id");
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        if (id != null) remove(ctx, id);
        ensureChannels(ctx);
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title != null ? title : "Jarvis")
            .setContentText(body != null ? body : "")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body != null ? body : ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(ctx).notify(id != null ? id.hashCode() : (int) System.currentTimeMillis(), b.build());
        } catch (SecurityException ignored) { }
    }

    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel r = new NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("Reminders you ask Jarvis to set");
        r.enableVibration(true);
        nm.createNotificationChannel(r);
        NotificationChannel w = new NotificationChannel(CH_WAKE, "Wake word", NotificationManager.IMPORTANCE_LOW);
        w.setDescription("Shown while Jarvis listens for its name");
        w.setShowBadge(false);
        nm.createNotificationChannel(w);
    }

    // ---- scheduling (shared with the plugin and the boot receiver) ----

    static void schedule(Context ctx, String id, long at, String title, String body, boolean persist) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx, id, title, body);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        if (persist) store(ctx, id, at, title, body);
    }

    static void cancel(Context ctx, String id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(ctx, id, null, null));
        remove(ctx, id);
    }

    private static PendingIntent pending(Context ctx, String id, String title, String body) {
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction(ACTION_FIRE);
        i.putExtra("id", id);
        if (title != null) i.putExtra("title", title);
        if (body != null) i.putExtra("body", body);
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static synchronized void store(Context ctx, String id, long at, String title, String body) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY_REMINDERS, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!id.equals(o.optString("id"))) out.put(o);
            }
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("at", at); o.put("title", title); o.put("body", body);
            out.put(o);
            p.edit().putString(KEY_REMINDERS, out.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static synchronized void remove(Context ctx, String id) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY_REMINDERS, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!id.equals(o.optString("id"))) out.put(o);
            }
            p.edit().putString(KEY_REMINDERS, out.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void rescheduleAll(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY_REMINDERS, "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long at = o.optLong("at");
                // Missed while the phone was off: fire a minute after boot so it isn't lost.
                schedule(ctx, o.optString("id"), Math.max(at, now + 60_000), o.optString("title"), o.optString("body"), false);
            }
        } catch (Exception ignored) { }
    }
}
