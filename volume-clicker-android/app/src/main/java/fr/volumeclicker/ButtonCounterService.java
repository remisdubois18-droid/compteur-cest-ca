package fr.volumeclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

public class ButtonCounterService extends Service {
    public static final String START="fr.volumeclicker.action.START";
    public static final String STOP="fr.volumeclicker.action.STOP";
    private static final String CHANNEL="volume-clicker-counter";
    private static final int NOTIFICATION_ID=17;
    private MediaSession mediaSession;
    private VolumeProvider volumeProvider;

    @Override public void onCreate() { super.onCreate(); }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && STOP.equals(intent.getAction())) {
            Counters.setRunning(this,false);
            releaseMediaSession();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(intent==null && !Counters.running(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Counters.setRunning(this,true);
        try {
            showForegroundNotification();
            startMediaSession();
        } catch(RuntimeException e) {
            Counters.setRunning(this,false);
            releaseMediaSession();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void showForegroundNotification() {
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Compteur de boutons",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Comptage volontaire des touches Volume + et Volume -");
            manager.createNotificationChannel(channel);
        }
        int immutable=Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|immutable);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ButtonCounterService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|immutable);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification notification=b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Volume Clicker actif")
            .setContentText("Les boutons de volume sont surveillés. Touchez pour ouvrir.")
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Arrêter",stop).build())
            .build();
        if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID,notification);
    }

    private void startMediaSession() {
        if(mediaSession!=null) return;
        mediaSession=new MediaSession(this,"VolumeClickerButtons");
        volumeProvider=new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE,100,50) {
            @Override public void onAdjustVolume(int direction) {
                if(direction==AudioManager.ADJUST_RAISE) Counters.increment(ButtonCounterService.this,true);
                else if(direction==AudioManager.ADJUST_LOWER) Counters.increment(ButtonCounterService.this,false);
            }
        };
        // Experimental: Android can route system volume events to an active
        // remote-volume media session, including on some locked-screen devices.
        // This is not an actual audio player and can affect media volume routing.
        mediaSession.setPlaybackToRemote(volumeProvider);
        mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING,PlaybackState.PLAYBACK_POSITION_UNKNOWN,1.0f)
            .build());
        mediaSession.setActive(true);
    }

    private void releaseMediaSession() {
        if(mediaSession!=null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession=null;
        }
        volumeProvider=null;
    }

    @Override public void onDestroy() {
        releaseMediaSession();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {return null;}
}
