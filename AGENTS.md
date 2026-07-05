# Instruções do projeto DroidBoaOferta

## Forma de trabalho

- O aplicativo é Android nativo e deve permanecer em Java, sem Kotlin.
- Não instalar, abrir ou executar em aparelho físico ou emulador sem pedido explícito no turno atual.
- Não enviar alterações para repositórios remotos sem pedido explícito.
- Para mudanças pequenas, preferir revisão dos arquivos. Executar build ou testes somente quando o risco justificar ou quando solicitado.
- Se uma instalação for solicitada, usar somente o usuário principal (`--user 0`). Nunca instalar ou abrir no perfil Samsung Dual App (`user 95`).
- Preservar português brasileiro natural e corrigir problemas de acentuação ao tocar em textos da interface.
- Quando existirem traduções, atualizar os idiomas correspondentes ao alterar textos visíveis.

## Produto

- Nome visível: **Alertou**.
- Frase: **Só avisa quando vale a pena.**
- A fonte principal planejada são grupos e canais de promoções escolhidos pelo usuário no Telegram.
- A arquitetura recomendada é TDLib local no Android, com sessão protegida no aparelho.
- Não usar bot como fonte principal, leitura por acessibilidade ou scraping de páginas.
- Não enviar mensagens do Telegram a serviços externos de IA.
- O motor inicial de avaliação deve usar regras determinísticas, histórico observado e explicações claras.
- Não afirmar "menor preço da internet" sem uma fonte que realmente comprove isso. Preferir "menor preço observado nos seus grupos".
- O usuário prefere Amazon e não quer Mercado Livre como integração principal.

## Interface

- Seguir visual escuro refinado inspirado na Samsung One UI.
- Usar fundo escuro fixo, cartões arredondados, ícones circulares coloridos próximos à borda esquerda, título e resumo em uma coluna, tipografia moderada, botões explícitos e diálogos escuros arredondados.
- Manter o primeiro MVP pequeno e prático.

Consulte `docs/CONTEXTO_DO_PROJETO.md` antes de ampliar o escopo ou escolher a integração de dados.
