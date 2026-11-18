package ir.maniimani.driveplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Keeps the music going across ignition off/on.
 *
 * When the car is switched off the head unit sends ENTER_SLEEP, then force-stops every
 * user-installed app. A force-stopped app gets no BOOT_COMPLETED or MEDIA_MOUNTED, but the
 * vendor's own wake-up broadcasts (EXIT_SLEEP, CAR_BOOT_COMPLETED, CAR_APP_INIT_COMPLETED)
 * are sent with FLAG_INCLUDE_STOPPED_PACKAGES, so they still reach us.
 *
 * On ENTER_SLEEP we note whether music should continue: it was playing, or it was paused by
 * the head unit itself in the last minute (not by the driver). On wake-up we resume if so.
 */
public class SleepReceiver extends BroadcastReceiver {
    static final String KEY_RESUME = "resumeAfterSleep";

    static final String ENTER_SLEEP = "android.car.action.ENTER_SLEEP";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences("player", Context.MODE_PRIVATE);
        String a = intent == null ? null : intent.getAction();

        if (ENTER_SLEEP.equals(a)) {
            boolean wasPlaying = p.getBoolean("wasPlaying", false);
            boolean wanted = p.getBoolean("intent", wasPlaying);
            long lossAt = p.getLong("lossAt", 0);
            boolean recent = lossAt == 0 || System.currentTimeMillis() - lossAt < 60_000;
            p.edit().putBoolean(KEY_RESUME, wanted && recent).commit();
            return;
        }

        // woke up, booted, or a USB stick / SD card was mounted
        if (!p.getBoolean(KEY_RESUME, false) && !p.getBoolean("wasPlaying", false)) return;
        Intent s = new Intent(context, PlayerService.class).setAction(PlayerService.ACTION_RESUME_IF_WAS_PLAYING);
        try {
            context.startForegroundService(s);
        } catch (Throwable ignored) {
        }
    }
}
