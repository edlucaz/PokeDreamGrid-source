package br.com.pokedream.pokedreamgrid;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Relatorio de erros/eventos (quedas, recargas, rede) pra pessoa mandar quando algo der errado. */
final class Diario {
    private Diario() {}

    private static final long LIMITE = 256 * 1024;
    private static final SimpleDateFormat F = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT);

    private static File arq(Context c) { return new File(c.getFilesDir(), "relatorio.log"); }

    static synchronized void log(Context c, String msg) {
        try {
            File f = arq(c);
            if (f.length() > LIMITE) f.renameTo(new File(c.getFilesDir(), "relatorio.antigo.log"));
            try (FileWriter w = new FileWriter(f, true)) {
                w.write("[" + F.format(new Date()) + "] " + msg + "\n");
            }
        } catch (Exception ignored) {}
    }

    static synchronized String ler(Context c) {
        String cab = "PokeDreamGrid Android " + versao(c) + " · Android " + Build.VERSION.RELEASE
            + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n";
        try {
            byte[] b = Files.readAllBytes(arq(c).toPath());
            int ini = Math.max(0, b.length - 60_000); // o fim e o que interessa
            return cab + new String(b, ini, b.length - ini, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return cab + "Nenhum evento registrado ainda.";
        }
    }

    static String versao(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }
}
