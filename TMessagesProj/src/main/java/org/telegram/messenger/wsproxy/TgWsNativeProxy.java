package org.telegram.messenger.wsproxy;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

final class TgWsNativeProxy {

    private interface ProxyLibrary extends Library {
        ProxyLibrary INSTANCE = Native.load("tgwsproxy", ProxyLibrary.class);

        int StartProxy(String host, int port, String dcIps, String secret, int verbose);
        int StopProxy();
        void SetPoolSize(int size);
        void SetCfProxyCacheDir(String cacheDir);
        void SetCfProxyConfig(int enabled, int priority, String userDomain);
        Pointer GetStats();
        void FreeString(Pointer pointer);
    }

    private TgWsNativeProxy() {
    }

    static int startProxy(String host, int port, String dcIps, String secret, boolean verbose) {
        return ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, verbose ? 1 : 0);
    }

    static int stopProxy() {
        return ProxyLibrary.INSTANCE.StopProxy();
    }

    static void setPoolSize(int size) {
        ProxyLibrary.INSTANCE.SetPoolSize(size);
    }

    static void setCfProxyCacheDir(String cacheDir) {
        ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir);
    }

    static void setCfProxyConfig(boolean enabled, boolean priority, String userDomain) {
        ProxyLibrary.INSTANCE.SetCfProxyConfig(enabled ? 1 : 0, priority ? 1 : 0, userDomain == null ? "" : userDomain);
    }

    static String getStats() {
        Pointer pointer = ProxyLibrary.INSTANCE.GetStats();
        if (pointer == null) {
            return null;
        }
        try {
            return pointer.getString(0);
        } finally {
            ProxyLibrary.INSTANCE.FreeString(pointer);
        }
    }
}
