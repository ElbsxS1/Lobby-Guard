package com.asthen.lobbyguard.evento;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Aldeão fixo no lobby que leva ao mundo do evento.
 *
 * <ul>
 *   <li>FECHADO: nome "Sem Evento"; só OP passa.</li>
 *   <li>AGENDADO: nome com contagem regressiva; o evento abre sozinho ao zerar.</li>
 *   <li>ABERTO: mundo do evento carregado; qualquer jogador passa.</li>
 * </ul>
 */
public final class EventoManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final Supplier<Location> retorno;
    private final Map<UUID, Long> ultimoClique = new HashMap<>();

    private NamespacedKey chaveNpc;
    private File arquivoDados;
    private BukkitTask tarefa;

    private Estado estado = Estado.FECHADO;
    private long restante;
    private int contadorSalvar;

    // Posição do aldeão (salva em data.yml). O mundo é guardado pela chave, ex.: "minecraft:lobby".
    private String npcMundo;
    private double npcX;
    private double npcY;
    private double npcZ;
    private float npcYaw;
    private float npcPitch;

    private World mundoEvento;
    private Villager npc;
    private String ultimoNome = "";

    // ------------------------------------------------------------------ ciclo de vida

    /** @param retorno devolve o local do lobby para onde os jogadores voltam quando o evento fecha. */
    public EventoManager(JavaPlugin plugin, Supplier<Location> retorno) {
        this.plugin = plugin;
        this.retorno = retorno;
        this.chaveNpc = new NamespacedKey(plugin, "npc_evento");
        this.arquivoDados = new File(plugin.getDataFolder(), "evento.yml");
        carregarDados();
    }

    /** Registra o comando /event e o listener do aldeão. */
    public void registrar() {
        EventoCommand comando = new EventoCommand(this);
        PluginCommand pc = Objects.requireNonNull(plugin.getCommand("event"), "comando 'event' ausente no plugin.yml");
        pc.setExecutor(comando);
        pc.setTabCompleter(comando);
        plugin.getServer().getPluginManager().registerEvents(new NpcListener(this), plugin);
    }

    public void parar() {
        if (tarefa != null) {
            tarefa.cancel();
        }
        salvarDados();
    }

    /** Chame um tick depois do onEnable, quando os mundos já carregaram. */
    public void iniciar() {
        if (estado == Estado.ABERTO && carregarMundo(null) == null) {
            plugin.getLogger().warning("O evento estava aberto, mas o mundo não carregou; voltando para FECHADO.");
            estado = Estado.FECHADO;
            salvarDados();
        }
        garantirNpc();
        atualizarNome();
        tarefa = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ------------------------------------------------------------------ tick (1 vez por segundo)

    private void tick() {
        garantirNpc();
        if (estado == Estado.AGENDADO) {
            restante--;
            if (restante <= 0) {
                abrirPorContagem();
            } else if (++contadorSalvar >= 10) {
                contadorSalvar = 0;
                salvarDados();
            }
        }
        atualizarNome();
    }

    private void abrirPorContagem() {
        restante = 0;
        if (carregarMundo(null) == null) {
            estado = Estado.FECHADO;
            salvarDados();
            plugin.getLogger().severe("A contagem chegou a zero, mas o mundo do evento não carregou. O evento continua FECHADO.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("lobbyguard.admin")) {
                    erro(p, "A contagem do evento acabou, mas o mundo do evento não carregou. Veja o console.");
                }
            }
            return;
        }
        estado = Estado.ABERTO;
        salvarDados();
        anunciarAbertura();
    }

    // ------------------------------------------------------------------ ações dos comandos

    void ligar(CommandSender quem) {
        if (estado == Estado.ABERTO && mundoEventoCarregado() != null) {
            ok(quem, "O evento já está aberto.");
            return;
        }
        World w = carregarMundo(quem);
        if (w == null) {
            return;
        }
        estado = Estado.ABERTO;
        restante = 0;
        salvarDados();
        atualizarNome();
        anunciarAbertura();
        ok(quem, "Evento aberto.");
    }

    void desligar(CommandSender quem) {
        estado = Estado.FECHADO;
        restante = 0;
        salvarDados();
        atualizarNome();

        World w = mundoEventoCarregado();
        if (w == null) {
            ok(quem, "Evento fechado (o mundo do evento já estava descarregado).");
            return;
        }
        esvaziarMundo(w, () -> {
            if (w.equals(Bukkit.getWorlds().get(0))) {
                erro(quem, "Evento fechado, mas o mundo do evento é o mundo principal e não pode ser descarregado.");
                return;
            }
            boolean descarregou = Bukkit.unloadWorld(w, true);
            if (descarregou) {
                mundoEvento = null;
                ok(quem, "Evento fechado e mundo descarregado.");
            } else {
                erro(quem, "Evento fechado, mas não consegui descarregar o mundo (ainda há jogadores nele ou está em uso).");
            }
        });
    }

    void agendar(CommandSender quem, long segundos) {
        if (estado == Estado.ABERTO) {
            erro(quem, "O evento já está aberto. Use /event off antes de agendar outro.");
            return;
        }
        estado = Estado.AGENDADO;
        restante = segundos;
        contadorSalvar = 0;
        salvarDados();
        atualizarNome();
        ok(quem, "Contagem iniciada: o evento abre em " + Tempo.formatar(segundos) + ".");
    }

    void cancelarContagem(CommandSender quem) {
        if (estado != Estado.AGENDADO) {
            erro(quem, "Não há contagem em andamento.");
            return;
        }
        estado = Estado.FECHADO;
        restante = 0;
        salvarDados();
        atualizarNome();
        ok(quem, "Contagem cancelada.");
    }

    void definirNpc(Player jogador) {
        Location l = jogador.getLocation();
        removerNpcAtual();
        npcMundo = l.getWorld().getKey().toString();
        npcX = l.getX();
        npcY = l.getY();
        npcZ = l.getZ();
        npcYaw = l.getYaw();
        npcPitch = 0f;
        salvarDados();
        garantirNpc();
        ok(jogador, "Aldeão posicionado aqui.");
    }

    String statusTexto() {
        StringBuilder sb = new StringBuilder("Estado: ").append(estado);
        if (estado == Estado.AGENDADO) {
            sb.append(" (faltam ").append(Tempo.formatar(restante)).append(')');
        }
        sb.append(" | Mundo do evento: ").append(mundoEventoCarregado() != null ? "carregado" : "descarregado");
        Location l = localNpc();
        if (l == null) {
            sb.append(" | Aldeão: ainda não posicionado (use /event setnpc)");
        } else {
            sb.append(String.format(Locale.ROOT, " | Aldeão: %s %.1f %.1f %.1f%s",
                    npcMundo, npcX, npcY, npcZ, npc != null && npc.isValid() ? "" : " (não está spawnado)"));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ clique no aldeão

    boolean ehNpc(Entity entidade) {
        return entidade instanceof Villager
                && entidade.getPersistentDataContainer().has(chaveNpc, PersistentDataType.BYTE);
    }

    void aoClicarNpc(Player jogador) {
        long agora = System.currentTimeMillis();
        Long anterior = ultimoClique.put(jogador.getUniqueId(), agora);
        if (anterior != null && agora - anterior < 800) {
            return;
        }

        boolean admin = jogador.hasPermission("lobbyguard.event.bypass");
        if (estado == Estado.ABERTO || admin) {
            World w = mundoEventoCarregado();
            if (w == null) {
                w = carregarMundo(admin ? jogador : null);
            }
            if (w == null) {
                if (!admin) {
                    jogador.sendMessage(mensagem("evento-fechado", ""));
                }
                return;
            }
            jogador.teleportAsync(w.getSpawnLocation());
            return;
        }

        if (estado == Estado.AGENDADO) {
            jogador.sendMessage(mensagem("evento-agendado", Tempo.formatar(restante)));
        } else {
            jogador.sendMessage(mensagem("evento-fechado", ""));
        }
    }

    void limparCliques(UUID jogador) {
        ultimoClique.remove(jogador);
    }

    // ------------------------------------------------------------------ mundo do evento

    World mundoEventoCarregado() {
        if (mundoEvento != null && Bukkit.getWorld(mundoEvento.getUID()) != null) {
            return mundoEvento;
        }
        mundoEvento = Bukkit.getWorld(cfg("mundo-evento", "eventos"));
        return mundoEvento;
    }

    /** Carrega o mundo do evento (se a pasta existir). Em caso de falha, avisa {@code quem} e devolve null. */
    World carregarMundo(CommandSender quem) {
        World ja = mundoEventoCarregado();
        if (ja != null) {
            return ja;
        }
        String nome = cfg("mundo-evento", "eventos");

        // createWorld() cria um mundo novo e vazio se a pasta não existir; por isso conferimos antes.
        if (!pastaDoMundoExiste(nome)) {
            erro(quem, "Não encontrei a pasta do mundo '" + nome + "' no servidor. Coloque o mundo lá antes de abrir o evento.");
            return null;
        }

        World w = null;
        try {
            w = WorldCreator.name(nome).createWorld();
            if (w == null) {
                w = WorldCreator.ofKey(NamespacedKey.minecraft(nome.toLowerCase(Locale.ROOT))).createWorld();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Falha ao carregar o mundo '" + nome + "'", e);
        }
        if (w == null) {
            erro(quem, "O Paper não conseguiu carregar o mundo '" + nome + "'. Veja o console.");
            return null;
        }
        mundoEvento = w;
        return w;
    }

    /** Procura a pasta no layout antigo (&lt;servidor&gt;/nome) e no layout 26.1+ (&lt;nivel&gt;/dimensions/&lt;namespace&gt;/nome). */
    private boolean pastaDoMundoExiste(String nome) {
        File raiz = Bukkit.getWorldContainer();
        if (new File(raiz, nome).isDirectory()) {
            return true;
        }
        File[] niveis = raiz.listFiles(File::isDirectory);
        if (niveis == null) {
            return false;
        }
        for (File nivel : niveis) {
            File[] espacos = new File(nivel, "dimensions").listFiles(File::isDirectory);
            if (espacos == null) {
                continue;
            }
            for (File espaco : espacos) {
                if (new File(espaco, nome).isDirectory()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Leva todos que estão no mundo para o lobby e só então executa {@code depois} (na thread principal). */
    private void esvaziarMundo(World mundo, Runnable depois) {
        Location destino = destinoRetorno();
        List<CompletableFuture<Boolean>> futuros = new ArrayList<>();
        for (Player p : new ArrayList<>(mundo.getPlayers())) {
            p.sendMessage(mensagem("evento-encerrado", ""));
            futuros.add(p.teleportAsync(destino));
        }
        if (futuros.isEmpty()) {
            depois.run();
            return;
        }
        CompletableFuture.allOf(futuros.toArray(new CompletableFuture<?>[0]))
                .whenComplete((v, ex) -> plugin.getServer().getScheduler().runTask(plugin, depois));
    }

    private Location destinoRetorno() {
        String nome = cfg("mundo-retorno", "");
        if (nome.isBlank()) {
            Location l = retorno.get();
            if (l != null) {
                return l;
            }
        }
        World w = nome.isBlank() ? null : Bukkit.getWorld(nome);
        if (w == null) {
            Location n = localNpc();
            if (n != null) {
                w = n.getWorld();
            }
        }
        if (w == null) {
            w = Bukkit.getWorlds().get(0);
        }
        return w.getSpawnLocation();
    }

    private void anunciarAbertura() {
        Bukkit.broadcast(mensagem("evento-aberto", ""));
    }

    // ------------------------------------------------------------------ aldeão

    private Location localNpc() {
        if (npcMundo == null) {
            return null;
        }
        NamespacedKey chave = NamespacedKey.fromString(npcMundo);
        World w = chave == null ? null : Bukkit.getWorld(chave);
        return w == null ? null : new Location(w, npcX, npcY, npcZ, npcYaw, npcPitch);
    }

    /** Garante que o aldeão existe: reaproveita o que já está no chunk ou cria um novo. */
    private void garantirNpc() {
        if (npc != null && npc.isValid()) {
            return;
        }
        Location loc = localNpc();
        if (loc == null) {
            return;
        }
        Chunk chunk = loc.getChunk();
        // Mantém o chunk do aldeão carregado para ele nunca sumir quando não há ninguém perto.
        loc.getWorld().addPluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);

        Villager encontrado = null;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Villager v && ehNpc(v)) {
                if (encontrado == null) {
                    encontrado = v;
                } else {
                    v.remove(); // duplicado
                }
            }
        }
        if (encontrado == null) {
            encontrado = criarNpc(loc);
        }
        npc = encontrado;
        ultimoNome = "";
        atualizarNome();
    }

    private Villager criarNpc(Location loc) {
        return loc.getWorld().spawn(loc, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setGravity(false);
            v.setCollidable(false);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(chaveNpc, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void removerNpcAtual() {
        Location antiga = localNpc();
        if (npc != null) {
            npc.remove();
            npc = null;
        }
        if (antiga != null) {
            Chunk chunk = antiga.getChunk();
            for (Entity e : chunk.getEntities()) {
                if (e instanceof Villager v && ehNpc(v)) {
                    v.remove();
                }
            }
            antiga.getWorld().removePluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
        }
    }

    private void atualizarNome() {
        if (npc == null || !npc.isValid()) {
            return;
        }
        String texto = switch (estado) {
            case FECHADO -> cfg("nome-fechado", "&c&lSem Evento");
            case AGENDADO -> cfg("nome-agendado", "&e&lEvento começa em &f&l{tempo}")
                    .replace("{tempo}", Tempo.formatar(restante));
            case ABERTO -> cfg("nome-aberto", "&a&lEvento Aberto");
        };
        if (texto.equals(ultimoNome)) {
            return; // evita reenviar o mesmo nome toda hora
        }
        ultimoNome = texto;
        npc.customName(LEGACY.deserialize(texto));
        npc.setCustomNameVisible(true);
    }

    // ------------------------------------------------------------------ dados (data.yml)

    private void carregarDados() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arquivoDados);
        try {
            estado = Estado.valueOf(y.getString("estado", "FECHADO"));
        } catch (IllegalArgumentException e) {
            estado = Estado.FECHADO;
        }
        restante = y.getLong("restante", 0);
        if (estado == Estado.AGENDADO && restante <= 0) {
            estado = Estado.FECHADO;
        }
        npcMundo = y.getString("npc.mundo");
        npcX = y.getDouble("npc.x");
        npcY = y.getDouble("npc.y");
        npcZ = y.getDouble("npc.z");
        npcYaw = (float) y.getDouble("npc.yaw");
        npcPitch = (float) y.getDouble("npc.pitch");
    }

    private void salvarDados() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("estado", estado.name());
        y.set("restante", restante);
        if (npcMundo != null) {
            y.set("npc.mundo", npcMundo);
            y.set("npc.x", npcX);
            y.set("npc.y", npcY);
            y.set("npc.z", npcZ);
            y.set("npc.yaw", (double) npcYaw);
            y.set("npc.pitch", (double) npcPitch);
        }
        try {
            y.save(arquivoDados);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Não consegui salvar o data.yml", e);
        }
    }

    // ------------------------------------------------------------------ mensagens

    /** Lê uma chave da seção "evento:" do config.yml do LobbyGuard. */
    private String cfg(String chave, String padrao) {
        return plugin.getConfig().getString("evento." + chave, padrao);
    }

    Component mensagem(String chave, String tempo) {
        String prefixo = cfg("mensagens.prefixo", "");
        String corpo = cfg("mensagens." + chave, "");
        return LEGACY.deserialize(prefixo + corpo.replace("{tempo}", tempo));
    }

    void ok(CommandSender quem, String texto) {
        if (quem != null) {
            quem.sendMessage(Component.text(texto, NamedTextColor.GREEN));
        }
    }

    void erro(CommandSender quem, String texto) {
        if (quem != null) {
            quem.sendMessage(Component.text(texto, NamedTextColor.RED));
        } else {
            plugin.getLogger().warning(texto);
        }
    }
}
