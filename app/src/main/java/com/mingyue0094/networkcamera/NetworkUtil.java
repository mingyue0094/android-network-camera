package com.mingyue0094.networkcamera;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

public final class NetworkUtil {
    private NetworkUtil() {}

    public static String getWifiIp(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "0.0.0.0";
            WifiInfo info = wm.getConnectionInfo();
            int ip = info.getIpAddress();
            return (ip & 0xff) + "." +
                    ((ip >> 8) & 0xff) + "." +
                    ((ip >> 16) & 0xff) + "." +
                    ((ip >> 24) & 0xff);
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }
}
