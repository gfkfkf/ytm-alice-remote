package ru.ytmalice.remote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class TrackWidget extends AppWidgetProvider {

    static final String ACTION_PLAY_PAUSE = "ru.ytmalice.remote.WIDGET_PLAY_PAUSE";
    static final String ACTION_NEXT = "ru.ytmalice.remote.WIDGET_NEXT";
    static final String ACTION_PREV = "ru.ytmalice.remote.WIDGET_PREV";
    static final String ACTION_REFRESH = "ru.ytmalice.remote.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            final int widgetId = id;
            final Context ctx = context.getApplicationContext();
            final AppWidgetManager mgr = appWidgetManager;
            new Thread(() -> updateWidget(ctx, mgr, widgetId)).start();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (action == null) return;
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, TrackWidget.class));
            for (int id : ids) handleAction(context, mgr, id, action);
            ids = mgr.getAppWidgetIds(new ComponentName(context, TrackWidgetCompact.class));
            for (int id : ids) handleAction(context, mgr, id, action);
            ids = mgr.getAppWidgetIds(new ComponentName(context, TrackWidgetLarge.class));
            for (int id : ids) handleAction(context, mgr, id, action);
        } else {
            handleAction(context, mgr, widgetId, action);
        }
    }

    static void handleAction(Context context, AppWidgetManager mgr, int id, String action) {
        String url = getServerUrl(context);
        if (url == null) { updateWidget(context, mgr, id); return; }
        new Thread(() -> {
            try {
                if (ACTION_PLAY_PAUSE.equals(action)) sendCommand(url, "toggle");
                else if (ACTION_NEXT.equals(action)) sendCommand(url, "next");
                else if (ACTION_PREV.equals(action)) sendCommand(url, "previous");
            } catch (Exception ignored) {}
            updateWidget(context, mgr, id);
        }).start();
    }

    static int layoutFor(Context context, int id) {
        try {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id);
            if (info != null) {
                String cls = info.provider.getClassName();
                if (cls != null) {
                    if (cls.endsWith("Compact")) return R.layout.widget_compact;
                    if (cls.endsWith("Large")) return R.layout.widget_large;
                }
            }
        } catch (Exception ignored) {}
        return R.layout.widget_track;
    }

    static String getServerUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("ytm_alice_remote", Context.MODE_PRIVATE);
        String u = prefs.getString("server_url", null);
        if (u == null || u.isEmpty()) return null;
        return u;
    }

    static void sendCommand(String base, String action) throws Exception {
        URL url = new URL(base + "/api/command");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Ytm-Client", "web");
        c.setDoOutput(true);
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        String body = "{\"action\":\"" + action + "\"}";
        c.getOutputStream().write(body.getBytes("UTF-8"));
        c.getResponseCode();
        c.disconnect();
    }

    static void updateWidget(Context context, AppWidgetManager mgr, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), layoutFor(context, id));
        String url = getServerUrl(context);
        if (url == null) {
            rv.setTextViewText(R.id.widget_title, "ytm-alice");
            rv.setTextViewText(R.id.widget_artist, "Настройте адрес в приложении");
        } else {
            try {
                URL u = new URL(url + "/api/state");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                if (c.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject state = json.optJSONObject("state");
                    if (state != null) {
                        JSONArray q = state.optJSONArray("queue");
                        int idx = state.optInt("index", 0);
                        String title = "Нет трека";
                        String artist = "ytm-alice";
                        if (q != null && idx >= 0 && idx < q.length()) {
                            JSONObject t = q.optJSONObject(idx);
                            if (t != null) {
                                title = t.optString("title", title);
                                artist = t.optString("artists", artist);
                            }
                        }
                        rv.setTextViewText(R.id.widget_title, title);
                        rv.setTextViewText(R.id.widget_artist, artist);
                        String status = state.optString("status", "");
                        boolean playing = status.equals("playing") || status.equals("buffering");
                        rv.setTextViewText(R.id.widget_play, playing ? "\u23F8" : "\u25B6");
                    }
                }
                c.disconnect();
            } catch (Exception e) {
                rv.setTextViewText(R.id.widget_title, "Нет связи");
                rv.setTextViewText(R.id.widget_artist, "Проверьте сеть");
            }
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, openPi);

        Intent ppIntent = new Intent(context, TrackWidget.class);
        ppIntent.setAction(ACTION_PLAY_PAUSE);
        ppIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent ppPi = PendingIntent.getBroadcast(context, id * 10 + 1, ppIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_play, ppPi);

        Intent nextIntent = new Intent(context, TrackWidget.class);
        nextIntent.setAction(ACTION_NEXT);
        nextIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent nextPi = PendingIntent.getBroadcast(context, id * 10 + 2, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_next, nextPi);

        Intent prevIntent = new Intent(context, TrackWidget.class);
        prevIntent.setAction(ACTION_PREV);
        prevIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent prevPi = PendingIntent.getBroadcast(context, id * 10 + 3, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_prev, prevPi);

        mgr.updateAppWidget(id, rv);
    }
}
