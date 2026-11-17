package fr.volumeclicker;
import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class VolumeAccessibilityService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override protected boolean onKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if(key!=KeyEvent.KEYCODE_VOLUME_UP && key!=KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(!Counters.running(this)) return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)
            Counters.increment(this,key==KeyEvent.KEYCODE_VOLUME_UP);
        return true;
    }
}
