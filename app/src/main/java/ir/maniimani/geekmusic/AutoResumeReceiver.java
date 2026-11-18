package ir.maniimani.geekmusic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Car turned on / USB stick plugged in: if music was playing when the car turned off,
 * carry on from the same second. Does nothing otherwise.
 */
public class AutoResumeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("wasPlaying", false)) return;
        Intent s = new Intent(context, PlayerService.class).setAction(PlayerService.ACTION_RESUME_IF_WAS_PLAYING);
        try {
            context.startForegroundService(s);
        } catch (Throwable ignored) {
        }
    }
}
