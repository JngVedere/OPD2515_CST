package dev.padhelper.fastcts;

import android.app.PendingIntent;
import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.*;
import android.os.Build;
import android.os.Handler;

public final class BarTile extends TileService {
    @Override public void onStartListening() { BarService.releaseOrphanedPowerKeys(this); refresh(); }

    @Override public void onClick() {
        if (!BarService.granted(this)) {
            Intent setup = new Intent(this, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, setup, PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(setup);
        } else if (BarService.running) BarService.stop(this);
        else BarService.start(this);
        new Handler(getMainLooper()).postDelayed(this::refresh, 250);
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(BarService.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setSubtitle(BarService.running ? "사용 중" : "꺼짐");
            tile.updateTile();
        }
    }
}
