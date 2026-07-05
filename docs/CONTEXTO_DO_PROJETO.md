# Contexto do projeto — Alertou

## Identidade

- Nome exibido: **Alertou**.
- Nome técnico do projeto: **DroidBoaOferta**.
- Pacote inicial: `br.com.droidboaoferta`.
- Frase principal: **Só avisa quando vale a pena.**
- Aplicativo relacionado do mesmo desenvolvedor: **Boa Escolha**, voltado à leitura de alimentos por código de barras.

## Problema que o app resolve

O usuário participa de vários grupos e canais de promoções no Telegram. Esses grupos publicam muitas mensagens, repetem ofertas e frequentemente usam expressões como "imperdível" sem demonstrar se o preço é realmente bom.

O Alertou deve acompanhar apenas as fontes escolhidas, extrair os dados úteis e alertar quando houver evidência de uma promoção relevante para o usuário.

## Experiência pretendida

1. O usuário conecta a própria conta do Telegram no aparelho.
2. Escolhe os grupos e canais de promoções que deseja acompanhar.
3. Cadastra produtos, categorias, palavras-chave ou valores máximos de interesse.
4. O app identifica produto, variação, preço, cupom, forma de pagamento, loja e link.
5. Ofertas repetidas ou pouco interessantes são descartadas silenciosamente.
6. Uma notificação é exibida somente quando a oferta supera a nota mínima escolhida.
7. O alerta explica por que aquela oferta foi considerada boa.

## Arquitetura recomendada

A opção tecnicamente mais completa analisada foi a **TDLib executada localmente no Android**:

- funciona como uma sessão adicional da conta do usuário;
- recebe novas mensagens e consegue recuperar histórico perdido;
- não depende exclusivamente das notificações do aplicativo oficial;
- permite selecionar grupos e canais já acessíveis pela conta;
- mantém a sessão e o processamento no aparelho.

Um bot foi descartado como fonte principal porque precisaria ser adicionado aos grupos por seus administradores. A leitura exclusiva das notificações do Telegram também foi descartada como solução principal porque pode perder grupos silenciados, mensagens agrupadas ou notificações não exibidas.

## Avaliação de uma promoção

O primeiro motor deve ser determinístico, explicável e configurável. Não deve enviar mensagens dos grupos para serviços externos de inteligência artificial.

Possíveis evidências positivas:

- preço abaixo do limite cadastrado pelo usuário;
- menor preço observado nos grupos em 30 ou 90 dias;
- queda relevante em relação à mediana recente;
- cupom identificado e ainda válido;
- produto e variação correspondem exatamente ao interesse;
- vendedor ou loja pertence à lista confiável.

Possíveis penalidades:

- desconto condicionado a cartão, assinatura ou clube;
- cupom não confirmado;
- frete desconhecido;
- vendedor não reconhecido;
- mesma oferta repetida diversas vezes;
- modelo, capacidade, cor, voltagem ou condição ambíguos.

O resultado pode ser apresentado como nota de `0 a 100` ou `1 a 10`, sempre acompanhado de uma explicação curta. A frase correta quando os dados vierem apenas dos grupos é **menor preço observado nos seus grupos**, e não "menor preço da internet".

## Amazon

O usuário prefere Amazon e não deseja usar Mercado Livre como fonte principal.

Links da Amazon facilitam a identificação exata pelo ASIN. O histórico pode começar com os preços observados nos próprios grupos. Uma integração futura com fornecedor licenciado de histórico, como Keepa, depende de confirmação de cobertura do Amazon Brasil, custo e permissão de uso dos dados.

Não usar scraping da Amazon. Se o aplicativo futuramente usar links de associado, revisar antes as regras comerciais vigentes sobre rastreamento e alertas de preços.

## Privacidade e segurança

- Processar somente grupos e canais escolhidos explicitamente.
- Não analisar conversas pessoais.
- Não enviar textos integrais das mensagens para servidor próprio ou serviço de IA.
- Após a extração, guardar somente os campos necessários: identificador do produto, preço, cupom, condições, origem, horário e link.
- Proteger a sessão local com os mecanismos seguros do Android.
- Disponibilizar desconexão da conta e exclusão completa dos dados.
- Antes de uma eventual publicação, revisar os termos atuais do Telegram e as exigências de privacidade da Play Store.

## Fases sugeridas

### Fase 1 — Base

- estrutura Android em Java;
- identidade visual e tela inicial;
- banco local para fontes, interesses e ofertas;
- modelos do domínio e regras iniciais.

### Fase 2 — Telegram

- cadastro do aplicativo em `my.telegram.org` para obter `api_id` e `api_hash`;
- integração com TDLib;
- login local;
- seleção de grupos e canais;
- sincronização incremental e recuperação de mensagens.

### Fase 3 — Extração

- reconhecimento de preço, cupom, parcelamento e condições;
- resolução segura de links;
- identificação de ASIN e outros códigos de produto;
- deduplicação.

### Fase 4 — Qualidade da oferta

- histórico local;
- valores desejados;
- nota explicável;
- alertas e feedback "valeu a pena" / "não era promoção".

## Pendências antes da integração com Telegram

- Criar uma aplicação em `my.telegram.org` e obter as credenciais próprias exigidas pelo Telegram.
- Decidir se o primeiro uso será estritamente pessoal ou se existe intenção de publicar o app.
- Definir os primeiros grupos e alguns exemplos reais de mensagens para construir o extrator sem armazenar conteúdo desnecessário.
