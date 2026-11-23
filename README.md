# LobbyGuard

Plugin para Paper 26.x que cria um **lobby protegido** com um **NPC aldeão** que teleporta o jogador para o **mundo de sobrevivência**.

Todas as regras valem **somente no mundo do lobby**. O mundo de sobrevivência não recebe proteção, luz nem nenhuma outra regra.

## O que ele faz no lobby

- **Proteção contra quebrar/colocar blocos**, usar baldes, mexer em quadros e armor stands, pisotear plantação, fogo e explosões.
- **Sem mobs**: nenhum mob nasce (naturais, spawners, ovos). O NPC é criado pelo plugin e não é afetado.
- **Void**: quem cai abaixo do limite volta ao spawn do lobby (pode desligar no `config.yml`).
- **Luz em todo o lobby**: visão noturna permanente, horário fixo de dia e sem chuva. A visão noturna some ao sair do lobby.
- **Sem dano e sem fome**.
- **NPC blindado**: aldeão sem IA, invulnerável, sem som, que não abre troca nem pode ser nomeado/amarrado. Se alguém o remover (ex.: `/kill`), o plugin recria em até 5 segundos e apaga duplicatas.
- Ao clicar no NPC, o jogador vai para o spawn do mundo de sobrevivência.

Extras para o conjunto funcionar: quem morre no mundo de sobrevivência (sem cama) renasce lá, e quem entra no servidor aparece no spawn do lobby (`teleport-on-join`).

> Quem tem a permissão `lobbyguard.bypass` (ops por padrão) **ignora as proteções**. Para testar, use uma conta sem OP.

## Instalação

1. Coloque `LobbyGuard-1.0.0.jar` em `plugins/` e inicie o servidor.
2. Revise `plugins/LobbyGuard/config.yml` (nomes dos mundos). Se os mundos `lobby` e `survival` não existirem, o plugin cria (lobby superflat, survival normal).
3. Entre no lobby, vá até onde o NPC deve ficar e rode `/lobbyguard setnpc`.
4. Opcional: `/lobbyguard setspawn` define o ponto onde os jogadores aparecem.
5. Para mudar o ponto de chegada na sobrevivência, vá ao mundo `survival` e use `/setworldspawn`.

## Comandos (permissão `lobbyguard.admin`)

| Comando | O que faz |
|---|---|
| `/lobbyguard setnpc` | Cria o NPC na sua posição (precisa estar no lobby) |
| `/lobbyguard removenpc` | Remove o NPC de vez |
| `/lobbyguard setspawn` | Define o spawn do lobby |
| `/lobbyguard reload` | Recarrega o `config.yml` |

## Compilar

O repositório já tem um workflow do GitHub Actions: a cada push ele compila e deixa o jar na aba **Actions → Build → Artifacts → LobbyGuard**.

Para compilar no seu PC é preciso JDK 25 e Maven:

```
mvn package
```

O jar sai em `target/LobbyGuard-1.0.0.jar`. A dependência `paper-api` usa a faixa `[26.3.build,)` recomendada na documentação do Paper; se preferir fixar a build, troque a versão no `pom.xml`.

## Limitações conhecidas

- Portais do Nether/End dentro do mundo de sobrevivência seguem o comportamento padrão do Bukkit para mundos extras (levam aos mundos principais). Para dimensões próprias, use um plugin de mundos como o Multiverse.
- O mundo do lobby é um mundo separado do principal (`world`). Se quiser usar o principal como lobby, coloque `lobby: world` no `config.yml`.

## Esse Plugin trabalha com https://github.com/ElbsxS1/AsthenEssencials
e também 
https://github.com/ElbsxS1/DiscordKitBridge
e com o Bot 
https://github.com/contatorzlkz-prog/BOT-ASTHEN

## 🔗 Ecossistema Asthen

Este projeto faz parte do ecossistema **Asthen**, um conjunto de plugins e ferramentas que trabalham juntos para integrar o servidor Minecraft, o gerenciamento de jogadores, o lobby, os comandos, os sistemas internos e o Discord.

**Projetos relacionados:**

- [AsthenEssencials](https://github.com/ElbsxS1/AsthenEssencials)
- [DiscordKitBridge](https://github.com/ElbsxS1/DiscordKitBridge)
- [Lobby-Guard](https://github.com/ElbsxS1/Lobby-Guard)
- [BOT-ASTHEN](https://github.com/contatorzlkz-prog/BOT-ASTHEN)
