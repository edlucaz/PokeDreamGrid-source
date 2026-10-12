package br.com.pokedream.pokedreamgrid;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Procura APK novo nas Releases do GitHub (mesmo repo do PC). O APK precisa se chamar
 * PokeDreamGrid-android-<versionCode>.apk; achou um maior que o instalado, avisa e o toque
 * abre o download no navegador (o Android pergunta se quer instalar por cima).
 */
final class Atualizador {
    private Atualizador() {}

    private static final String API = "https://api.github.com/repos/edlucaz/PokeDreamGrid-source/releases?per_page=20";
    private static final Pattern NOME = Pattern.compile("^PokeDreamGrid-android-(\\d+)\\.apk$");
    private static final int ID_NOTIF = 3;
    private static volatile boolean rodando;

    static void checarSePrecisa(Context c, long intervalo) {
        long agora = System.currentTimeMillis();
        if (agora - Prefs.ultimaChecagem(c) < intervalo) return;
        Prefs.ultimaChecagem(c, agora);
        checar(c, false);
    }

    static void checar(Context c, boolean manual) {
        if (rodando) return;
        rodando = true;
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try {
                android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                @SuppressWarnings("deprecation")
                int instalado = android.os.Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
                int melhor = 0;
                String url = null;
                JSONArray rels = new JSONArray(baixar(API));
                for (int i = 0; i < rels.length(); i++) {
                    JSONObject r = rels.getJSONObject(i);
                    if (r.optBoolean("draft")) continue;
                    JSONArray as = r.optJSONArray("assets");
                    for (int j = 0; as != null && j < as.length(); j++) {
                        JSONObject a = as.getJSONObject(j);
                        Matcher m = NOME.matcher(a.optString("name"));
                        if (m.matches() && Integer.parseInt(m.group(1)) > melhor) {
                            melhor = Integer.parseInt(m.group(1));
                            url = a.optString("browser_download_url");
                        }
                    }
                }
                if (melhor > instalado && url != null) {
                    notificar(app, url);
                    msg = "Versão nova disponível: veja a notificação";
                } else {
                    msg = "Você já está na versão mais recente (" + Diario.versao(app) + ")";
                }
            } catch (Exception e) {
                Diario.log(app, "checagem de atualizacao falhou: " + e);
                msg = "Não foi possível checar atualização: " + e.getMessage();
            } finally {
                rodando = false;
            }
            if (manual) {
                String m = msg;
                main.post(() -> Toast.makeText(app, m, Toast.LENGTH_LONG).show());
            }
        }, "atualizador").start();
    }

    private static void notificar(Context c, String url) {
        PendingIntent pi = PendingIntent.getActivity(c, 7,
            new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE);
        c.getSystemService(NotificationManager.class).notify(ID_NOTIF,
            new Notification.Builder(c, GameService.CANAL_AVISOS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Atualização do PokeDreamGrid")
                .setContentText("Toque para baixar e instalar a versão nova")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    private static String baixar(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(15_000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "PokeDreamGrid-Android");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
