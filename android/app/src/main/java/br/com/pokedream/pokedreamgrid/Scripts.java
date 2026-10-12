package br.com.pokedream.pokedreamgrid;

import org.json.JSONObject;

/** JS injetado na pagina do jogo. Mesmos scripts do PokeDreamGrid do PC (index.html). */
final class Scripts {
    private Scripts() {}

    /** Modo Eco: limita o requestAnimationFrame do jogo (idle nao precisa de 60 fps). 0 = sem limite. */
    static String eco(int fps) {
        return "(() => {"
            + "if (!window.__eco) {"
            + "  window.__eco = { native: window.requestAnimationFrame.bind(window), fps: 0, last: 0 };"
            + "  window.requestAnimationFrame = (cb) => {"
            + "    const s = window.__eco;"
            + "    if (!s.fps) return s.native(cb);"
            + "    const wait = Math.max(0, (1000 / s.fps) - (performance.now() - s.last));"
            + "    return setTimeout(() => { s.last = performance.now(); s.native(cb); }, wait);"
            + "  };"
            + "}"
            + "window.__eco.fps = " + fps + ";"
            + "})()";
    }

    /**
     * Login automatico: a PokeDream e uma SPA de rota unica (deslogar nao navega), entao um vigia
     * continuo preenche e envia o formulario sempre que ele aparecer. Seletores vem do bundle do jogo.
     */
    static String login(String email, String senha) {
        return "(() => {"
            + "if (window.__pgLoginWatch) return; window.__pgLoginWatch = true;"
            + "const EMAIL = " + JSONObject.quote(email) + ", SENHA = " + JSONObject.quote(senha) + ";"
            + "const setVal = (el, val) => {"
            + "  const st = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;"
            + "  st.call(el, val);"
            + "  el.dispatchEvent(new Event('input', { bubbles: true }));"
            + "  el.dispatchEvent(new Event('change', { bubbles: true }));"
            + "};"
            + "const findBtn = () => document.querySelector('.auth-submit') || [...document.querySelectorAll('button')].find(b => b.type === 'submit');"
            + "let cooldown = 0;"
            + "setInterval(() => {"
            + "  if (Date.now() < cooldown) return;"
            + "  const inputs = [...document.querySelectorAll('input')];"
            + "  const u = inputs.find(i => i.autocomplete === 'email');"
            + "  const p = inputs.find(i => i.autocomplete === 'current-password');"
            + "  if (!u || !p) {"
            // tela inicial (antes do formulario): botao "Jogar"/"Entrar" abre o login
            + "    const pre = document.querySelector('.auth-prelogin-actions .primary, .auth-prelogin-head-actions button');"
            + "    if (pre) { pre.click(); return; }"
            + "    if (!document.querySelector('input[autocomplete=new-password]')) return;"
            + "    const tab = [...document.querySelectorAll('.auth-tab')].find(x => /entrar|login|log ?in/i.test(x.textContent || ''));"
            + "    if (tab) tab.click();"
            + "    return;"
            + "  }"
            + "  const b = findBtn();"
            + "  if (!b) return;"
            + "  if (u.value !== EMAIL) setVal(u, EMAIL);"
            + "  if (p.value !== SENHA) setVal(p, SENHA);"
            + "  if (u.value === EMAIL && p.value === SENHA && !b.disabled) {"
            + "    b.click();"
            + "    cooldown = Date.now() + 15000;" // senha errada nao vira loop de envio
            + "  }"
            + "}, 2000);"
            + "})()";
    }

    /** WebGL perdeu o contexto e nao voltou em 4s: o jogo congela com a pagina viva, entao recarrega. */
    static final String GL_WATCHDOG =
        "(()=>{if(window.__pgGlWd)return;window.__pgGlWd=1;let tm;"
        + "document.addEventListener('webglcontextlost',()=>{clearTimeout(tm);tm=setTimeout(()=>{if(!document.hidden)location.reload()},4000);},true);"
        + "document.addEventListener('webglcontextrestored',()=>clearTimeout(tm),true);})()";

    /**
     * Le a sessao que o jogo persiste em localStorage ('pokeidle:v1' -> state.session). Saldo usa a
     * mesma conta do Analisador do jogo: (loot + gold + autovenda + capturas) - suprimentos.
     */
    static final String STATS =
        "(()=>{try{"
        // telas de autenticacao (inicial, login, registro) tem o rodape .auth-legal
        + "const login=!!document.querySelector('.auth-legal, .auth-start, input[autocomplete=current-password]');"
        + "const raw=localStorage.getItem('pokeidle:v1');"
        + "if(!raw)return JSON.stringify({login});"
        + "const st=JSON.parse(raw).state||{},s=st.session||{},n=k=>+s[k]||0;"
        + "const inc=n('lootValue')+n('goldDrops')+n('autoSellValue')+n('capturedValue');"
        + "return JSON.stringify({login,hunt:st.hunt||'',ms:n('activeMs'),kills:n('kills'),shiny:n('shinyKills'),"
        + "caught:n('caught'),xp:n('xp'),bal:inc-n('suppliesCost')});"
        + "}catch(e){return JSON.stringify({err:String(e)})}})()";
}
