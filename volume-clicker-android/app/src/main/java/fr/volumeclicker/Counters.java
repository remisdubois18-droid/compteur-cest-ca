package fr.volumeclicker;
import android.content.Context;
import android.content.SharedPreferences;

final class Counters {
    static final String UP="up", DOWN="down", RUNNING="running";
    static SharedPreferences prefs(Context c) {return c.getSharedPreferences("volume_clicker_counters", Context.MODE_PRIVATE);}
    static boolean running(Context c) {return prefs(c).getBoolean(RUNNING,false);}
    static void setRunning(Context c, boolean value) {prefs(c).edit().putBoolean(RUNNING,value).apply();}
    static synchronized void increment(Context c, boolean up) {
        SharedPreferences p=prefs(c);
        String key=up?UP:DOWN;
        p.edit().putLong(key,p.getLong(key,0L)+1L).apply();
    }
    static synchronized void reset(Context c) {prefs(c).edit().putLong(UP,0L).putLong(DOWN,0L).apply();}
}
