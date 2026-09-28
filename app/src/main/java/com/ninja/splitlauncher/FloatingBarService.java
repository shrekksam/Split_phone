package com.ninja.splitlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Service de premier plan qui affiche une « barre flottante » (overlay)
 * permettant de relancer le Split à tout moment, par-dessus n'importe quelle
 * application.
 *
 * Correctifs v1.2 :
 *   - bouton « Arrêter » dans la notification (envoie ACTION_HIDE) ;
 *   - position mémorisée ET bornée à l'écran ;
 *   - sur redémarrage START_STICKY, la permission overlay est REVÉRIFIÉE avant
 *     de réafficher la barre.
 */
public class FloatingBarService extends Service {

    public static final String ACTION_SHOW = "com.ninja.splitlauncher.SHOW_BAR";
    public static final String ACTION_HIDE = "com.ninja.splitlauncher.HIDE_BAR";

    private static final String CHANNEL_ID = "split_floating_channel";
    private static final int NOTIF_ID = 4242;

    private static final String POS_PREFS = "floating_pos";
    private static final String K_X = "x";
    private static final String K_Y = "y";

    private WindowManager windowManager;
    private View barView;
    private WindowManager.LayoutParams params;
    private ProfileManager profile;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        profile = new ProfileManager(this);
        createNotificationChannel();
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_HIDE.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Redémarrage système (START_STICKY, intent == null) ou démarrage normal :
        // on revérifie la permission overlay avant tout affichage.
        if (!WindowManagerHelper.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (barView == null) {
            showBar();
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Action « Arrêter » : envoie ACTION_HIDE au service.
        Intent hide = new Intent(this, FloatingBarService.class);
        hide.setAction(ACTION_HIDE);
        PendingIntent stopPi = PendingIntent.getService(this, 1, hide,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(0, getString(R.string.notif_action_stop), stopPi)
                .build();
    }

    // ------------------------------------------------------------------ OVERLAY

    private void showBar() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        barView = LayoutInflater.from(this).inflate(R.layout.floating_bar, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        // Position mémorisée (bornée à l'écran).
        SharedPreferences pos = getSharedPreferences(POS_PREFS, MODE_PRIVATE);
        params.x = pos.getInt(K_X, 40);
        params.y = pos.getInt(K_Y, 240);
        clampToScreen();

        TextView splitBtn = barView.findViewById(R.id.floatingSplit);
        splitBtn.setOnClickListener(v -> launchProfileSplit());

        TextView closeBtn = barView.findViewById(R.id.floatingClose);
        closeBtn.setOnClickListener(v -> stopSelf());

        enableDrag();

        try {
            windowManager.addView(barView, params);
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void launchProfileSplit() {
        // Garde anti-taps : on ignore un nouveau tap tant qu'un lancement est en cours.
        if (SplitEngine.isLaunching()) {
            android.widget.Toast.makeText(this, getString(R.string.toast_launch_in_progress),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        AppInfo a = profile.getAppA(this);
        AppInfo b = profile.getAppB(this);
        if (a == null || b == null) {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
            return;
        }
        SplitController.launchSplit(this, a, b, profile.isLandscape(),
                profile.getRatio(), null);
    }

    /** Rend la barre déplaçable par glissement (position bornée à l'écran). */
    private void enableDrag() {
        barView.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int) (event.getRawX() - touchX);
                        params.y = startY + (int) (event.getRawY() - touchY);
                        clampToScreen();
                        if (windowManager != null && barView != null) {
                            windowManager.updateViewLayout(barView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        savePosition();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Borne la position de la barre à l'intérieur de l'écran. */
    private void clampToScreen() {
        if (windowManager == null || params == null) return;
        int screenW, screenH;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = windowManager.getCurrentWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
        int viewW = (barView != null && barView.getWidth() > 0)
                ? barView.getWidth() : 160;
        int viewH = (barView != null && barView.getHeight() > 0)
                ? barView.getHeight() : 56;

        if (params.x < 0) params.x = 0;
        if (params.y < 0) params.y = 0;
        if (params.x > screenW - viewW) params.x = Math.max(0, screenW - viewW);
        if (params.y > screenH - viewH) params.y = Math.max(0, screenH - viewH);
    }

    private void savePosition() {
        if (params == null) return;
        getSharedPreferences(POS_PREFS, MODE_PRIVATE).edit()
                .putInt(K_X, params.x)
                .putInt(K_Y, params.y)
                .apply();
    }

    @Override
    public void onDestroy() {
        savePosition();
        if (windowManager != null && barView != null) {
            try {
                windowManager.removeView(barView);
            } catch (Exception ignored) {}
            barView = null;
        }
        super.onDestroy();
    }
}
