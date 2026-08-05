<div align="center">

<img src="tray.png" width="72" alt="PokeDreamGrid">

# PokeDreamGrid

**Até quatro contas de [PokeDream](https://pokedream.com.br/) em uma janela só.**

</div>

> Variação do [PokeGrid](https://github.com/soufoka/PokeGrid-source) (feito originalmente pra Poke Idle World) adaptada pro PokeDream. Mesma base de Electron: paineis com sessão separada, login automático, Modo Eco, anti-sono, bandeja e watchdog de queda.

## Status: MVP, não testado ao vivo

Esta primeira versão foi montada por análise estática do bundle JS/CSS do PokeDream (`main-*.js`/`main-*.css`), sem acesso de navegador pra logar e conferir visualmente. Os seletores usados (`input[autocomplete=email]`, `input[autocomplete=current-password]`, `.auth-submit`, `.auth-tab`, `.chat`, `.sidebar`) vêm direto do bundle publicado, mas **precisam ser confirmados rodando o app de verdade**. Se algo não bater, é provável que o jogo tenha mudado esses nomes desde a análise — abra uma conta de teste, veja o que quebrou e ajuste.

## O que tem

- 1 a 4 contas rodando ao mesmo tempo, cada uma com sessão isolada (`persist:conta1..4`).
- Login automático: preenche e-mail/senha e envia assim que o formulário de entrar aparecer. Roda em vigia contínuo (a PokeDream é uma SPA de rota única, não navega de página ao deslogar), então cobre login inicial e sessão caída.
- Senhas criptografadas no PC (`safeStorage` do Electron), nunca saem daqui.
- Modo Eco: derruba o `requestAnimationFrame` do jogo pra 15fps (idle não precisa de 60).
- "Limpar tela": esconde `.chat`/`.sidebar` do jogo (desligado por padrão — teste antes de deixar ligado, pode esconder navegação junto com o chat).
- Watchdog: recarrega painel que travou, notifica (SO + webhook Discord opcional) se uma conta cair de vez.
- Anti-sono, minimizar pra bandeja, atalhos Ctrl+1..4 (expandir painel) e Ctrl+M (mudo).
- Atualização automática: checa as Releases do GitHub ao abrir e a cada 6h, baixa sozinho e pergunta quando reiniciar pra instalar. Dá pra checar na mão em ☰ Opções → 🔄 Verificar atualizações (ou no menu da bandeja). Só funciona no app empacotado (build instalado), não em `npm start`.

## O que NÃO tem (por escolha, veja o motivo)

O PokeGrid original tem calculadora de IV, "Modo Simples" (dashboard numérico) e "Modo Cartas", todos construídos lendo o estado interno específico do Poke Idle World (WebSocket, endpoints, formato de item/pokémon). O PokeDream já tem calculadora e bolsa próprias na interface dele, então essa camada não foi replicada — reduz risco de sair quebrado e evita duplicar o que o próprio jogo já oferece.

## Como rodar

Precisa de Node.js (LTS, [nodejs.org](https://nodejs.org)).

```bash
bash iniciar.sh
```

Ou manualmente: `npm install` e depois `npm start`.

## Publicando uma atualização

O app consulta `github.com/edlucaz/PokeDreamGrid-source` (repositório público) via `electron-updater` (configurado em `package.json` > `build.publish`). Como o repo é público, a checagem de update não precisa de nenhum token em tempo de execução — só quem publica precisa se autenticar. Pra soltar uma versão nova:

1. Suba a versão em `package.json` (`"version"`).
2. Gere um [token do GitHub](https://github.com/settings/tokens) com escopo `repo` e exporte `GH_TOKEN=seu_token`.
3. Rode `npm run release` (Linux/AppImage) e/ou `npm run release:win` (Windows/NSIS) — isso builda, cria a Release no GitHub (com a tag da versão) e sobe os instaladores + o manifesto (`latest.yml`/`latest-linux.yml`) que o autoUpdater lê pra saber que há versão nova.

O Windows precisa do alvo NSIS (já configurado) pra suportar atualização automática — o formato "portable" antigo não suporta.

## Por dentro

Igual ao PokeGrid: cada painel é um `<webview>` do Electron com partição própria. Tudo está em `main.js`, `preload.js` e `index.html`, sem nada escondido.

## Licença

MIT. Projeto independente, sem ligação com o PokeDream.
