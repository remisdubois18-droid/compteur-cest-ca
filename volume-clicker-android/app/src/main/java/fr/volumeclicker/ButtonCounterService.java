package fr.volumeclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Compte les appuis sur les touches de volume, même téléphone verrouillé et écran éteint.
 *
 * Écran éteint, Android ne modifie le volume que si un son est en train de jouer : le service joue
 * donc en boucle un son inaudible. Il surveille ensuite le niveau du volume « multimédia » :
 * à chaque changement, il compte 1 et remet aussitôt le volume au milieu, pour que le prochain
 * appui (Volume + ou Volume −) provoque à nouveau un changement.
 */
public class ButtonCounterService extends Service {
    public static final String START = "fr.volumeclicker.action.START";
    public static final String STOP = "fr.volumeclicker.action.STOP";
    private static final String CANAL = "compteur-volume";
    private static final int NOTIFICATION_ID = 17;

    private MediaSession session;
    private AudioManager audio;
    private BroadcastReceiver surveillance;
    private int volumeOrigine = -1, volumeMilieu = -1;
    private static final String VOLUME_CHANGE = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_FLUX = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALEUR = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private AudioTrack silence;
    private PowerManager.WakeLock eveil;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            Counters.setRunning(this, false);
            toutArreter();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null && !Counters.running(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Counters.setRunning(this, true);
        try {
            afficherNotification();
            demarrerSilence();
            demarrerSession();
            surveillerVolume();
            garderEveille();
        } catch (RuntimeException e) {
            Counters.setRunning(this, false);
            toutArreter();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    // ------------------------------------------------------------------ notification

    private Notification construireNotification() {
        int immuable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent ouvrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | immuable);
        PendingIntent arreter = PendingIntent.getService(this, 1,
                new Intent(this, ButtonCounterService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | immuable);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_input_add)
                .setContentTitle("Compteur : " + Counters.total(this))
                .setContentText("Volume + ou Volume − pour ajouter 1")
                .setContentIntent(ouvrir)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", arreter).build())
                .build();
    }

    private void afficherNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel canal = new NotificationChannel(CANAL, "Compteur", NotificationManager.IMPORTANCE_LOW);
            canal.setShowBadge(false);
            canal.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(canal);
        }
        Notification n = construireNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void majNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, construireNotification());
    }

    // ------------------------------------------------------------------ son silencieux

    private void demarrerSilence() {
        if (silence != null) return;
        int freq = 44100;
        int images = freq; // 1 seconde de silence, jouée en boucle
        AudioAttributes attributs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(freq)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        silence = new AudioTrack(attributs, format, images * 2, AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        short[] son = new short[images];
        for (int i = 0; i < images; i++) son[i] = (short) ((i % 2 == 0) ? 1 : -1); // inaudible
        silence.write(son, 0, images);
        silence.setLoopPoints(0, images, -1);
        silence.play();
    }

    // ------------------------------------------------------------------ session multimédia

    private void demarrerSession() {
        if (session != null) return;
        session = new MediaSession(this, "CompteurVolume");
        session.setPlaybackToLocal(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .build());
        session.setActive(true);
    }

    // ------------------------------------------------------------------ surveillance du volume

    private void surveillerVolume() {
        if (surveillance != null) return;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volumeOrigine = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeMilieu = Math.max(1, max / 2);
        remettreAuMilieu();
        surveillance = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getIntExtra(EXTRA_FLUX, -1) != AudioManager.STREAM_MUSIC) return;
                int valeur = i.getIntExtra(EXTRA_VALEUR, volumeMilieu);
                if (valeur == volumeMilieu) return; // c'est notre propre remise au milieu
                remettreAuMilieu();
                if (Counters.increment(ButtonCounterService.this)) {
                    vibrer();
                    majNotification();
                }
            }
        };
        IntentFilter filtre = new IntentFilter(VOLUME_CHANGE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(surveillance, filtre, Context.RECEIVER_EXPORTED);
        else registerReceiver(surveillance, filtre);
    }

    private void remettreAuMilieu() {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeMilieu, 0);
        } catch (Exception ignored) { } // mode « Ne pas déranger » : Android peut refuser
    }

    private void garderEveille() {
        if (eveil != null) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        eveil = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Compteur:volume");
        eveil.setReferenceCounted(false);
        eveil.acquire();
    }

    private void vibrer() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(40);
    }

    private void toutArreter() {
        if (surveillance != null) {
            try { unregisterReceiver(surveillance); } catch (Exception ignored) { }
            surveillance = null;
        }
        if (audio != null && volumeOrigine >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeOrigine, 0); } catch (Exception ignored) { }
            volumeOrigine = -1;
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (silence != null) {
            try { silence.stop(); } catch (Exception ignored) { }
            silence.release();
            silence = null;
        }
        if (eveil != null) {
            if (eveil.isHeld()) eveil.release();
            eveil = null;
        }
    }

    @Override
    public void onDestroy() {
        toutArreter();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
