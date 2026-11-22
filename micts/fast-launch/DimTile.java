package dev.padhelper.fastcts;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Quick settings "딥 다크 모드": Android's Extra Dim (reduce bright colors) on/off, written with this app's
 * WRITE_SECURE_SETTINGS (device: secure reduce_bright_colors_activated 1/0 switched ColorDisplayService on/off).
 * The state is read back from the display service, so a change made elsewhere shows correctly.
 */
public final class DimTile extends TileService {
    @Override public void onStartListening() { show(Boolean.TRUE.equals(Tweaks.extraDim(this))); }

    @Override public void onClick() {
        boolean on = Boolean.TRUE.equals(Tweaks.extraDim(this));
        // The display service applies the setting asynchronously (device: reading right after the write still returned
        // the old state), so the tile shows the value just written instead of reading it back.
        if (Tweaks.setExtraDim(this, !on)) show(!on);
        else Toast.makeText(this, "보안 설정 쓰기 권한이 필요합니다.\n앱의 최초 ADB 설정 안내를 확인해주세요.", Toast.LENGTH_LONG).show();
    }

    private void show(boolean on) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
