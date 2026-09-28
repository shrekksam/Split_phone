package com.ninja.splitlauncher;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Notifications de l'application.
 *
 * Correctif v1.3 : sur Android 14/15, le lancement d'activité depuis un
 * BroadcastReceiver (démarrage au boot) est strictement restreint. On propose
 * donc un REPLI par notification « Appuyer pour lancer le split » : le tap ouvre
 * {@link LaunchSplitActivity} (au premier plan), ce qui est toujours autorisé.
 */
public final class NotificationHelper {

    private static final String CHANNEL_BOOT = "split_boot_channel";
    private static final int NOTIF_BOOT = 4243;

    private NotificationHelper() {}

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_BOOT) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_BOOT,
                        ctx.getString(R.string.notif_boot_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
    }

    /** Publie la notification de repli « appuyer pour lancer le split ». */
    public static void postBootLaunch(Context ctx) {
        ensureChannel(ctx);

        Intent launch = new Intent(ctx, LaunchSplitActivity.class);
        launch.setAction("com.ninja.splitlauncher.action.LAUNCH_PROFILE");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_BOOT)
                .setContentTitle(ctx.getString(R.string.notif_boot_title))
                .setContentText(ctx.getString(R.string.notif_boot_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_BOOT, b.build());
            } catch (Throwable ignored) {}
        }
    }
}
