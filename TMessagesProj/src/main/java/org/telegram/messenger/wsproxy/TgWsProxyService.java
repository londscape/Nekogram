package org.telegram.messenger.wsproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

public class TgWsProxyService extends Service {

    public static final String ACTION_START = "org.telegram.messenger.wsproxy.START";
    public static final String ACTION_STOP = "org.telegram.messenger.wsproxy.STOP";
    public static final String ACTION_RESTART = "org.telegram.messenger.wsproxy.RESTART";
    public static final String EXTRA_BIND_IP = "bind_ip";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_DC_IPS = "dc_ips";
    public static final String EXTRA_SECRET = "secret";
    public static final String EXTRA_POOL_SIZE = "pool_size";
    public static final String EXTRA_CF_ENABLED = "cf_enabled";
    public static final String EXTRA_CF_DOMAIN = "cf_domain";

    private static final int NOTIFICATION_ID = 1443;
    private static final String CHANNEL_ID = "tg_ws_proxy";
    private static final long WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L;

    private static volatile boolean running;

    private PowerManager.WakeLock wakeLock;
    private String lastBindIp = TgWsProxyBootstrap.DEFAULT_BIND_IP;
    private int lastPort = TgWsProxyBootstrap.DEFAULT_PORT;
    private String lastDcIps = "";
    private String lastSecret = "";
    private int lastPoolSize = 4;
    private boolean lastCfEnabled = true;
    private String lastCfDomain = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopProxy();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            stopNativeProxy();
            startProxy(lastBindIp, lastPort, lastDcIps, lastSecret, lastPoolSize, lastCfEnabled, lastCfDomain);
            return START_REDELIVER_INTENT;
        }
        if (intent == null || ACTION_START.equals(action)) {
            String bindIp = intent != null ? intent.getStringExtra(EXTRA_BIND_IP) : null;
            String dcIps = intent != null ? intent.getStringExtra(EXTRA_DC_IPS) : null;
            String secret = intent != null ? intent.getStringExtra(EXTRA_SECRET) : null;
            String cfDomain = intent != null ? intent.getStringExtra(EXTRA_CF_DOMAIN) : null;
            int port = intent != null ? intent.getIntExtra(EXTRA_PORT, TgWsProxyBootstrap.DEFAULT_PORT) : TgWsProxyBootstrap.DEFAULT_PORT;
            int poolSize = intent != null ? intent.getIntExtra(EXTRA_POOL_SIZE, 4) : 4;
            boolean cfEnabled = intent == null || intent.getBooleanExtra(EXTRA_CF_ENABLED, true);
            startProxy(
                    bindIp != null ? bindIp : TgWsProxyBootstrap.DEFAULT_BIND_IP,
                    port,
                    dcIps != null ? dcIps : "",
                    secret != null ? secret : "",
                    poolSize,
                    cfEnabled,
                    cfDomain != null ? cfDomain : ""
            );
        }
        return START_REDELIVER_INTENT;
    }

    private synchronized void startProxy(String bindIp, int port, String dcIps, String secret, int poolSize, boolean cfEnabled, String cfDomain) {
        lastBindIp = bindIp;
        lastPort = port;
        lastDcIps = dcIps;
        lastSecret = secret;
        lastPoolSize = poolSize;
        lastCfEnabled = cfEnabled;
        lastCfDomain = cfDomain;

        startForegroundCompat(getString(R.string.TgWsProxyStarting));
        acquireWakeLock();

        if (running) {
            updateNotification(getString(R.string.TgWsProxyRunning));
            return;
        }
        running = true;

        Thread thread = new Thread(() -> {
            try {
                TgWsNativeProxy.setPoolSize(poolSize);
                TgWsNativeProxy.setCfProxyCacheDir(getCacheDir().getAbsolutePath());
                TgWsNativeProxy.setCfProxyConfig(cfEnabled, true, cfDomain);
                int result = TgWsNativeProxy.startProxy(bindIp, port, dcIps, secret, true);
                if (result == 0) {
                    FileLog.d("TG WS Proxy started on " + bindIp + ":" + port);
                    updateNotification(getString(R.string.TgWsProxyRunning));
                } else {
                    running = false;
                    FileLog.e("TG WS Proxy start failed, code " + result);
                    updateNotification(getString(R.string.TgWsProxyStartFailed, result));
                    stopSelf();
                }
            } catch (Throwable throwable) {
                running = false;
                FileLog.e(throwable);
                updateNotification(getString(R.string.TgWsProxyStartError));
                stopSelf();
            }
        }, "TgWsProxyStart");
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void stopProxy() {
        stopNativeProxy();
        releaseWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
        stopSelf();
    }

    private void stopNativeProxy() {
        if (!running) {
            return;
        }
        running = false;
        Thread thread = new Thread(() -> {
            try {
                TgWsNativeProxy.stopProxy();
                FileLog.d("TG WS Proxy stopped");
            } catch (Throwable throwable) {
                FileLog.e(throwable);
            }
        }, "TgWsProxyStop");
        thread.setDaemon(true);
        thread.start();
    }

    private void startForegroundCompat(String text) {
        Notification notification = createNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }

    private Notification createNotification(String text) {
        Intent openIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (openIntent == null) {
            openIntent = new Intent();
        }
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent restartPendingIntent = PendingIntent.getService(
                this,
                2,
                new Intent(this, TgWsProxyService.class).setAction(ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                3,
                new Intent(this, TgWsProxyService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.TgWsProxyTitle))
                .setContentText(text)
                .setSmallIcon(R.drawable.tgwsproxy_notification)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_popup_sync, getString(R.string.TgWsProxyRestart), restartPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.TgWsProxyStop), stopPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.TgWsProxyChannel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            releaseWakeLock();
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nekogram:TgWsProxy");
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        } catch (Throwable throwable) {
            FileLog.e(throwable);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable throwable) {
            FileLog.e(throwable);
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
