package br.com.pokedream.pokedreamgrid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Dono do WebView do jogo. Vive no processo, nao na tela: a MainActivity so "empresta" o
 * WebView enquanto esta visivel, e o GameService mantem o processo vivo com a tela fechada.
 * Sem tela, o WebView fica escondido e o jogo entra no "Descanso" dele: continua farmando
 * e para de desenhar (o mesmo que o PokeDreamGrid do PC faz ao ir pra bandeja).
 *
 * Um Game por conta. A conta 1 usa o perfil padrao do WebView (o login de antes de existir
 * a segunda continua valendo); a conta 2 usa o perfil "conta2", com cookies e localStorage
 * proprios, como as particoes persist:contaN do PC.
 */
final class Game {
    static final String URL = "https://pokedream.com.br/";
    private static final long CICLO_MS = 60_000;          // leitura das estatisticas + teste de vida
    private static final int CICLOS_SEM_RESPOSTA = 3;     // ~3 min sem o JS responder = travou

    interface Ouvinte {
        void estado(Game g);                 // estatisticas/estado mudaram (notificacao)
        default void webViewTrocado(WebView novo) {}
    }

    static final int MAX_CONTAS = 2;
    private static final Game[] sGames = new Game[MAX_CONTAS];

    static Game get(Context c, int i) {
        if (sGames[i] == null) sGames[i] = new Game(c.getApplicationContext(), i);
        return sGames[i];
    }

    static Game atual(int i) { return sGames[i]; }

    /** Os jogos que estao rodando agora, na ordem das contas. */
    static java.util.List<Game> rodando() {
        java.util.List<Game> l = new java.util.ArrayList<>();
        for (Game g : sGames) if (g != null) l.add(g);
        return l;
    }

    /** O WebView do aparelho separa sessoes (Chrome/WebView 110+)? Sem isso, so da uma conta. */
    static boolean perfisSuportados() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
    }

    final Context app;
    final int indice;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableContextWrapper ctx;
    private WebView web;
    private Ouvinte servico, tela;

    // estado mostrado na notificacao
    volatile Stats stats = new Stats();
    volatile String problema = null;       // texto curto quando algo esta errado (sem rede, recarregando...)
    private int falhas = 0;
    private int semResposta = 0;
    private boolean esperandoResposta = false;
    private boolean visivel = false;

    private Game(Context app, int indice) {
        this.app = app;
        this.indice = indice;
        this.ctx = new MutableContextWrapper(app);
        criarWebView();
        app.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(rede);
        main.postDelayed(ciclo, 15_000);
    }

    private final ConnectivityManager.NetworkCallback rede = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                // voltou a rede depois de falhar: recarrega na hora em vez de esperar o backoff
                main.post(() -> {
                    if (falhas > 0) { log("rede voltou, recarregando"); recarregar(); }
                    else if ("Sem internet".equals(problema)) { problema = null; avisar(); }
                });
            }
            @Override public void onLost(Network n) {
                main.post(() -> { problema = "Sem internet"; avisar(); });
            }
    };

    WebView webView() { return web; }

    void setServico(Ouvinte o) { servico = o; }

    /** A tela pega o WebView pra mostrar. Contexto da Activity: dialogos/teclado do jogo funcionam. */
    void anexar(ViewGroup pai, Context activity, Ouvinte o) {
        tela = o;
        ctx.setBaseContext(activity);
        soltar();
        pai.addView(web, 0, new ViewGroup.LayoutParams(-1, -1));
        web.onResume();
        visivel = true;
        aplicarEco();
    }

    /** A tela sumiu: o WebView fica sem janela, a pagina vira "hidden" e o jogo vai pro Descanso. */
    void desanexar() {
        tela = null;
        visivel = false;
        web.onPause(); // so pausa animacao/midia e marca a pagina como escondida; o JS continua
        soltar();
        ctx.setBaseContext(app);
    }

    private void soltar() {
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
    }

    void recarregar() {
        problema = "Recarregando…";
        avisar();
        web.reload();
    }

    /** Joga fora o WebView (travado ou processo morto) e cria outro limpo. Sessao/login ficam (cookies/localStorage). */
    void recriar(String motivo) {
        log("recriando o jogo: " + motivo);
        ViewGroup pai = web.getParent() instanceof ViewGroup ? (ViewGroup) web.getParent() : null;
        soltar();
        try { web.destroy(); } catch (Throwable ignored) {}
        semResposta = 0;
        esperandoResposta = false;
        criarWebView();
        if (pai != null) {
            pai.addView(web, 0, new ViewGroup.LayoutParams(-1, -1));
        } else {
            web.onPause();
        }
        if (tela != null) tela.webViewTrocado(web);
        problema = "Reiniciando o jogo…";
        avisar();
    }

    void destruir() {
        try { app.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(rede); } catch (Exception ignored) {}
        main.removeCallbacksAndMessages(null);
        soltar();
        try { web.destroy(); } catch (Throwable ignored) {}
        if (sGames[indice] == this) sGames[indice] = null;
    }

    void aplicarEco() {
        // Eco so importa com a tela visivel: escondida, o navegador ja nao desenha nada
        boolean eco = Prefs.eco(app);
        web.evaluateJavascript(Scripts.eco(eco ? 15 : 0), null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void criarWebView() {
        web = new WebView(ctx);
        // o perfil tem que ser escolhido antes de qualquer outro uso do WebView
        CookieManager cookies = CookieManager.getInstance();
        if (indice > 0) {
            String perfil = "conta" + (indice + 1);
            WebViewCompat.setProfile(web, perfil);
            cookies = ProfileStore.getInstance().getOrCreateProfile(perfil).getCookieManager();
        }
        web.setBackgroundColor(Color.parseColor("#0d1117"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        // UA de Chrome comum (sem o "; wv" de WebView), como o PC faz com o token "Electron"
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true); // desafio do Cloudflare
        // escondido, o processo do jogo nao perde prioridade: o Android mata ele por ultimo
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new WebChromeClient() {
            // alert/confirm sem tela aberta derrubaria o app (dialogo sem janela): recusa
            @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) {
                if (tela == null) { r.cancel(); return true; }
                return false;
            }
            @Override public boolean onJsConfirm(WebView v, String u, String m, JsResult r) {
                if (tela == null) { r.cancel(); return true; }
                return false;
            }
        });
        web.loadUrl(URL);
    }

    private final class Cliente extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String h = u.getHost() == null ? "" : u.getHost();
            if (h.equals("pokedream.com.br") || h.endsWith(".pokedream.com.br") || h.endsWith("challenges.cloudflare.com")) return false;
            // link pra outro site abre no navegador, nunca no WebView logado
            try {
                app.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
            return true;
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (v != web || !url.startsWith(URL)) return;
            falhas = 0;
            problema = null;
            String[] c = Prefs.conta(app, indice);
            if (c != null) v.evaluateJavascript(Scripts.login(c[0], c[1]), null);
            v.evaluateJavascript(Scripts.GL_WATCHDOG, null);
            if (visivel) aplicarEco();
            avisar();
            main.postDelayed(() -> lerStats(), 5_000);
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (v != web || !r.isForMainFrame()) return;
            falhas++;
            // backoff: 5s, 10s, 20s... ate 2 min; a volta da rede (NetworkCallback) fura a fila
            long espera = Math.min(120_000L, 5_000L << Math.min(falhas - 1, 5));
            problema = "Sem conexão com o jogo, tentando de novo em " + (espera / 1000) + "s";
            log("erro ao carregar (" + e.getErrorCode() + " " + e.getDescription() + "), tentativa " + falhas);
            avisar();
            main.postDelayed(() -> { if (falhas > 0 && v == web) v.reload(); }, espera);
        }

        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            // processo do jogo morreu (falta de memoria ou crash): sem isso o app inteiro cairia
            if (v != web) return true;
            log("processo do jogo caiu" + (d.didCrash() ? " (crash)" : " (o Android liberou memoria)"));
            main.post(() -> recriar("processo do jogo caiu"));
            return true;
        }
    }

    /** A cada minuto: le as estatisticas da sessao e, de quebra, confere se o JS ainda responde. */
    private final Runnable ciclo = new Runnable() {
        @Override public void run() {
            if (esperandoResposta) {
                semResposta++;
                if (semResposta >= CICLOS_SEM_RESPOSTA) { recriar("jogo travado (sem responder há " + semResposta + " min)"); }
            }
            lerStats();
            main.postDelayed(this, CICLO_MS);
        }
    };

    private void lerStats() {
        esperandoResposta = true;
        final WebView alvo = web;
        alvo.evaluateJavascript(Scripts.STATS, valor -> {
            if (alvo != web) return;
            esperandoResposta = false;
            semResposta = 0;
            try {
                Object o = new JSONTokener(valor).nextValue();    // vem como string JSON dentro de JSON
                if (!(o instanceof String)) return;
                stats = Stats.de(new JSONObject((String) o), stats);
            } catch (Exception e) {
                log("stats ilegiveis: " + e);
            }
            avisar();
        });
    }

    String nome() { return "Conta " + (indice + 1); }

    private void log(String msg) { Diario.log(app, nome() + ": " + msg); }

    private void avisar() {
        if (servico != null) servico.estado(this);
        if (tela != null) tela.estado(this);
    }

    /** Snapshot da sessao do jogo (mesmos numeros do Analisador dele). */
    static final class Stats {
        boolean lido, deslogado;
        String hunt = "";
        long activeMs, kills, shiny, caught, xp, saldo;
        long ultimoKill;            // quando kills mudou pela ultima vez (relogio do aparelho)

        static Stats de(JSONObject j, Stats ant) {
            Stats s = new Stats();
            s.lido = true;
            s.deslogado = j.optBoolean("login");
            s.hunt = j.optString("hunt");
            s.activeMs = j.optLong("ms");
            s.kills = j.optLong("kills");
            s.shiny = j.optLong("shiny");
            s.caught = j.optLong("caught");
            s.xp = j.optLong("xp");
            s.saldo = j.optLong("bal");
            s.ultimoKill = (!ant.lido || s.kills != ant.kills) ? SystemClock.elapsedRealtime() : ant.ultimoKill;
            return s;
        }

        long minutosParado() { return lido ? (SystemClock.elapsedRealtime() - ultimoKill) / 60_000 : 0; }
    }
}
