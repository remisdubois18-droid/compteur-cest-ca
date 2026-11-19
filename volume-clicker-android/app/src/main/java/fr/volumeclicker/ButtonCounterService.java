package fr.volumeclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.VolumeProvider;
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
 * Écran éteint, Android n'envoie les touches de volume qu'à l'application qui joue du son.
 * Le service joue donc en boucle un son totalement silencieux, et déclare une « session
 * multimédia » dont il gère lui-même le volume : chaque appui arrive ici au lieu de changer le volume.
 */
public class ButtonCounterService extends Service {
    public static final String START = "fr.volumeclicker.action.START";
    public static final String STOP = "fr.volumeclicker.action.STOP";
    private static final String CANAL = "compteur-volume";
    private static final int NOTIFICATION_ID = 17;

    private MediaSession session;
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
        silence.write(new short[images], 0, images);
        silence.setLoopPoints(0, images, -1);
        silence.play();
    }

    // ------------------------------------------------------------------ session multimédia

    private void demarrerSession() {
        if (session != null) return;
        session = new MediaSession(this, "CompteurVolume");
        VolumeProvider volume = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override
            public void onAdjustVolume(int direction) {
                if (direction == AudioManager.ADJUST_RAISE || direction == AudioManager.ADJUST_LOWER) {
                    if (Counters.increment(ButtonCounterService.this)) {
                        vibrer();
                        majNotification();
                    }
                }
            }
        };
        session.setPlaybackToRemote(volume);
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .build());
        session.setActive(true);
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
