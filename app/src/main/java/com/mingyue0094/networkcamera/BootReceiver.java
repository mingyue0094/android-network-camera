package com.mingyue0094.networkcamera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // If this app is already the default Home/Launcher, Android will
        // start MainActivity itself. Do not start a second instance here,
        // otherwise both instances try to bind HTTP port 8080.
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        ResolveInfo ri = context.getPackageManager().resolveActivity(home, 0);
        if (ri != null && ri.activityInfo != null
                && context.getPackageName().equals(ri.activityInfo.packageName)) {
            return;
        }

        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
