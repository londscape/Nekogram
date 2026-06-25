package org.telegram.messenger.wsproxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.security.SecureRandom;

public final class TgWsProxyBootstrap {

    public static final String DEFAULT_BIND_IP = "127.0.0.1";
    public static final int DEFAULT_PORT = 1443;

    private static final String SETTINGS = "tg_ws_proxy";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_CF_ENABLED = "cf_enabled";
    private static final String KEY_CF_DOMAIN = "cf_domain";
    private static final String KEY_POOL_SIZE = "pool_size";
    private static final String KEY_DC_IPS = "dc_ips";

    private TgWsProxyBootstrap() {
    }

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences settings = appContext.getSharedPreferences(SETTINGS, Activity.MODE_PRIVATE);
        if (!settings.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        String secret = ensureSecret(settings);
        applyTelegramProxy(appContext, secret);
        new android.os.Handler(appContext.getMainLooper()).postDelayed(
                () -> startService(appContext, settings, secret),
                2500
        );
    }

    private static void startService(Context context, SharedPreferences settings, String secret) {
        Intent intent = new Intent(context, TgWsProxyService.class)
                .setAction(TgWsProxyService.ACTION_START)
                .putExtra(TgWsProxyService.EXTRA_BIND_IP, DEFAULT_BIND_IP)
                .putExtra(TgWsProxyService.EXTRA_PORT, DEFAULT_PORT)
                .putExtra(TgWsProxyService.EXTRA_DC_IPS, settings.getString(KEY_DC_IPS, ""))
                .putExtra(TgWsProxyService.EXTRA_SECRET, secret)
                .putExtra(TgWsProxyService.EXTRA_POOL_SIZE, settings.getInt(KEY_POOL_SIZE, 4))
                .putExtra(TgWsProxyService.EXTRA_CF_ENABLED, settings.getBoolean(KEY_CF_ENABLED, true))
                .putExtra(TgWsProxyService.EXTRA_CF_DOMAIN, settings.getString(KEY_CF_DOMAIN, ""));

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable throwable) {
            FileLog.e(throwable);
        }
    }

    private static void applyTelegramProxy(Context context, String secret) {
        String proxySecret = "dd" + secret;
        try {
            SharedConfig.loadProxyList();
            SharedConfig.ProxyInfo proxyInfo = SharedConfig.addProxy(
                    new SharedConfig.ProxyInfo(DEFAULT_BIND_IP, DEFAULT_PORT, "", "", proxySecret)
            );
            SharedConfig.currentProxy = proxyInfo;
            SharedConfig.saveProxyList();

            SharedPreferences preferences = context.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
            preferences.edit()
                    .putString("proxy_ip", DEFAULT_BIND_IP)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", proxySecret)
                    .putInt("proxy_port", DEFAULT_PORT)
                    .putBoolean("proxy_enabled", true)
                    .putBoolean("proxy_enabled_calls", true)
                    .apply();

        } catch (Throwable throwable) {
            FileLog.e(throwable);
        }
    }

    private static String ensureSecret(SharedPreferences settings) {
        String secret = settings.getString(KEY_SECRET, "");
        if (isValidSecret(secret)) {
            return secret;
        }

        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        secret = builder.toString();
        settings.edit().putString(KEY_SECRET, secret).apply();
        return secret;
    }

    private static boolean isValidSecret(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
