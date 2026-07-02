# DroidBoaOferta

Aplicativo Android nativo chamado **Boa Oferta**.

O objetivo é acompanhar grupos e canais de promoções escolhidos pelo usuário, organizar as ofertas recebidas e alertar somente quando o preço realmente parecer vantajoso.

## Estado atual

- Projeto Android criado em Java.
- Interface inicial escura inspirada na Samsung One UI.
- `minSdk 24`, `targetSdk 36` e `compileSdk 36`.
- Login local no Telegram por TDLib.
- Seleção dos grupos e canais da lista principal ou arquivada.
- Sessão armazenada localmente, com chave do banco protegida pelo Android Keystore.
- Cadastro de produtos ou palavras-chave com preço máximo.
- Monitor local em primeiro plano, restrito aos grupos escolhidos.
- Avaliação determinística inicial: palavra-chave correspondente e preço dentro do limite.
- Alertas com explicação objetiva e abertura do link encontrado na mensagem.

O histórico comparativo de 30/90 dias e uma extração mais ampla de condições comerciais ainda são etapas futuras.

## Credenciais do Telegram

Crie uma aplicação em [my.telegram.org](https://my.telegram.org) e acrescente ao arquivo local `local.properties`:

```properties
telegram.api_id=12345678
telegram.api_hash=seu_api_hash
```

O arquivo `local.properties` não é versionado. O binário nativo incluído é a interface JSON/JNI da TDLib oficial, compilada para `arm64-v8a` a partir do commit `a17f87c4cff7b90b278d12b91ba0614383aaee82`.

As decisões completas do produto estão em [`docs/CONTEXTO_DO_PROJETO.md`](docs/CONTEXTO_DO_PROJETO.md).
