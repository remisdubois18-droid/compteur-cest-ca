package fr.volumeclicker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int FOND = Color.parseColor("#1b1f3b");
    private static final int CARTE = Color.parseColor("#262b52");
    private static final int ACCENT = Color.parseColor("#ffb703");
    private static final int VERT = Color.parseColor("#2ec27e");
    private static final int ROUGE = Color.parseColor("#e5484d");
    private static final int DOUX = Color.parseColor("#a9adc9");

    private TextView nombre, etat;
    private Button bascule;
    private GradientDrawable anneau;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener ecouteur =
            (p, cle) -> runOnUiThread(this::rafraichir);

    @Override
    public void onCreate(Bundle etatSauve) {
        super.onCreate(etatSauve);
        getWindow().setStatusBarColor(FOND);
        preferences = Counters.prefs(this);
        construire();
        rafraichir();
    }

    @Override
    protected void onResume() {
        super.onResume();
        preferences.registerOnSharedPreferenceChangeListener(ecouteur);
        rafraichir();
    }

    @Override
    protected void onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(ecouteur);
        super.onPause();
    }

    /** Application ouverte à l'écran : on compte directement les touches de volume. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && Counters.running(this)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                Counters.increment(this);
                rafraichir();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable arrondi(int couleur) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(dp(16));
        return g;
    }

    private Button bouton(LinearLayout parent, String texte, int couleur, float poids, int hauteur) {
        Button b = new Button(this);
        b.setText(texte);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setBackground(arrondi(couleur));
        LinearLayout.LayoutParams p = poids > 0
                ? new LinearLayout.LayoutParams(0, dp(hauteur), poids)
                : new LinearLayout.LayoutParams(-1, dp(hauteur));
        p.setMargins(dp(5), dp(6), dp(5), dp(6));
        parent.addView(b, p);
        return b;
    }

    private void construire() {
        ScrollView defilement = new ScrollView(this);
        defilement.setBackgroundColor(FOND);
        defilement.setFillViewport(true);
        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setGravity(Gravity.CENTER_HORIZONTAL);
        racine.setPadding(dp(18), dp(28), dp(18), dp(24));
        defilement.addView(racine);

        TextView titre = new TextView(this);
        titre.setText("Compteur");
        titre.setTextColor(DOUX);
        titre.setTextSize(20);
        racine.addView(titre);

        LinearLayout cercle = new LinearLayout(this);
        cercle.setGravity(Gravity.CENTER);
        anneau = new GradientDrawable();
        anneau.setShape(GradientDrawable.OVAL);
        anneau.setColor(CARTE);
        anneau.setStroke(dp(6), Color.parseColor("#343a6b"));
        cercle.setBackground(anneau);
        LinearLayout.LayoutParams pc = new LinearLayout.LayoutParams(dp(260), dp(260));
        pc.topMargin = dp(18);
        racine.addView(cercle, pc);

        nombre = new TextView(this);
        nombre.setTextColor(ACCENT);
        nombre.setTextSize(96);
        nombre.setTypeface(Typeface.DEFAULT_BOLD);
        cercle.addView(nombre);

        etat = new TextView(this);
        etat.setTextSize(15);
        etat.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pe = new LinearLayout.LayoutParams(-1, -2);
        pe.topMargin = dp(14);
        racine.addView(etat, pe);

        bascule = bouton(racine, "", VERT, 0, 66);
        bascule.setTextSize(19);
        bascule.setTypeface(Typeface.DEFAULT_BOLD);
        bascule.setOnClickListener(v -> basculer());

        LinearLayout ligne = new LinearLayout(this);
        ligne.setOrientation(LinearLayout.HORIZONTAL);
        racine.addView(ligne, new LinearLayout.LayoutParams(-1, -2));
        bouton(ligne, "− 1", Color.parseColor("#3b4175"), 1, 56).setOnClickListener(v -> Counters.decrement(this));
        bouton(ligne, "Remettre à 0", Color.parseColor("#3b4175"), 2, 56).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Remettre le compteur à 0 ?")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Oui", (d, w) -> Counters.reset(this))
                        .show());

        bouton(racine, "Réglages batterie (à faire une fois)", CARTE, 0, 52).setOnClickListener(v -> reglagesBatterie());

        TextView aide = new TextView(this);
        aide.setText("Appuyez sur « Démarrer », puis verrouillez le téléphone : chaque appui sur Volume + ou "
                + "Volume − ajoute 1 (petite vibration). Le compteur s'affiche aussi dans la notification.\n\n"
                + "Pendant le comptage, les touches de volume ne règlent plus le son (le volume reste au milieu). "
                + "« Arrêter » rend au volume son niveau d'avant.");
        aide.setTextColor(DOUX);
        aide.setTextSize(13);
        aide.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(-1, -2);
        pa.topMargin = dp(14);
        racine.addView(aide, pa);

        setContentView(defilement);
    }

    private void reglagesBatterie() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) { }
        }
        new AlertDialog.Builder(this)
                .setTitle("Fonctionnement en arrière-plan")
                .setMessage("Sur OPPO (ColorOS) : dans les informations de l'application, ouvrez « Utilisation de la batterie » "
                        + "et autorisez l'activité en arrière-plan / choisissez « Sans restriction ». "
                        + "Sinon le téléphone peut couper le compteur quand l'écran est éteint.")
                .setNegativeButton("Fermer", null)
                .setPositiveButton("Ouvrir les réglages", (d, w) -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .show();
    }

    private void basculer() {
        if (Counters.running(this)) {
            startService(new Intent(this, ButtonCounterService.class).setAction(ButtonCounterService.STOP));
            Counters.setRunning(this, false);
            rafraichir();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
        Intent i = new Intent(this, ButtonCounterService.class).setAction(ButtonCounterService.START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this).setTitle("Démarrage impossible")
                    .setMessage("Android a empêché le démarrage : " + e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private void rafraichir() {
        if (nombre == null) return;
        nombre.setText(String.valueOf(Counters.total(this)));
        boolean actif = Counters.running(this);
        etat.setText(actif ? "● Comptage actif – fonctionne téléphone verrouillé" : "○ Comptage arrêté");
        etat.setTextColor(actif ? VERT : DOUX);
        anneau.setStroke(dp(6), actif ? VERT : Color.parseColor("#343a6b"));
        bascule.setText(actif ? "■  Arrêter" : "▶  Démarrer");
        bascule.setBackground(arrondi(actif ? ROUGE : VERT));
    }
}
