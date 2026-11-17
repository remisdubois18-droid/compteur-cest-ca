package fr.volumeclicker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView up, down, total, status;
    private Button toggle;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
        (p, key) -> runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences=Counters.prefs(this);
        render();
        refresh();
    }
    @Override protected void onResume() {
        super.onResume();
        preferences.registerOnSharedPreferenceChangeListener(listener);
        refresh();
    }
    @Override protected void onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code=e.getKeyCode();
        if((code==KeyEvent.KEYCODE_VOLUME_UP || code==KeyEvent.KEYCODE_VOLUME_DOWN) && Counters.running(this)) {
            if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
                Counters.increment(this,code==KeyEvent.KEYCODE_VOLUME_UP);
                refresh();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private TextView label(String text,int size,int color) {
        TextView view=new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(8,12,8,12);
        return view;
    }
    private Button button(String text,LinearLayout root) {
        Button b=new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        root.addView(b,new LinearLayout.LayoutParams(-1,-2));
        return b;
    }
    private TextView counter(LinearLayout root,String name,int accent) {
        root.addView(label(name,17,accent));
        TextView count=label("0",49,Color.WHITE);
        root.addView(count);
        return count;
    }
    private void render() {
        int bg=Color.rgb(12,18,32);
        int muted=Color.rgb(178,191,207);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,40,36,32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);
        root.addView(label("VOLUME CLICKER",25,Color.WHITE));
        root.addView(label("Touches physiques • compteur local",14,muted));
        up=counter(root,"VOLUME +",Color.CYAN);
        down=counter(root,"VOLUME −",Color.rgb(185,156,255));
        total=counter(root,"TOTAL",Color.WHITE);
        status=label("",15,muted);
        root.addView(status);
        toggle=button("DÉMARRER LE COMPTAGE",root);
        toggle.setOnClickListener(v -> toggleCounting());

        button("PARAMÈTRES BATTERIE / APPLICATION",root).setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Autoriser le fonctionnement en arrière-plan")
                .setMessage("Sur OPPO/ColorOS, ouvre les informations de Volume Clicker puis, si disponibles, autorise l'activité en arrière-plan et règle la batterie sur « Sans restriction ». Les libellés dépendent de ColorOS.")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Ouvrir",(d,w)->{
                    Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:"+getPackageName()));
                    startActivity(i);
                }).show());

        button("REMETTRE LES COMPTEURS À ZÉRO",root).setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Réinitialiser les compteurs ?")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Oui",(d,w)->{Counters.reset(this);refresh();})
                .show());

        root.addView(label("Le mode arrière-plan est expérimental : une session multimédia intercepte les réglages de volume. Cela peut empêcher le réglage du volume d'autres applications pendant le comptage. Écran verrouillé ou éteint : non garanti selon ColorOS. Un appui prolongé peut générer plusieurs incréments.",12,muted));
        setContentView(scroll);
    }

    private void toggleCounting() {
        if(Counters.running(this)) {
            Intent i=new Intent(this,ButtonCounterService.class).setAction(ButtonCounterService.STOP);
            startService(i);
            Counters.setRunning(this,false);
            refresh();
        } else {
            if(Build.VERSION.SDK_INT>=33 &&
               checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);
            }
            Intent i=new Intent(this,ButtonCounterService.class).setAction(ButtonCounterService.START);
            try {
                if(Build.VERSION.SDK_INT>=26) startForegroundService(i);
                else startService(i);
            } catch(RuntimeException exception) {
                new AlertDialog.Builder(this).setTitle("Démarrage impossible")
                    .setMessage("Android a empêché le démarrage du service : "+exception.getMessage())
                    .setPositiveButton("OK",null).show();
            }
        }
    }

    private void refresh() {
        if(up==null) return;
        long u=preferences.getLong(Counters.UP,0L);
        long d=preferences.getLong(Counters.DOWN,0L);
        up.setText(String.format(Locale.FRANCE,"%,d",u));
        down.setText(String.format(Locale.FRANCE,"%,d",d));
        total.setText(String.format(Locale.FRANCE,"%,d",u+d));
        boolean enabled=Counters.running(this);
        status.setText(enabled?"● Comptage démarré — mode arrière-plan expérimental":"○ Comptage arrêté");
        toggle.setText(enabled?"ARRÊTER LE COMPTAGE":"DÉMARRER LE COMPTAGE");
    }
}
