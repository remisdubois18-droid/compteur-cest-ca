package fr.volumeclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Le compteur, enregistré sur le téléphone (il survit à la fermeture de l'application). */
final class Counters {
    static final String TOTAL = "total", RUNNING = "running";
    private static long dernierAppui = -1L;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("volume_clicker_counters", Context.MODE_PRIVATE);
    }

    static boolean running(Context c) { return prefs(c).getBoolean(RUNNING, false); }

    static void setRunning(Context c, boolean value) { prefs(c).edit().putBoolean(RUNNING, value).apply(); }

    static long total(Context c) { return prefs(c).getLong(TOTAL, 0L); }

    /** +1 à chaque appui sur Volume + ou Volume −. Renvoie vrai si l'appui a été compté. */
    static synchronized boolean increment(Context c) {
        if (!running(c)) return false;
        long maintenant = SystemClock.elapsedRealtime();
        // Un même appui peut arriver deux fois par deux chemins différents : on l'ignore s'il suit de moins de 60 ms
        if (dernierAppui >= 0 && maintenant - dernierAppui < 60) return false;
        dernierAppui = maintenant;
        SharedPreferences p = prefs(c);
        p.edit().putLong(TOTAL, p.getLong(TOTAL, 0L) + 1L).commit();
        return true;
    }

    /** +1 sans aucun filtre (chaque cran de volume reçu correspond à un appui). */
    static synchronized boolean ajouter(Context c) {
        if (!running(c)) return false;
        SharedPreferences p = prefs(c);
        p.edit().putLong(TOTAL, p.getLong(TOTAL, 0L) + 1L).commit();
        return true;
    }

    static synchronized void decrement(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(TOTAL, Math.max(0L, p.getLong(TOTAL, 0L) - 1L)).commit();
    }

    static final String VIVANT = "vivant", PAUSE_MAX = "pause_max";

    /** Le service signale toutes les 2 s qu'il tourne ; une pause plus longue veut dire que le téléphone l'a gelé. */
    static void battement(Context c) {
        SharedPreferences p = prefs(c);
        long maintenant = System.currentTimeMillis();
        long dernier = p.getLong(VIVANT, 0L);
        SharedPreferences.Editor e = p.edit().putLong(VIVANT, maintenant);
        if (dernier > 0 && maintenant - dernier > 6000) {
            e.putLong(PAUSE_MAX, Math.max(p.getLong(PAUSE_MAX, 0L), maintenant - dernier));
        }
        e.apply();
    }

    static void nouveauDepart(Context c) {
        prefs(c).edit().putLong(VIVANT, 0L).putLong(PAUSE_MAX, 0L).putString(JOURNAL, "").apply();
    }

    static final String JOURNAL = "journal";

    /** Garde les 30 derniers événements, avec l'heure au centième de seconde. */
    static synchronized void journal(Context c, String ligne) {
        SharedPreferences p = prefs(c);
        String heure = new java.text.SimpleDateFormat("HH:mm:ss.SS", java.util.Locale.FRANCE).format(new java.util.Date());
        String tout = p.getString(JOURNAL, "") + heure + "  " + ligne + "\n";
        String[] lignes = tout.split("\n");
        if (lignes.length > 30) {
            StringBuilder sb = new StringBuilder();
            for (int k = lignes.length - 30; k < lignes.length; k++) sb.append(lignes[k]).append('\n');
            tout = sb.toString();
        }
        p.edit().putString(JOURNAL, tout).apply();
    }

    static synchronized void reset(Context c) {
        prefs(c).edit().putLong(TOTAL, 0L).commit();
        dernierAppui = -1L;
    }
}
