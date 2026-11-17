package fr.volumeclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

final class Counters {
    static final String UP="up", DOWN="down", RUNNING="running";
    private static long lastUp=-1L, lastDown=-1L;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("volume_clicker_counters", Context.MODE_PRIVATE); }
    static boolean running(Context c) { return prefs(c).getBoolean(RUNNING,false); }
    static void setRunning(Context c,boolean value) { prefs(c).edit().putBoolean(RUNNING,value).apply(); }
    static synchronized void increment(Context c,boolean up) {
        if (!running(c)) return;
        long now=SystemClock.elapsedRealtime();
        long previous=up?lastUp:lastDown;
        // Ignore two different dispatch paths for the same physical keypress.
        // Very rapid double taps (< 55ms) may also be collapsed.
        if(previous>=0 && now-previous<55) return;
        if(up) lastUp=now; else lastDown=now;
        SharedPreferences p=prefs(c);
        String key=up?UP:DOWN;
        p.edit().putLong(key,p.getLong(key,0L)+1L).apply();
    }
    static synchronized void reset(Context c) {
        prefs(c).edit().putLong(UP,0L).putLong(DOWN,0L).apply();
        lastUp=-1L;lastDown=-1L;
    }
}
