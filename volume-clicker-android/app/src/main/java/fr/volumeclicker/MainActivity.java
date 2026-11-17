package fr.volumeclicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView up, down, total, status;
    private Button toggle;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
        (p, key) -> runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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

    private boolean accessibilityEnabled() {
        AccessibilityManager manager=(AccessibilityManager)getSystemService(Context.ACCESSIBILITY_SERVICE);
        if(manager==null) return false;
        List<AccessibilityServiceInfo> list=manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String name=getPackageName()+"/"+VolumeAccessibilityService.class.getName();
        for(AccessibilityServiceInfo info:list) if(name.equals(info.getId())) return true;
        return false;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if((key==KeyEvent.KEYCODE_VOLUME_UP || key==KeyEvent.KEYCODE_VOLUME_DOWN) && Counters.running(this)) {
            if(!accessibilityEnabled() && event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) {
                Counters.increment(this,key==KeyEvent.KEYCODE_VOLUME_UP);
                refresh();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private TextView label(String text,int size,int color) {
        TextView v=new TextView(this);
        v.setText(text); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER);
        v.setPadding(8,12,8,12);
        return v;
    }
    private Button button(String text,LinearLayout root) {
        Button b=new Button(this);
        b.setText(text); b.setAllCaps(false);
        root.addView(b,new LinearLayout.LayoutParams(-1,-2));
        return b;
    }
    private TextView counter(LinearLayout root,String title,int accent) {
        root.addView(label(title,17,accent));
        TextView n=label("0",50,Color.WHITE);
        root.addView(n);
        return n;
    }
    private void render() {
        int bg=Color.rgb(12,18,32), muted=Color.rgb(171,189,209);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40,40,40,40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);
        root.addView(label("VOLUME CLICKER",26,Color.WHITE));
        root.addView(label("Compteur physique, y compris en arrière-plan",14,muted));
        up=counter(root,"VOLUME +",Color.CYAN);
        down=counter(root,"VOLUME −",Color.rgb(183,146,255));
        total=counter(root,"TOTAL",Color.WHITE);
        status=label("",14,muted);
        root.addView(status);
        toggle=button("DÉMARRER LE COMPTAGE",root);
        toggle.setOnClickListener(v -> {
            Counters.setRunning(this,!Counters.running(this));
            refresh();
            if(Counters.running(this) && !accessibilityEnabled())
                new AlertDialog.Builder(this)
                    .setTitle("Activer le service d'accessibilité")
                    .setMessage("Pour compter pendant l'utilisation d'autres applications, active Volume Clicker dans les paramètres d'accessibilité. Écran éteint : non garanti selon le téléphone.")
                    .setNegativeButton("Plus tard",null)
                    .setPositiveButton("Paramètres",(d,w)->openSettings())
                    .show();
        });
        button("OUVRIR LES PARAMÈTRES D'ACCESSIBILITÉ",root)
            .setOnClickListener(v->openSettings());
        button("RÉINITIALISER",root).setOnClickListener(v->
            new AlertDialog.Builder(this)
                .setTitle("Remettre les compteurs à zéro ?")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Réinitialiser",(d,w)->{Counters.reset(this);refresh();})
                .show());
        root.addView(label("Un appui long = 1. Le volume système ne change pas pendant le comptage. Capture écran verrouillé ou éteint non garantie.",12,muted));
        setContentView(scroll);
    }
    private void openSettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }
    private void refresh() {
        if(up==null) return;
        long u=preferences.getLong(Counters.UP,0),d=preferences.getLong(Counters.DOWN,0);
        up.setText(String.format(Locale.FRANCE,"%,d",u));
        down.setText(String.format(Locale.FRANCE,"%,d",d));
        total.setText(String.format(Locale.FRANCE,"%,d",u+d));
        boolean active=Counters.running(this);
        status.setText(active ? (accessibilityEnabled() ? "● Comptage actif en arrière-plan" : "● Active le service d'accessibilité") : "○ Comptage arrêté");
        toggle.setText(active?"ARRÊTER LE COMPTAGE":"DÉMARRER LE COMPTAGE");
    }
}
