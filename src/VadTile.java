package com.mylauncher;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class VadTile extends TileService {
    void upd() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean open = getSharedPreferences("l", 0).getBoolean("open", false);
        t.setState(open ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Vad");
        t.updateTile();
    }

    @Override
    public void onStartListening() { upd(); }

    @Override
    public void onClick() {
        Tile t = getQsTile();
        if (t != null) { t.setState(Tile.STATE_ACTIVE); t.updateTile(); }
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
