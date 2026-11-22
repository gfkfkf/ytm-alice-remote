package ru.ytmalice.remote;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Scans the local network for a running ytm-alice web panel.
 * Tries alice.lan / alice.local first, then the /24 around every local IPv4 address.
 */
final class StationFinder {

    interface Callback {
        void onResult(List<String> urls);
    }

    private static final int PORT = 24721;
    private static final int CONNECT_TIMEOUT_MS = 700;
    private static final int READ_TIMEOUT_MS = 900;
    private static final int THREADS = 32;

    private StationFinder() {
    }

    static void find(Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final List<String> found = scan();
            main.post(() -> callback.onResult(found));
        }, "ytm-alice-finder").start();
    }

    private static List<String> scan() {
        Set<String> targets = new LinkedHashSet<>();
        targets.add("http://alice.lan:" + PORT);
        targets.add("http://alice.local:" + PORT);
        targets.addAll(localSubnetHosts());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> futures = new ArrayList<>();
        for (final String candidate : targets) {
            futures.add(pool.submit((Callable<String>) () -> probe(candidate) ? candidate : null));
        }
        pool.shutdown();

        Set<String> found = new LinkedHashSet<>();
        try {
            pool.awaitTermination(25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Future<String> future : futures) {
            try {
                String value = future.get(200, TimeUnit.MILLISECONDS);
                if (value != null) {
                    found.add(value);
                }
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        return new ArrayList<>(found);
    }

    private static boolean probe(String base) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/info").openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return false;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buffer = new char[512];
                int read;
                while ((read = reader.read(buffer)) != -1 && body.length() < 2048) {
                    body.append(buffer, 0, read);
                }
            }
            return body.toString().contains("\"version\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Set<String> localSubnetHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                    InetAddress address = ia.getAddress();
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) {
                        continue;
                    }
                    byte[] octets = address.getAddress();
                    int own = octets[3] & 0xFF;
                    String prefix = (octets[0] & 0xFF) + "." + (octets[1] & 0xFF) + "."
                            + (octets[2] & 0xFF) + ".";
                    for (int last = 1; last <= 254; last++) {
                        if (last == own) {
                            continue;
                        }
                        hosts.add("http://" + prefix + last + ":" + PORT);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return hosts;
    }
}
