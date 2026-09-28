package com.ninja.splitlauncher;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

/**
 * Tuile des Réglages rapides : lance le profil actif en un tap.
 * Disponible à partir d'Android 7.0 (API 24).
 *
 * Correctif v1.3 : la tuile n'appelle PLUS {@code SplitController.launchSplit}
 * directement (startActivity depuis un service d'arrière-plan = bloqué sur
 * Android 14/15). Elle replie le panneau et ouvre {@link LaunchSplitActivity},
 * qui reste au premier plan le temps du lancement.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class SplitTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setLabel(getString(R.string.tile_label));
            tile.setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher));
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();

        // On ouvre LaunchSplitActivity (au premier plan) : elle gère l'absence de
        // profil (ouverture de MainActivity) et déclenche le split proprement.
        Intent launch = new Intent(this, LaunchSplitActivity.class);
        launch.setAction("com.ninja.splitlauncher.action.LAUNCH_PROFILE");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(launch);
        }
    }
}
