package dev.padhelper.fastcts;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;

/** ADB/system-only automation entry point, guarded by the manifest DUMP permission. */
public final class SetupCommandActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent command = getIntent();
        SharedPreferences preferences = getSharedPreferences("bar", MODE_PRIVATE);
        SharedPreferences.Editor edit = preferences.edit().remove("native_bar");
        if (command.getBooleanExtra("setup_circle", false))
            edit.remove(MainActivity.TARGET_COMPONENT).remove(MainActivity.TARGET_LABEL);
        if (command.hasExtra("setup_power")) edit.putBoolean(BarService.POWER, command.getBooleanExtra("setup_power", true));
        edit.apply();
        if (command.hasExtra("setup_boot_recovery"))
            BootRecovery.setActive(this, command.getBooleanExtra("setup_boot_recovery", false));
        if (command.hasExtra("setup_power_locked")) BarService.setLockedAssist(this, command.getBooleanExtra("setup_power_locked", false));
        String mode = command.getStringExtra("setup_mode");
        if (BarService.TOUCH.equals(mode) || BarService.NATIVE.equals(mode)) BarService.setMode(this, mode);
        boolean enable = command.getBooleanExtra("setup_enable", BarService.enabled(this));
        if (command.hasExtra("setup_enable")) {
            if (enable) BarService.start(this);
            else BarService.stop(this);
        }
        BarService.refreshConfiguration();
        startActivity(new Intent(this, SetupActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        if (enable && !BarService.granted(this))
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        finish();
    }
}
