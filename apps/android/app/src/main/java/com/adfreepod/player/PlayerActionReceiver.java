package com.adfreepod.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PlayerActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        MainActivity.handlePlaybackActionFromNotification(intent.getAction());
    }
}
