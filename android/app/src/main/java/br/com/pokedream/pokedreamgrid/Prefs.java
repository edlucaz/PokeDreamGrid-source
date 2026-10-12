package br.com.pokedream.pokedreamgrid;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Preferencias. A conta do login automatico fica cifrada com chave do Android Keystore (nunca sai do aparelho). */
final class Prefs {
    private Prefs() {}

    private static final String CHAVE = "pokedreamgrid-conta";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static boolean eco(Context c) { return sp(c).getBoolean("eco", true); }
    static void eco(Context c, boolean on) { sp(c).edit().putBoolean("eco", on).apply(); }

    static boolean jaPerguntouBateria(Context c) { return sp(c).getBoolean("bateria", false); }
    static void perguntouBateria(Context c) { sp(c).edit().putBoolean("bateria", true).apply(); }

    static long ultimaChecagem(Context c) { return sp(c).getLong("upd", 0); }
    static void ultimaChecagem(Context c, long t) { sp(c).edit().putLong("upd", t).apply(); }

    /** Segunda conta ligada (so funciona com WebView que tem MULTI_PROFILE). */
    static boolean duasContas(Context c) { return sp(c).getBoolean("duas", false); }
    static void duasContas(Context c, boolean on) { sp(c).edit().putBoolean("duas", on).apply(); }

    /** Qual conta a tela mostra (0 ou 1). */
    static int contaNaTela(Context c) { return sp(c).getInt("tela", 0); }
    static void contaNaTela(Context c, int i) { sp(c).edit().putInt("tela", i).apply(); }

    /** Chave da conta i: a 1 continua em "conta" (de antes de existir a segunda). */
    private static String chaveConta(int i) { return i == 0 ? "conta" : "conta" + (i + 1); }

    /** {email, senha} da conta i, ou null se nao houver conta salva. */
    static String[] conta(Context c, int i) {
        String v = sp(c).getString(chaveConta(i), null);
        if (v == null) return null;
        try {
            byte[] tudo = Base64.decode(v, Base64.NO_WRAP);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, chave(), new GCMParameterSpec(128, tudo, 0, 12));
            String s = new String(ci.doFinal(tudo, 12, tudo.length - 12), StandardCharsets.UTF_8);
            int q = s.indexOf('\n');
            return q < 0 ? null : new String[]{s.substring(0, q), s.substring(q + 1)};
        } catch (Exception e) {
            Diario.log(c, "conta salva ilegivel: " + e);
            return null;
        }
    }

    static void conta(Context c, int i, String email, String senha) {
        if (email == null || email.isEmpty() || senha == null || senha.isEmpty()) {
            sp(c).edit().remove(chaveConta(i)).apply();
            return;
        }
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, chave());
            byte[] iv = ci.getIV();
            byte[] ct = ci.doFinal((email + "\n" + senha).getBytes(StandardCharsets.UTF_8));
            byte[] tudo = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, tudo, 0, iv.length);
            System.arraycopy(ct, 0, tudo, iv.length, ct.length);
            sp(c).edit().putString(chaveConta(i), Base64.encodeToString(tudo, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            Diario.log(c, "nao foi possivel salvar a conta: " + e);
        }
    }

    private static SecretKey chave() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(CHAVE)) return (SecretKey) ks.getKey(CHAVE, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(CHAVE, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return kg.generateKey();
    }
}
