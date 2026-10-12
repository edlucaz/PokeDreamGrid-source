package br.com.pokedream.pokedreamgrid;

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

import java.util.Locale;

/**
 * Servico em primeiro plano: e o que impede o Android de matar o app minimizado ou com a tela
 * apagada. Segura um wake lock parcial (CPU acordada, tela pode apagar) e mostra as estatisticas
 * da sessao na notificacao fixa.
 */
public class GameService extends Service implements Game.Ouvinte {
    static final String ACAO_PARAR = "parar", ACAO_RECARREGAR = "recarregar", ACAO_CONTAS = "contas";
    static final String CANAL_STATUS = "status", CANAL_AVISOS = "avisos";
    private static final int ID_STATUS = 1, ID_DESLOGADO = 2;
    private static final long ATUALIZACAO_CADA = 12 * 60 * 60 * 1000L;

    private PowerManager.WakeLock wl;
    private NotificationManager nm;
    private final boolean[] avisouDeslogado = new boolean[Game.MAX_CONTAS];
    private String ultimoTexto = "";

    static void iniciar(Context c) {
        c.startForegroundService(new Intent(c, GameService.class));
    }

    /** Ligou/desligou a segunda conta nas opcoes: o servico abre ou fecha o jogo dela. */
    static void contasMudaram(Context c) {
        c.startService(new Intent(c, GameService.class).setAction(ACAO_CONTAS));
    }

    /** Segunda conta ligada e o WebView do aparelho sabe separar as sessoes. */
    static boolean duasContas(Context c) {
        return Prefs.duasContas(c) && Game.perfisSuportados();
    }

    static void canais(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel st = new NotificationChannel(CANAL_STATUS, "Jogo rodando", NotificationManager.IMPORTANCE_LOW);
        st.setDescription("Notificação fixa com as estatísticas da sessão");
        st.setShowBadge(false);
        nm.createNotificationChannel(st);
        nm.createNotificationChannel(new NotificationChannel(CANAL_AVISOS, "Avisos (deslogou, atualização)", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        canais(this);
        Notification n = montar();
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID_STATUS, n);

        wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PokeDreamGrid:jogo");
        wl.setReferenceCounted(false);
        wl.acquire();

        ligarContas();
        Diario.log(this, "servico iniciado" + (duasContas(this) ? " (2 contas)" : ""));
        Atualizador.checarSePrecisa(this, ATUALIZACAO_CADA);
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (ACAO_PARAR.equals(a)) {
            Diario.log(this, "desligado pelo usuario");
            MainActivity.fecharSeAberta();
            for (Game g : Game.rodando()) g.destruir();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACAO_RECARREGAR.equals(a)) {
            for (Game g : Game.rodando()) g.recarregar();
        }
        if (ACAO_CONTAS.equals(a)) ligarContas();
        if (i == null) Diario.log(this, "servico reiniciado pelo Android depois de ser encerrado");
        return START_STICKY; // o Android matou por memoria: ele mesmo reabre o servico (e o jogo) depois
    }

    /** Abre o jogo de cada conta ligada e fecha o da segunda se ela foi desligada. */
    private void ligarContas() {
        Game.get(this, 0).setServico(this);
        if (duasContas(this)) {
            Game.get(this, 1).setServico(this);
        } else if (Game.atual(1) != null) {
            Diario.log(this, "segunda conta desligada");
            Game.atual(1).destruir();
            nm.cancel(ID_DESLOGADO + 1);
            avisouDeslogado[1] = false;
            Prefs.contaNaTela(this, 0);
        }
        atualizarNotificacao();
    }

    @Override public void onDestroy() {
        for (Game g : Game.rodando()) g.setServico(null);
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void estado(Game g) {
        atualizarNotificacao();
        avisoDeslogado(g);
        Atualizador.checarSePrecisa(this, ATUALIZACAO_CADA);
    }

    private void atualizarNotificacao() {
        Notification n = montar();
        String t = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)) + n.extras.getCharSequence(Notification.EXTRA_TITLE);
        if (!t.equals(ultimoTexto)) { ultimoTexto = t; nm.notify(ID_STATUS, n); } // nao acorda a UI do sistema a toa
    }

    private PendingIntent pi(String acao) {
        Intent i = new Intent(this, GameService.class).setAction(acao);
        return PendingIntent.getService(this, acao.hashCode(), i, PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent abrir() { return abrir(-1); }

    /** Abre a tela; com conta >= 0, ja mostrando essa conta (aviso de deslogado). */
    private PendingIntent abrir(int conta) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONTA, conta);
        return PendingIntent.getActivity(this, conta + 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification montar() {
        java.util.List<Game> jogos = Game.rodando();
        String titulo, texto, grande = null;
        if (jogos.size() <= 1) {
            Game g = jogos.isEmpty() ? null : jogos.get(0);
            Game.Stats s = g == null ? null : g.stats;
            if (g == null || s == null || !s.lido) {
                titulo = "PokeDream";
                texto = g != null && g.problema != null ? g.problema : "Abrindo o jogo…";
            } else if (s.deslogado) {
                titulo = "PokeDream · deslogado";
                texto = Prefs.conta(this, g.indice) != null ? "Entrando de novo…" : "Toque para entrar na conta";
            } else {
                titulo = "PokeDream" + (s.hunt.isEmpty() ? "" : " · " + nomeHunt(s.hunt)) + " · " + duracao(s.activeMs);
                texto = resumo(s);
                grande = detalhes(g);
                if (g.problema != null) texto = g.problema;
            }
        } else {
            // duas contas: uma linha curta por conta no texto, os detalhes de cada uma ao expandir
            titulo = "PokeDream · " + jogos.size() + " contas";
            StringBuilder t = new StringBuilder(), b = new StringBuilder();
            for (Game g : jogos) {
                String linha = g.nome() + ": " + linhaCurta(g);
                if (t.length() > 0) { t.append(" | "); b.append("\n\n"); }
                t.append(linha);
                Game.Stats s = g.stats;
                if (s.lido && !s.deslogado) {
                    b.append(g.nome()).append(s.hunt.isEmpty() ? "" : " · " + nomeHunt(s.hunt)).append(" · ").append(duracao(s.activeMs)).append("\n");
                    b.append(detalhes(g));
                } else {
                    b.append(linha);
                }
            }
            texto = t.toString();
            grande = b.toString();
        }
        Notification.Builder nb = new Notification.Builder(this, CANAL_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(0xFFC07BF5)
            .addAction(new Notification.Action.Builder(null, "Recarregar", pi(ACAO_RECARREGAR)).build())
            .addAction(new Notification.Action.Builder(null, "Desligar", pi(ACAO_PARAR)).build());
        if (grande != null) nb.setStyle(new Notification.BigTextStyle().bigText(grande));
        if (Build.VERSION.SDK_INT >= 31) nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return nb.build();
    }

    private static String resumo(Game.Stats s) {
        double h = Math.max(s.activeMs, 60_000) / 3_600_000.0;
        return "XP/h " + num(s.xp / h) + " · Saldo/h " + sinal(s.saldo / h) + " · " + num(s.kills) + " kills";
    }

    private String linhaCurta(Game g) {
        Game.Stats s = g.stats;
        if (g.problema != null) return g.problema;
        if (!s.lido) return "abrindo…";
        if (s.deslogado) return Prefs.conta(this, g.indice) != null ? "deslogada, entrando de novo…" : "deslogada";
        double h = Math.max(s.activeMs, 60_000) / 3_600_000.0;
        return "XP/h " + num(s.xp / h) + " · Saldo/h " + sinal(s.saldo / h);
    }

    private static String detalhes(Game g) {
        Game.Stats s = g.stats;
        double h = Math.max(s.activeMs, 60_000) / 3_600_000.0;
        StringBuilder b = new StringBuilder();
        b.append("XP ").append(num(s.xp)).append("  (").append(num(s.xp / h)).append("/h)\n");
        b.append("Saldo ").append(sinal(s.saldo)).append("  (").append(sinal(s.saldo / h)).append("/h)\n");
        b.append("Kills ").append(num(s.kills)).append(" · capturas ").append(s.caught);
        if (s.shiny > 0) b.append(" · ✨ shiny ").append(s.shiny);
        long parado = s.minutosParado();
        if (parado >= 15) b.append("\n⏸ sem kills há ").append(parado).append(" min");
        if (g.problema != null) b.append("\n⚠ ").append(g.problema);
        return b.toString();
    }

    /** Deslogou e nao ha senha salva pro login automatico: avisa uma vez (com som) pra pessoa entrar. */
    private void avisoDeslogado(Game g) {
        int i = g.indice;
        boolean desl = g.stats.lido && g.stats.deslogado;
        if (!desl) { if (avisouDeslogado[i]) nm.cancel(ID_DESLOGADO + i); avisouDeslogado[i] = false; return; }
        if (avisouDeslogado[i] || Prefs.conta(this, i) != null) return;
        avisouDeslogado[i] = true;
        Diario.log(this, g.nome() + ": deslogada, sem senha salva");
        boolean duas = Game.rodando().size() > 1;
        nm.notify(ID_DESLOGADO + i, new Notification.Builder(this, CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(duas ? "PokeDream deslogou (" + g.nome() + ")" : "PokeDream deslogou")
            .setContentText("O jogo parou de progredir. Toque para entrar de novo.")
            .setContentIntent(abrir(i))
            .setAutoCancel(true)
            .build());
    }

    private static String nomeHunt(String h) {
        return h.isEmpty() ? h : Character.toUpperCase(h.charAt(0)) + h.substring(1).replace('_', ' ');
    }

    private static String duracao(long ms) {
        long m = ms / 60_000;
        return m >= 60 ? (m / 60) + "h" + String.format(Locale.ROOT, "%02d", m % 60) : m + "min";
    }

    static String num(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) return String.format(new Locale("pt", "BR"), "%.1fB", v / 1e9);
        if (a >= 1e6) return String.format(new Locale("pt", "BR"), "%.1fM", v / 1e6);
        if (a >= 1e4) return String.format(new Locale("pt", "BR"), "%.1fk", v / 1e3);
        return String.valueOf(Math.round(v));
    }

    private static String sinal(double v) { return (v > 0 ? "+" : "") + num(v); }
}
