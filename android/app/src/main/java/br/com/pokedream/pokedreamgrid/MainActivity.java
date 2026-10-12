package br.com.pokedream.pokedreamgrid;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Tela do jogo. So mostra o WebView de uma conta por vez (botoes 1/2 trocam); quem mantem os
 * jogos rodando e o GameService. A conta que nao esta na tela fica no Descanso, farmando.
 */
public class MainActivity extends Activity implements Game.Ouvinte {
    static final String EXTRA_CONTA = "conta";
    private static WeakReference<MainActivity> sAberta = new WeakReference<>(null);
    private FrameLayout raiz;
    private LinearLayout barra;
    private final TextView[] botoesConta = new TextView[Game.MAX_CONTAS];
    private int naTela;
    private boolean visivel;

    static void fecharSeAberta() {
        MainActivity a = sAberta.get();
        if (a != null) a.finishAndRemoveTask();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sAberta = new WeakReference<>(this);
        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF0D1117);
        setContentView(raiz);
        // Android 15 desenha atras das barras do sistema e do teclado: o jogo recua o que elas ocupam
        if (Build.VERSION.SDK_INT >= 30) {
            raiz.setOnApplyWindowInsetsListener((v, ins) -> {
                Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        raiz.addView(barra());
        naTela = GameService.duasContas(this) ? Prefs.contaNaTela(this) : 0;
        pedirConta(getIntent());

        GameService.canais(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            pedirBateria();
        }
        GameService.iniciar(this);
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        pedirBateria();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        pedirConta(i);
        if (visivel) mostrar(naTela);
    }

    /** Notificacao de "deslogou" de uma conta: abre ja nela. */
    private void pedirConta(Intent i) {
        int c = i == null ? -1 : i.getIntExtra(EXTRA_CONTA, -1);
        if (c >= 0 && (c == 0 || GameService.duasContas(this))) naTela = c;
    }

    @Override protected void onStart() {
        super.onStart();
        visivel = true;
        mostrar(naTela);
    }

    @Override protected void onStop() {
        visivel = false;
        Game g = Game.atual(naTela);
        if (g != null) g.desanexar();
        super.onStop();
    }

    /** Poe a conta i na tela; a que estava volta pro Descanso (escondida, continua farmando). */
    private void mostrar(int i) {
        Game antes = Game.atual(naTela);
        if (antes != null && naTela != i) antes.desanexar();
        naTela = i;
        Prefs.contaNaTela(this, i);
        Game.get(this, i).anexar(raiz, this, this);
        atualizarBarra();
    }

    @Override protected void onDestroy() {
        if (sAberta.get() == this) sAberta.clear();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        Game g = Game.atual(naTela);
        WebView w = g == null ? null : g.webView();
        if (w != null && w.canGoBack()) { w.goBack(); return; }
        moveTaskToBack(true); // "voltar" so minimiza: fechar a tela nao pode parar o jogo
    }

    @Override public void estado(Game g) {}

    @Override public void webViewTrocado(WebView novo) {
        novo.onResume();
    }

    // ---------- Opcoes ----------

    /** Canto de baixo: engrenagem e, com duas contas, os botoes 1/2 que trocam a conta na tela. */
    private View barra() {
        barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(6), 0, 0, dp(6));
        barra.setLayoutParams(lp);
        TextView op = botaoRedondo("⚙", 18);
        op.setContentDescription("Opções do PokeDreamGrid");
        op.setOnClickListener(v -> opcoes());
        barra.addView(op);
        for (int i = 0; i < Game.MAX_CONTAS; i++) {
            final int conta = i;
            TextView b = botaoRedondo(String.valueOf(i + 1), 14);
            b.setContentDescription("Mostrar a conta " + (i + 1));
            ((LinearLayout.LayoutParams) b.getLayoutParams()).leftMargin = dp(6);
            b.setOnClickListener(v -> { if (conta != naTela) mostrar(conta); });
            botoesConta[i] = b;
            barra.addView(b);
        }
        return barra;
    }

    private TextView botaoRedondo(String texto, int tamanho) {
        TextView b = new TextView(this);
        b.setText(texto);
        b.setTextSize(tamanho);
        b.setTextColor(0xFFE6EDF3);
        b.setGravity(Gravity.CENTER);
        b.setBackground(fundoRedondo(false));
        b.setAlpha(0.55f);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return b;
    }

    private GradientDrawable fundoRedondo(boolean ativo) {
        GradientDrawable f = new GradientDrawable();
        f.setShape(GradientDrawable.OVAL);
        f.setColor(ativo ? 0xCC6E40C9 : 0xCC161B22);
        f.setStroke(dp(1), ativo ? 0xFFC07BF5 : 0xFF30363D);
        return f;
    }

    /** Botoes 1/2 so aparecem com a segunda conta ligada; o da conta na tela fica destacado. */
    private void atualizarBarra() {
        boolean duas = GameService.duasContas(this);
        for (int i = 0; i < botoesConta.length; i++) {
            botoesConta[i].setVisibility(duas ? View.VISIBLE : View.GONE);
            botoesConta[i].setBackground(fundoRedondo(i == naTela));
            botoesConta[i].setAlpha(i == naTela ? 0.85f : 0.55f);
        }
    }

    private void opcoes() {
        boolean eco = Prefs.eco(this);
        boolean duas = GameService.duasContas(this);
        List<String> itens = new ArrayList<>();
        List<Runnable> acoes = new ArrayList<>();
        for (int i = 0; i < (duas ? 2 : 1); i++) {
            final int conta = i;
            String estado = Prefs.conta(this, i) != null ? "ligado (alterar)" : "desligado (configurar)";
            itens.add((duas ? "Conta " + (i + 1) + " · login" : "Login") + " automático: " + estado);
            acoes.add(() -> dialogoConta(conta));
        }
        itens.add("Segunda conta: " + (duas ? "ligada (desligar)" : "desligada (ligar)"));
        acoes.add(() -> alternarSegundaConta(!duas));
        itens.add("Modo Eco (15 fps com a tela aberta): " + (eco ? "ligado" : "desligado"));
        acoes.add(() -> {
            Prefs.eco(this, !eco);
            for (Game g : Game.rodando()) g.aplicarEco();
        });
        itens.add("Liberar segundo plano (bateria)");
        acoes.add(() -> abrirBateria(true));
        itens.add(duas ? "Recarregar a conta " + (naTela + 1) : "Recarregar o jogo");
        acoes.add(() -> Game.get(this, naTela).recarregar());
        itens.add("Verificar atualização");
        acoes.add(() -> Atualizador.checar(this, true));
        itens.add("Enviar relatório de erros");
        acoes.add(this::enviarRelatorio);
        itens.add("Desligar (parar de farmar)");
        acoes.add(() -> startService(new Intent(this, GameService.class).setAction(GameService.ACAO_PARAR)));
        new AlertDialog.Builder(this)
            .setTitle("PokeDreamGrid " + Diario.versao(this))
            .setItems(itens.toArray(new String[0]), (d, i) -> acoes.get(i).run())
            .show();
    }

    private void alternarSegundaConta(boolean ligar) {
        if (ligar && !Game.perfisSuportados()) {
            new AlertDialog.Builder(this)
                .setTitle("Segunda conta indisponível")
                .setMessage("O WebView deste aparelho é antigo e não consegue separar duas sessões do jogo.\n\n"
                    + "Atualize o \"Android System WebView\" (ou o Chrome) na Play Store e tente de novo.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        // tira a conta 2 da tela antes do servico fechar o jogo dela
        if (!ligar && naTela != 0) mostrar(0);
        Prefs.duasContas(this, ligar);
        GameService.contasMudaram(this);
        if (ligar) {
            mostrar(1);
            Toast.makeText(this, "Conta 2 aberta: entre com a outra conta. As duas continuam farmando; use 1/2 para trocar.", Toast.LENGTH_LONG).show();
        } else {
            atualizarBarra();
        }
    }

    private void dialogoConta(int conta) {
        String[] c = Prefs.conta(this, conta);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        EditText email = new EditText(this);
        email.setHint("E-mail");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText senha = new EditText(this);
        senha.setHint("Senha");
        senha.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (c != null) { email.setText(c[0]); senha.setText(c[1]); }
        TextView ajuda = new TextView(this);
        ajuda.setText("Se a conta deslogar (queda do servidor, sessão expirada), o app entra de novo sozinho. A senha fica cifrada só neste aparelho.");
        ajuda.setTextSize(12);
        ajuda.setPadding(0, dp(8), 0, 0);
        l.addView(email);
        l.addView(senha);
        l.addView(ajuda);
        new AlertDialog.Builder(this)
            .setTitle(GameService.duasContas(this) ? "Login automático · conta " + (conta + 1) : "Login automático")
            .setView(l)
            .setPositiveButton("Salvar", (d, w) -> {
                Prefs.conta(this, conta, email.getText().toString().trim(), senha.getText().toString());
                boolean salvo = Prefs.conta(this, conta) != null;
                Toast.makeText(this, salvo ? "Salvo. Vale a partir do próximo carregamento." : "Login automático desligado", Toast.LENGTH_LONG).show();
                if (salvo) Game.get(this, conta).recarregar();
            })
            .setNeutralButton("Apagar", (d, w) -> Prefs.conta(this, conta, null, null))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    /** Primeira vez: explica e pede pra tirar o app da otimizacao de bateria (senao o Android pode congelar o jogo). */
    private void pedirBateria() {
        if (Prefs.jaPerguntouBateria(this) || semRestricaoBateria()) return;
        Prefs.perguntouBateria(this);
        new AlertDialog.Builder(this)
            .setTitle("Rodar em segundo plano")
            .setMessage("Para o jogo continuar progredindo com o celular bloqueado, o Android precisa deixar o PokeDreamGrid fora da economia de bateria.\n\n"
                + "Com a tela fechada o jogo para de desenhar e fica no modo Descanso, gastando pouco.\n\n"
                + "Na próxima tela, toque em \"Permitir\".")
            .setPositiveButton("Continuar", (d, w) -> abrirBateria(false))
            .setNegativeButton("Agora não", null)
            .show();
    }

    private boolean semRestricaoBateria() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    @SuppressLint("BatteryLife")
    private void abrirBateria(boolean manual) {
        if (semRestricaoBateria()) {
            if (manual) {
                // ja liberado; alguns fabricantes (Xiaomi, Samsung...) tem uma trava a mais nas configuracoes do app
                Toast.makeText(this, "Já liberado. Se ainda fechar, procure \"Bateria\" > \"Sem restrições\" nas configurações do app.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void enviarRelatorio() {
        Intent i = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Relatório do PokeDreamGrid Android")
            .putExtra(Intent.EXTRA_TEXT, Diario.ler(this));
        startActivity(Intent.createChooser(i, "Enviar relatório"));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
