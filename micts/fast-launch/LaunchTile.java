package dev.padhelper.fastcts;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Stateless quick settings launch buttons (0.3.6): "Search" opens Circle to Search, "Assistant" opens Gemini Assistant.
 * Both go through MainActivity so the shade collapses first. Circle to Search never runs over the keyguard, so a locked
 * Search tap gets the system's unlock prompt first; a locked Assistant tap does too unless the locked-screen option allows it.
 */
public abstract class LaunchTile extends TileService {
    abstract int source();

    @Override public void onStartListening() {
        BarService.releaseOrphanedPowerKeys(this); // like BarTile: any visible tile of this app undoes a force-stop leftover
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override public void onClick() {
        if (source() == MainActivity.TILE_ASSISTANT && isLocked() && BarService.lockedAssist(this)) {
            BarService.tileAssistant(this); // over the keyguard: GeminiSession sends gesture-only flags, no screen content
            return;
        }
        // Locked: startActivityAndCollapse asks for the unlock itself and starts the activity afterwards. unlockAndRun
        // lost the launch on ColorOS (device: its runnable fired while SystemUI still showed the keyguard, which opened
        // the bouncer a second time and dropped the PendingIntent).
        launch();
    }

    private void launch() {
        Intent intent = new Intent(this, MainActivity.class).putExtra(MainActivity.SOURCE, source())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, source(), intent, PendingIntent.FLAG_IMMUTABLE));
        else startActivityAndCollapse(intent);
    }

    public static final class Search extends LaunchTile { @Override int source() { return MainActivity.TILE_SEARCH; } }
    public static final class Assistant extends LaunchTile { @Override int source() { return MainActivity.TILE_ASSISTANT; } }
}
