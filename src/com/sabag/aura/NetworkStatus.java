package com.sabag.aura;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

/** Passive, lifecycle-bound connection status. Never scans or contacts an external host. */
final class NetworkStatus {
    interface Listener {
        void onChanged(State state);
    }

    static final class State {
        final boolean connected, wired, wifi;
        /** Null when Android redacts the Wi-Fi name, or this is not a Wi-Fi connection. */
        final String ssid;
        /** Transport/name fallback; callers may localize transport-only labels. */
        final String label;
        /** Signal level in [0, maxSignalLevel], or -1 when unavailable. */
        final int signalLevel, maxSignalLevel;

        State(boolean connected, boolean wired, boolean wifi, String ssid,
                int signalLevel, int maxSignalLevel) {
            this.connected = connected;
            this.wired = wired;
            this.wifi = wifi;
            this.ssid = ssid;
            this.label = !connected ? "" : wired ? "Ethernet" : wifi ? (ssid == null ? "Wi-Fi" : ssid) : "Network";
            this.signalLevel = signalLevel;
            this.maxSignalLevel = maxSignalLevel;
        }

        private boolean sameAs(State other) {
            return other != null && connected == other.connected && wired == other.wired
                    && wifi == other.wifi && signalLevel == other.signalLevel
                    && maxSignalLevel == other.maxSignalLevel && label.equals(other.label);
        }
    }

    private final Context context;
    private final ConnectivityManager connectivity;
    private final WifiManager wifiManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private ConnectivityManager.NetworkCallback callback;
    private Network defaultNetwork, lastLostNetwork;
    private boolean running, receiverRegistered, callbackWithLocation;
    private int generation;
    private State lastState;

    private final Runnable refreshTask = this::refresh;
    private final BroadcastReceiver connectionChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (running) refresh();
        }
    };

    NetworkStatus(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        connectivity = this.context.getSystemService(ConnectivityManager.class);
        wifiManager = this.context.getSystemService(WifiManager.class);
    }

    void start() {
        if (Looper.myLooper() != main.getLooper()) {
            main.post(this::start);
            return;
        }
        if (running) {
            refresh();
            return;
        }
        running = true;
        generation++;
        lastState = null;
        lastLostNetwork = null;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        try {
            // Wi-Fi broadcasts may originate in a privileged service UID. No broadcast
            // payload is trusted: every notification just rereads the framework state.
            context.registerReceiver(connectionChanged, filter, null, main,
                    android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0);
            receiverRegistered = true;
        } catch (SecurityException ignored) {
            // Default-network callbacks and the next lifecycle refresh still work.
        }
        registerCallback();
        readCurrentNetwork();
    }

    void stop() {
        if (Looper.myLooper() != main.getLooper()) {
            main.post(this::stop);
            return;
        }
        running = false;
        generation++;
        main.removeCallbacks(refreshTask);
        unregisterCallback();
        if (receiverRegistered) {
            try { context.unregisterReceiver(connectionChanged); }
            catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        defaultNetwork = null;
        lastLostNetwork = null;
        lastState = null;
    }

    /** Also safe after the optional name permission or the location switch changes. */
    void refresh() {
        if (Looper.myLooper() != main.getLooper()) {
            main.post(refreshTask);
            return;
        }
        if (!running) return;
        if (callbackWithLocation != wantLocation() || callback == null) {
            unregisterCallback();
            registerCallback();
        }
        readCurrentNetwork();
    }

    boolean hasSsidPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Permission alone is insufficient when Android's location switch is disabled. */
    boolean canReadSsid() {
        if (!hasSsidPermission()) return false;
        LocationManager locations = context.getSystemService(LocationManager.class);
        try { return locations != null && locations.isLocationEnabled(); }
        catch (RuntimeException ignored) { return false; }
    }

    private void registerCallback() {
        if (connectivity == null) return;
        // The flags constructor (location info in callbacks) exists from Android 12; on 11 the plain
        // callback already carries the Wi-Fi name when location permission is granted.
        callbackWithLocation = wantLocation();
        ConnectivityManager.NetworkCallback next = callbackWithLocation
                ? new Callback(generation, ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
                : new Callback(generation);
        callback = next;
        try {
            connectivity.registerDefaultNetworkCallback(next, main);
        } catch (RuntimeException ignored) {
            callback = null;
            callbackWithLocation = false;
        }
    }

    private boolean wantLocation() {
        return android.os.Build.VERSION.SDK_INT >= 31 && canReadSsid();
    }

    /** Two constructors: the flags one exists only from Android 12 and is never called below it. */
    private final class Callback extends ConnectivityManager.NetworkCallback {
        private final int thisGeneration;

        Callback(int generation) {
            super();
            thisGeneration = generation;
        }

        Callback(int generation, int flags) {
            super(flags);
            thisGeneration = generation;
        }

        private boolean current() {
            return running && generation == thisGeneration && callback == this;
        }

        @Override public void onAvailable(Network network) {
            if (!current()) return;
            defaultNetwork = network;
            lastLostNetwork = null;
            // Android immediately follows this with ordered capabilities. Querying
            // synchronous capabilities here can race a network transition.
        }

        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (!current() || network.equals(lastLostNetwork)) return;
            if (defaultNetwork != null && !network.equals(defaultNetwork)) return;
            defaultNetwork = network;
            publish(caps);
        }

        @Override public void onLost(Network network) {
            if (!current() || !network.equals(defaultNetwork)) return;
            defaultNetwork = null;
            lastLostNetwork = network;
            // Reread after queued transition callbacks, so a new Ethernet/Wi-Fi
            // default does not get replaced with an obsolete offline state.
            main.removeCallbacks(refreshTask);
            main.post(refreshTask);
        }
    }

    private void unregisterCallback() {
        ConnectivityManager.NetworkCallback old = callback;
        callback = null;
        if (old == null || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(old); }
        catch (RuntimeException ignored) { }
    }

    private void readCurrentNetwork() {
        Network network = null;
        NetworkCapabilities caps = null;
        try {
            if (connectivity != null) {
                network = connectivity.getActiveNetwork();
                if (network != null && !network.equals(lastLostNetwork)) {
                    caps = connectivity.getNetworkCapabilities(network);
                }
            }
        } catch (RuntimeException ignored) { }
        defaultNetwork = caps == null ? null : network;
        publish(caps);
    }

    @SuppressWarnings("deprecation") // Some TV firmware omits WifiInfo from NetworkCapabilities.
    private void publish(NetworkCapabilities caps) {
        if (!running) return;
        if (caps == null) {
            deliver(new State(false, false, false, null, -1, 4));
            return;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            deliver(new State(true, true, false, null, -1, 4));
            return;
        }
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            deliver(new State(true, false, false, null, -1, 4));
            return;
        }

        WifiInfo info = null;
        TransportInfo transport = caps.getTransportInfo();
        if (transport instanceof WifiInfo) info = (WifiInfo) transport;
        WifiInfo fallback = null;
        try {
            if (wifiManager != null) fallback = wifiManager.getConnectionInfo();
        } catch (RuntimeException ignored) { }
        if (info == null) info = fallback;

        int level = -1, maximum = 4;
        try {
            if (wifiManager != null) {
                maximum = Math.max(1, wifiManager.getMaxSignalLevel());
                int rssi = info == null ? -127 : info.getRssi();
                if ((rssi <= -127 || rssi >= 0) && fallback != null) rssi = fallback.getRssi();
                if (rssi > -127 && rssi < 0) level = Math.max(0, Math.min(maximum, wifiManager.calculateSignalLevel(rssi)));
            }
        } catch (RuntimeException ignored) { }

        String ssid = null;
        if (canReadSsid()) {
            ssid = cleanSsid(info == null ? null : info.getSSID());
            if (ssid == null && fallback != null) ssid = cleanSsid(fallback.getSSID());
        }
        deliver(new State(true, false, true, ssid, level, maximum));
    }

    private void deliver(State state) {
        if (!running || state.sameAs(lastState)) return;
        lastState = state;
        listener.onChanged(state);
    }

    private static String cleanSsid(String raw) {
        if (raw == null || raw.isEmpty() || WifiManager.UNKNOWN_SSID.equalsIgnoreCase(raw)) return null;
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.isEmpty() || WifiManager.UNKNOWN_SSID.equalsIgnoreCase(raw) ? null : raw;
    }
}
