package fr.remis.cestca;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int DEMANDE_MICRO = 1;
    private static final int FREQ = 16000;              // échantillons par seconde
    private static final int BLOC = FREQ / 10;          // 0,1 seconde
    private static final int TRANCHE_MAX = FREQ * 10;   // on analyse le son par tranches de 10 s au plus
    private static final int TRANCHE_MIN = FREQ * 7;    // et on coupe dans un blanc entre 7 et 10 s

    private static final int FOND = Color.parseColor("#1b1f3b");
    private static final int CARTE = Color.parseColor("#262b52");
    private static final int ACCENT = Color.parseColor("#ffb703");
    private static final int VERT = Color.parseColor("#2ec27e");
    private static final int ROUGE = Color.parseColor("#e5484d");
    private static final int DOUX = Color.parseColor("#a9adc9");

    // c'est ça / c'est sa / cest ça / s'est ça / sait ça / ces ça / cé ça ...
    private static final Pattern MOTIF = Pattern.compile(
            "\\b(?:[cs]\\s*['’]?\\s*est|sait|ses|ces|cé)\\s*(?:ça|ca|sa)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private TextView txtNombre, txtEtat, txtDuree, txtRythme, txtTexte;
    private Button btnDemarrer;
    private CheckBox optVibre, optSilence;
    private RadioButton modePrecis, modeRapide, modeGoogle;
    private GradientDrawable fondCercle;

    private boolean enEcoute = false;
    private int compte = 0;
    private long tempsCumule = 0, debutSegment = 0;
    private final ArrayList<String> historique = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private boolean sonCoupe = false;

    // Moteur Whisper
    private long ctxPrecis = 0, ctxRapide = 0;
    private boolean chargement = false;
    private volatile boolean micActif = false;
    private int numeroSession = 0;
    private volatile LinkedBlockingQueue<short[]> fileSon;
    private volatile boolean analyseEnCours = false;
    private static final Object VERROU = new Object(); // une seule analyse à la fois par moteur

    // Moteur Google
    private SpeechRecognizer reco;
    private Intent intentReco;
    private int maxProvisoire = 0;

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

    // =====================================================================
    // Écran
    // =====================================================================

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable arrondi(int couleur, float rayon) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(dp(rayon));
        return g;
    }

    private RadioButton choix(RadioGroup groupe, String texte) {
        RadioButton r = new RadioButton(this);
        r.setText(texte);
        r.setTextColor(Color.WHITE);
        r.setId(View.generateViewId());
        groupe.addView(r);
        return r;
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

        RadioGroup choixMoteur = new RadioGroup(this);
        choixMoteur.setOrientation(RadioGroup.VERTICAL);
        modePrecis = choix(choixMoteur, "Précis – Whisper (recommandé, sans internet)");
        modeRapide = choix(choixMoteur, "Rapide – Whisper léger (téléphone ancien)");
        modeGoogle = choix(choixMoteur, "Google – texte en direct, moins fiable à plusieurs");
        choixMoteur.check(modePrecis.getId());
        LinearLayout.LayoutParams pm = new LinearLayout.LayoutParams(-1, -2);
        pm.topMargin = dp(10);
        racine.addView(choixMoteur, pm);

        LinearLayout cercle = new LinearLayout(this);
        cercle.setOrientation(LinearLayout.VERTICAL);
        cercle.setGravity(Gravity.CENTER);
        fondCercle = new GradientDrawable();
        fondCercle.setShape(GradientDrawable.OVAL);
        fondCercle.setColor(CARTE);
        fondCercle.setStroke(dp(6), Color.parseColor("#343a6b"));
        cercle.setBackground(fondCercle);
        LinearLayout.LayoutParams pc = new LinearLayout.LayoutParams(dp(240), dp(240));
        pc.topMargin = dp(14);
        racine.addView(cercle, pc);

        txtNombre = new TextView(this);
        txtNombre.setText("0");
        txtNombre.setTextColor(ACCENT);
        txtNombre.setTextSize(90);
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
        pe.topMargin = dp(12);
        racine.addView(txtEtat, pe);

        LinearLayout boutons = new LinearLayout(this);
        boutons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(-1, -2);
        pb.topMargin = dp(14);
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

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ps = new LinearLayout.LayoutParams(-1, -2);
        ps.topMargin = dp(12);
        racine.addView(stats, ps);
        txtDuree = ajouterStat(stats, "00:00", "durée d'écoute", true);
        txtRythme = ajouterStat(stats, "–", "par minute", false);

        optVibre = new CheckBox(this);
        optVibre.setText("Vibrer à chaque « c'est ça »");
        optVibre.setTextColor(DOUX);
        optVibre.setChecked(true);
        LinearLayout.LayoutParams po = new LinearLayout.LayoutParams(-1, -2);
        po.topMargin = dp(8);
        racine.addView(optVibre, po);

        optSilence = new CheckBox(this);
        optSilence.setText("Mode Google : couper le bip de relance");
        optSilence.setTextColor(DOUX);
        optSilence.setChecked(true);
        racine.addView(optSilence, new LinearLayout.LayoutParams(-1, -2));

        txtTexte = new TextView(this);
        txtTexte.setText("Le texte entendu s'affichera ici.");
        txtTexte.setTextColor(DOUX);
        txtTexte.setTextSize(15);
        txtTexte.setLineSpacing(0, 1.3f);
        txtTexte.setPadding(dp(14), dp(14), dp(14), dp(14));
        txtTexte.setBackground(arrondi(CARTE, 14));
        txtTexte.setMinHeight(dp(90));
        LinearLayout.LayoutParams pt = new LinearLayout.LayoutParams(-1, -2);
        pt.topMargin = dp(10);
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

    private void etat(String texte, int couleur) {
        txtEtat.setText(texte);
        txtEtat.setTextColor(couleur);
    }

    // =====================================================================
    // Démarrage / arrêt
    // =====================================================================

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
            if (resultats.length > 0 && resultats[0] == PackageManager.PERMISSION_GRANTED) demarrer();
            else etat("Accès au micro refusé. Autorisez-le dans Paramètres › Applications › C'est ça.", ROUGE);
        }
    }

    private void demarrer() {
        if (modeGoogle.isChecked()) demarrerGoogle();
        else demarrerWhisper(modePrecis.isChecked());
    }

    private void passerEnEcoute(boolean couper) {
        enEcoute = true;
        debutSegment = SystemClock.elapsedRealtime();
        couperSon(couper);
        modePrecis.setEnabled(false);
        modeRapide.setEnabled(false);
        modeGoogle.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnDemarrer.setText("■  Arrêter");
        btnDemarrer.setBackground(arrondi(ROUGE, 16));
        fondCercle.setStroke(dp(6), VERT);
        etat("● J'écoute…", VERT);
        handler.removeCallbacks(tic);
        handler.post(tic);
    }

    private void arreter() {
        if (enEcoute) tempsCumule += SystemClock.elapsedRealtime() - debutSegment;
        enEcoute = false;
        handler.removeCallbacks(tic);
        micActif = false;  // Whisper : on coupe le micro, l'analyse du son déjà enregistré continue
        if (reco != null) {
            try { reco.cancel(); } catch (Exception ignored) { }
        }
        maxProvisoire = 0;
        couperSon(false);
        modePrecis.setEnabled(true);
        modeRapide.setEnabled(true);
        modeGoogle.setEnabled(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnDemarrer.setText("▶  Démarrer");
        btnDemarrer.setBackground(arrondi(VERT, 16));
        fondCercle.setStroke(dp(6), Color.parseColor("#343a6b"));
        if (analyseEnCours || (fileSon != null && !fileSon.isEmpty())) etat("● Arrêté – fin de l'analyse en cours…", ACCENT);
        else etat("● À l'arrêt", DOUX);
        majDuree();
    }

    // =====================================================================
    // Moteur Whisper
    // =====================================================================

    private void demarrerWhisper(boolean precis) {
        long ctx = precis ? ctxPrecis : ctxRapide;
        if (ctx != 0) { ecouterWhisper(ctx); return; }
        if (chargement) return;
        chargement = true;
        btnDemarrer.setEnabled(false);
        etat("Préparation du moteur… (jusqu'à 30 s la première fois)", ACCENT);
        final String nom = precis ? "ggml-small.bin" : "ggml-base.bin";
        new Thread(() -> {
            long c = 0;
            String erreur = null;
            try {
                File f = copierModele(nom);
                c = Whisper.charger(f.getAbsolutePath());
                if (c == 0) erreur = "le modèle n'a pas pu être chargé";
            } catch (Throwable t) {
                erreur = t.getMessage();
            }
            final long resultat = c;
            final String err = erreur;
            handler.post(() -> {
                chargement = false;
                btnDemarrer.setEnabled(true);
                if (err != null) { etat("Impossible de préparer le moteur : " + err, ROUGE); return; }
                if (precis) ctxPrecis = resultat; else ctxRapide = resultat;
                ecouterWhisper(resultat);
            });
        }, "chargement").start();
    }

    /** Copie le modèle depuis l'application vers la mémoire du téléphone (une seule fois). */
    private File copierModele(String nom) throws Exception {
        File dest = new File(getFilesDir(), nom);
        long attendu = -1;
        try (AssetFileDescriptor fd = getAssets().openFd("whisper/" + nom)) {
            attendu = fd.getLength();
        } catch (Exception ignored) { }
        if (dest.exists() && (attendu < 0 || dest.length() == attendu)) return dest;
        File tmp = new File(getFilesDir(), nom + ".tmp");
        try (InputStream in = getAssets().open("whisper/" + nom);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new Exception("copie du modèle impossible");
        return dest;
    }

    private void ecouterWhisper(final long ctx) {
        int taille = Math.max(AudioRecord.getMinBufferSize(FREQ, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), FREQ * 2 * 4);
        final AudioRecord micro;
        try {
            // Micro « brut » : capte toute la pièce, pas seulement la personne qui tient le téléphone
            micro = new AudioRecord(MediaRecorder.AudioSource.MIC, FREQ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, taille);
        } catch (SecurityException e) {
            etat("Accès au micro refusé.", ROUGE);
            return;
        }
        if (micro.getState() != AudioRecord.STATE_INITIALIZED) {
            micro.release();
            etat("Le micro est déjà utilisé par une autre application.", ROUGE);
            return;
        }

        final int session = ++numeroSession;
        final LinkedBlockingQueue<short[]> file = new LinkedBlockingQueue<>();
        final short[] FIN = new short[0];
        fileSon = file;
        micActif = true;

        // 1) Le micro : lit le son et le range, rien n'est jamais jeté
        Thread filMicro = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            micro.startRecording();
            while (micActif) {
                short[] bloc = new short[BLOC];
                int n = micro.read(bloc, 0, bloc.length);
                if (n <= 0) continue;
                if (n < bloc.length) bloc = Arrays.copyOf(bloc, n);
                file.offer(bloc);
            }
            try { micro.stop(); } catch (Exception ignored) { }
            micro.release();
            file.offer(FIN);
        }, "micro");

        // 2) L'analyse : découpe le son en tranches de 7 à 10 s (coupées dans un blanc) et les transcrit
        Thread filAnalyse = new Thread(() -> {
            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
            float[] tampon = new float[TRANCHE_MAX * 3];
            int n = 0;
            try {
                while (true) {
                    short[] bloc = file.take();
                    boolean fin = (bloc == FIN);
                    if (!fin) {
                        if (n + bloc.length > tampon.length) tampon = Arrays.copyOf(tampon, tampon.length * 2);
                        for (short s : bloc) tampon[n++] = s / 32768f;
                    }
                    while (n >= TRANCHE_MAX) {
                        int coupe = chercherBlanc(tampon, n);
                        analyser(session, ctx, Arrays.copyOf(tampon, coupe), threads);
                        System.arraycopy(tampon, coupe, tampon, 0, n - coupe);
                        n -= coupe;
                    }
                    if (fin) {
                        if (n > FREQ / 2) analyser(session, ctx, Arrays.copyOf(tampon, n), threads);
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                handler.post(() -> { if (!enEcoute && session == numeroSession) etat("● À l'arrêt", DOUX); });
            }
        }, "analyse");
        filAnalyse.setPriority(Thread.MAX_PRIORITY);
        filAnalyse.start();
        filMicro.start();

        passerEnEcoute(false);
        txtTexte.setText("Le texte entendu s'affichera ici (par tranches de quelques secondes).");
    }

    /** Cherche le moment le plus calme entre 7 et 10 s pour ne pas couper un mot en deux. */
    private static int chercherBlanc(float[] son, int n) {
        int meilleur = Math.min(n, TRANCHE_MAX);
        double plusCalme = Double.MAX_VALUE;
        int pas = BLOC / 2;
        for (int debut = TRANCHE_MIN; debut + BLOC <= Math.min(n, TRANCHE_MAX); debut += pas) {
            double e = 0;
            for (int i = debut; i < debut + BLOC; i++) e += son[i] * son[i];
            if (e < plusCalme) { plusCalme = e; meilleur = debut + BLOC / 2; }
        }
        return meilleur;
    }

    /** Vrai s'il y a au moins un peu de son au-dessus du bruit de fond. */
    private static boolean contientDuSon(float[] son) {
        int blocsActifs = 0;
        for (int debut = 0; debut + BLOC <= son.length; debut += BLOC) {
            double e = 0;
            for (int i = debut; i < debut + BLOC; i++) e += son[i] * son[i];
            if (Math.sqrt(e / BLOC) > 0.004) blocsActifs++;
        }
        return blocsActifs >= 3;
    }

    private void analyser(int session, long ctx, float[] morceau, int threads) {
        if (!contientDuSon(morceau)) return;
        analyseEnCours = true;
        // Le moteur ne traite que la durée réelle du morceau (beaucoup plus rapide)
        int audioCtx = Math.min(1500, (int) (morceau.length / (float) FREQ * 50) + 64);
        String texte;
        try {
            byte[] octets;
            synchronized (VERROU) { octets = Whisper.transcrire(ctx, morceau, threads, audioCtx); }
            texte = new String(octets, StandardCharsets.UTF_8).trim();
        } finally {
            analyseEnCours = false;
        }
        final String t = texte;
        handler.post(() -> resultatWhisper(session, t));
    }

    private void resultatWhisper(int session, String texte) {
        if (session != numeroSession || texte.isEmpty()) return;
        // Whisper invente parfois ce genre de phrases sur du bruit : on les ignore
        String bas = texte.toLowerCase(Locale.FRANCE);
        if (bas.contains("sous-titr") || bas.contains("amara.org")) return;
        int n = compter(texte);
        if (n > 0) ajouter(n);
        historique.add(texte);
        if (historique.size() > 40) historique.remove(0);
        afficherTexte("");
        if (!enEcoute && !analyseEnCours && (fileSon == null || fileSon.isEmpty())) etat("● À l'arrêt", DOUX);
    }

    private double retardWhisper() {
        LinkedBlockingQueue<short[]> f = fileSon;
        return f == null ? 0 : f.size() / 10.0;
    }

    // =====================================================================
    // Moteur Google
    // =====================================================================

    private void demarrerGoogle() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            etat("Aucun service de reconnaissance vocale Google sur ce téléphone.", ROUGE);
            return;
        }
        if (reco == null) {
            reco = SpeechRecognizer.createSpeechRecognizer(this);
            reco.setRecognitionListener(ecouteurGoogle);
            intentReco = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intentReco.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intentReco.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
            intentReco.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intentReco.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            intentReco.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intentReco.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            intentReco.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
            intentReco.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        }
        numeroSession++;
        passerEnEcoute(optSilence.isChecked());
        lancerGoogle();
    }

    private void lancerGoogle() {
        if (!enEcoute || reco == null || !modeGoogle.isChecked()) return;
        try {
            reco.cancel();
            reco.startListening(intentReco);
        } catch (Exception e) {
            handler.postDelayed(this::lancerGoogle, 500);
        }
    }

    private final RecognitionListener ecouteurGoogle = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onError(int erreur) {
            if (!enEcoute) return;
            if (maxProvisoire > 0) { ajouter(maxProvisoire); maxProvisoire = 0; }
            if (erreur == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                arreter();
                etat("Accès au micro refusé.", ROUGE);
                return;
            }
            long pause = (erreur == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) ? 600 : 150;
            handler.postDelayed(MainActivity.this::lancerGoogle, pause);
        }

        @Override public void onPartialResults(Bundle partiels) {
            ArrayList<String> r = partiels.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null) for (String t : r) maxProvisoire = Math.max(maxProvisoire, compter(t));
            afficherTexte(r != null && !r.isEmpty() ? r.get(0) : "");
        }

        @Override public void onResults(Bundle resultats) {
            ArrayList<String> r = resultats.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            int meilleur = maxProvisoire;
            maxProvisoire = 0;
            if (r != null) for (String t : r) meilleur = Math.max(meilleur, compter(t));
            if (meilleur > 0) ajouter(meilleur);
            if (r != null && !r.isEmpty()) {
                String phrase = r.get(0).trim();
                if (!phrase.isEmpty()) {
                    historique.add(phrase);
                    if (historique.size() > 40) historique.remove(0);
                }
            }
            afficherTexte("");
            if (enEcoute) handler.postDelayed(MainActivity.this::lancerGoogle, 100);
        }
    };

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

    // =====================================================================
    // Comptage et affichage
    // =====================================================================

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
        handler.postDelayed(() -> fondCercle.setStroke(dp(6), enEcoute ? VERT : Color.parseColor("#343a6b")), 400);
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
        if (enEcoute && !modeGoogle.isChecked()) {
            double r = retardWhisper();
            if (r >= 3) etat(String.format(Locale.FRANCE, "● J'écoute… (analyse en retard de %.0f s, rien n'est perdu)", r), ACCENT);
            else etat("● J'écoute… (texte mis à jour toutes les ~10 s)", VERT);
        }
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
        micActif = false;
        handler.removeCallbacksAndMessages(null);
        couperSon(false);
        if (reco != null) {
            try { reco.destroy(); } catch (Exception ignored) { }
            reco = null;
        }
        super.onDestroy();
    }
}
