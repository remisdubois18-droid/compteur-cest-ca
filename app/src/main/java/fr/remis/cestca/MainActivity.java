package fr.remis.cestca;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int DEMANDE_MICRO = 1;
    private static final int FOND = Color.parseColor("#1b1f3b");
    private static final int CARTE = Color.parseColor("#262b52");
    private static final int ACCENT = Color.parseColor("#ffb703");
    private static final int VERT = Color.parseColor("#2ec27e");
    private static final int ROUGE = Color.parseColor("#e5484d");
    private static final int DOUX = Color.parseColor("#a9adc9");

    // c'est ça / c'est sa / cest ça / c est ca ...
    private static final Pattern MOTIF = Pattern.compile(
            "\\bc\\s*['’]?\\s*est\\s+(ça|ca|sa)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private TextView txtNombre, txtEtat, txtDuree, txtRythme, txtTexte;
    private Button btnDemarrer;
    private CheckBox optVibre, optSilence;
    private GradientDrawable fondCercle;

    private SpeechRecognizer reco;
    private Intent intentReco;
    private boolean enEcoute = false;
    private int compte = 0;
    private long tempsCumule = 0, debutSegment = 0;
    private final ArrayList<String> historique = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private boolean sonCoupe = false;

    private final Runnable tic = new Runnable() {
        @Override public void run() {
            majDuree();
            if (enEcoute) handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        getWindow().setStatusBarColor(FOND);
        construireEcran();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable arrondi(int couleur, float rayon) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(dp(rayon));
        return g;
    }

    private void construireEcran() {
        ScrollView defilement = new ScrollView(this);
        defilement.setBackgroundColor(FOND);
        defilement.setFillViewport(true);

        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setGravity(Gravity.CENTER_HORIZONTAL);
        racine.setPadding(dp(18), dp(24), dp(18), dp(24));
        defilement.addView(racine);

        TextView titre = new TextView(this);
        titre.setText("Compteur de « c'est ça »");
        titre.setTextColor(DOUX);
        titre.setTextSize(18);
        racine.addView(titre);

        // Cercle du compteur
        LinearLayout cercle = new LinearLayout(this);
        cercle.setOrientation(LinearLayout.VERTICAL);
        cercle.setGravity(Gravity.CENTER);
        fondCercle = new GradientDrawable();
        fondCercle.setShape(GradientDrawable.OVAL);
        fondCercle.setColor(CARTE);
        fondCercle.setStroke(dp(6), Color.parseColor("#343a6b"));
        cercle.setBackground(fondCercle);
        LinearLayout.LayoutParams pc = new LinearLayout.LayoutParams(dp(260), dp(260));
        pc.topMargin = dp(20);
        racine.addView(cercle, pc);

        txtNombre = new TextView(this);
        txtNombre.setText("0");
        txtNombre.setTextColor(ACCENT);
        txtNombre.setTextSize(96);
        txtNombre.setTypeface(Typeface.DEFAULT_BOLD);
        txtNombre.setGravity(Gravity.CENTER);
        cercle.addView(txtNombre);

        TextView libelle = new TextView(this);
        libelle.setText("« c'est ça » entendus");
        libelle.setTextColor(DOUX);
        libelle.setTextSize(15);
        cercle.addView(libelle);

        txtEtat = new TextView(this);
        txtEtat.setText("● À l'arrêt");
        txtEtat.setTextColor(DOUX);
        txtEtat.setTextSize(15);
        txtEtat.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pe = new LinearLayout.LayoutParams(-1, -2);
        pe.topMargin = dp(14);
        racine.addView(txtEtat, pe);

        // Boutons
        LinearLayout boutons = new LinearLayout(this);
        boutons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(-1, -2);
        pb.topMargin = dp(16);
        racine.addView(boutons, pb);

        btnDemarrer = new Button(this);
        btnDemarrer.setText("▶  Démarrer");
        btnDemarrer.setAllCaps(false);
        btnDemarrer.setTextSize(18);
        btnDemarrer.setTextColor(Color.WHITE);
        btnDemarrer.setTypeface(Typeface.DEFAULT_BOLD);
        btnDemarrer.setBackground(arrondi(VERT, 16));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(64), 2f);
        p1.rightMargin = dp(12);
        boutons.addView(btnDemarrer, p1);
        btnDemarrer.setOnClickListener(v -> { if (enEcoute) arreter(); else demanderPuisDemarrer(); });

        Button btnZero = new Button(this);
        btnZero.setText("Remettre à 0");
        btnZero.setAllCaps(false);
        btnZero.setTextSize(15);
        btnZero.setTextColor(Color.WHITE);
        btnZero.setBackground(arrondi(Color.parseColor("#3b4175"), 16));
        boutons.addView(btnZero, new LinearLayout.LayoutParams(0, dp(64), 1f));
        btnZero.setOnClickListener(v -> remettreAZero());

        // Statistiques
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ps = new LinearLayout.LayoutParams(-1, -2);
        ps.topMargin = dp(14);
        racine.addView(stats, ps);
        txtDuree = ajouterStat(stats, "00:00", "durée d'écoute", true);
        txtRythme = ajouterStat(stats, "–", "par minute", false);

        // Options
        optVibre = new CheckBox(this);
        optVibre.setText("Vibrer à chaque « c'est ça »");
        optVibre.setTextColor(DOUX);
        optVibre.setChecked(true);
        LinearLayout.LayoutParams po = new LinearLayout.LayoutParams(-1, -2);
        po.topMargin = dp(10);
        racine.addView(optVibre, po);

        optSilence = new CheckBox(this);
        optSilence.setText("Couper le petit bip de relance de l'écoute");
        optSilence.setTextColor(DOUX);
        optSilence.setChecked(true);
        racine.addView(optSilence, new LinearLayout.LayoutParams(-1, -2));

        // Texte entendu
        txtTexte = new TextView(this);
        txtTexte.setText("Le texte entendu s'affichera ici.");
        txtTexte.setTextColor(DOUX);
        txtTexte.setTextSize(15);
        txtTexte.setLineSpacing(0, 1.3f);
        txtTexte.setPadding(dp(14), dp(14), dp(14), dp(14));
        txtTexte.setBackground(arrondi(CARTE, 14));
        txtTexte.setMinHeight(dp(90));
        LinearLayout.LayoutParams pt = new LinearLayout.LayoutParams(-1, -2);
        pt.topMargin = dp(12);
        racine.addView(txtTexte, pt);

        setContentView(defilement);
    }

    private TextView ajouterStat(LinearLayout parent, String valeur, String legende, boolean marge) {
        LinearLayout carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setGravity(Gravity.CENTER);
        carte.setPadding(dp(10), dp(12), dp(10), dp(12));
        carte.setBackground(arrondi(CARTE, 14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        if (marge) p.rightMargin = dp(12);
        parent.addView(carte, p);

        TextView v = new TextView(this);
        v.setText(valeur);
        v.setTextColor(Color.WHITE);
        v.setTextSize(22);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        carte.addView(v);

        TextView l = new TextView(this);
        l.setText(legende);
        l.setTextColor(DOUX);
        l.setTextSize(12);
        carte.addView(l);
        return v;
    }

    // ---------- Micro et reconnaissance ----------

    private void demanderPuisDemarrer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, DEMANDE_MICRO);
        } else {
            demarrer();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] resultats) {
        super.onRequestPermissionsResult(code, permissions, resultats);
        if (code == DEMANDE_MICRO) {
            if (resultats.length > 0 && resultats[0] == PackageManager.PERMISSION_GRANTED) {
                demarrer();
            } else {
                txtEtat.setText("Accès au micro refusé. Autorisez-le dans Paramètres › Applications › C'est ça.");
                txtEtat.setTextColor(ROUGE);
            }
        }
    }

    private void demarrer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            txtEtat.setText("Aucun service de reconnaissance vocale sur ce téléphone (installez ou activez l'application Google).");
            txtEtat.setTextColor(ROUGE);
            return;
        }
        if (reco == null) {
            reco = SpeechRecognizer.createSpeechRecognizer(this);
            reco.setRecognitionListener(ecouteur);
            intentReco = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intentReco.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intentReco.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
            intentReco.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intentReco.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intentReco.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            intentReco.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
            intentReco.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        }
        enEcoute = true;
        debutSegment = SystemClock.elapsedRealtime();
        couperSon(optSilence.isChecked());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnDemarrer.setText("■  Arrêter");
        btnDemarrer.setBackground(arrondi(ROUGE, 16));
        fondCercle.setStroke(dp(6), VERT);
        txtEtat.setText("● J'écoute…");
        txtEtat.setTextColor(VERT);
        handler.post(tic);
        lancerEcoute();
    }

    private void lancerEcoute() {
        if (!enEcoute || reco == null) return;
        try {
            reco.cancel();
            reco.startListening(intentReco);
        } catch (Exception e) {
            handler.postDelayed(this::lancerEcoute, 500);
        }
    }

    private void arreter() {
        if (enEcoute) tempsCumule += SystemClock.elapsedRealtime() - debutSegment;
        enEcoute = false;
        handler.removeCallbacksAndMessages(null);
        if (reco != null) {
            try { reco.cancel(); } catch (Exception ignored) { }
        }
        couperSon(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnDemarrer.setText("▶  Démarrer");
        btnDemarrer.setBackground(arrondi(VERT, 16));
        fondCercle.setStroke(dp(6), Color.parseColor("#343a6b"));
        txtEtat.setText("● À l'arrêt");
        txtEtat.setTextColor(DOUX);
        majDuree();
    }

    private void couperSon(boolean couper) {
        if (audio == null || couper == sonCoupe) return;
        int sens = couper ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, sens, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, sens, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, sens, 0);
            sonCoupe = couper;
        } catch (Exception ignored) { }
    }

    private final RecognitionListener ecouteur = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onError(int erreur) {
            if (!enEcoute) return;
            if (erreur == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                arreter();
                txtEtat.setText("Accès au micro refusé.");
                txtEtat.setTextColor(ROUGE);
                return;
            }
            if (erreur == SpeechRecognizer.ERROR_NETWORK || erreur == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || erreur == SpeechRecognizer.ERROR_SERVER) {
                txtEtat.setText("● J'écoute… (problème de réseau, nouvel essai)");
            }
            // Pas de parole, délai dépassé, service occupé : on relance simplement
            long pause = (erreur == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) ? 600 : 150;
            handler.postDelayed(MainActivity.this::lancerEcoute, pause);
        }

        @Override public void onPartialResults(Bundle partiels) {
            ArrayList<String> r = partiels.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            afficherTexte(r != null && !r.isEmpty() ? r.get(0) : "");
        }

        @Override public void onResults(Bundle resultats) {
            ArrayList<String> r = resultats.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) {
                String phrase = r.get(0).trim();
                if (!phrase.isEmpty()) {
                    int n = compter(phrase);
                    if (n > 0) ajouter(n);
                    historique.add(phrase);
                    if (historique.size() > 40) historique.remove(0);
                }
            }
            afficherTexte("");
            if (enEcoute) handler.postDelayed(MainActivity.this::lancerEcoute, 100);
        }
    };

    // ---------- Comptage et affichage ----------

    private static int compter(String t) {
        Matcher m = MOTIF.matcher(t.replace('’', '\''));
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private void ajouter(int n) {
        compte += n;
        txtNombre.setText(String.valueOf(compte));
        fondCercle.setStroke(dp(6), ACCENT);
        txtNombre.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110)
                .withEndAction(() -> txtNombre.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
        handler.postDelayed(() -> { if (enEcoute) fondCercle.setStroke(dp(6), VERT); }, 400);
        if (optVibre.isChecked()) {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }
        majRythme();
    }

    private void afficherTexte(String provisoire) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int debut = Math.max(0, historique.size() - 10);
        for (int i = debut; i < historique.size(); i++) {
            ajouterSurligne(sb, historique.get(i), false);
            sb.append(' ');
        }
        if (provisoire != null && !provisoire.isEmpty()) ajouterSurligne(sb, provisoire, true);
        if (sb.length() == 0) txtTexte.setText("Le texte entendu s'affichera ici.");
        else txtTexte.setText(sb);
    }

    private void ajouterSurligne(SpannableStringBuilder sb, String texte, boolean provisoire) {
        int base = sb.length();
        sb.append(texte);
        if (provisoire) {
            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#6f7499")), base, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher m = MOTIF.matcher(texte.replace('’', '\''));
        while (m.find()) {
            sb.setSpan(new BackgroundColorSpan(ACCENT), base + m.start(), base + m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(FOND), base + m.start(), base + m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), base + m.start(), base + m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private long dureeTotale() {
        return tempsCumule + (enEcoute ? SystemClock.elapsedRealtime() - debutSegment : 0);
    }

    private void majDuree() {
        long s = dureeTotale() / 1000;
        txtDuree.setText(String.format(Locale.FRANCE, "%02d:%02d", s / 60, s % 60));
        majRythme();
    }

    private void majRythme() {
        double min = dureeTotale() / 60000.0;
        txtRythme.setText(min >= 0.25 ? String.format(Locale.FRANCE, "%.1f", compte / min) : "–");
    }

    private void remettreAZero() {
        compte = 0;
        tempsCumule = 0;
        if (enEcoute) debutSegment = SystemClock.elapsedRealtime();
        historique.clear();
        txtNombre.setText("0");
        afficherTexte("");
        majDuree();
    }

    @Override
    protected void onDestroy() {
        enEcoute = false;
        handler.removeCallbacksAndMessages(null);
        couperSon(false);
        if (reco != null) {
            try { reco.destroy(); } catch (Exception ignored) { }
            reco = null;
        }
        super.onDestroy();
    }
}
