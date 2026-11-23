package com.asthen.lobbyguard.evento;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /event on | off | time &lt;2h5m53s|cancel&gt; | setnpc | status (permissão lobbyguard.admin, padrão: OP). */
final class EventoCommand implements CommandExecutor, TabCompleter {

    private final EventoManager plugin;

    EventoCommand(EventoManager plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            uso(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> plugin.ligar(sender);
            case "off" -> plugin.desligar(sender);
            case "time" -> tempo(sender, args);
            case "setnpc" -> definirNpc(sender);
            case "status" -> plugin.ok(sender, plugin.statusTexto());
            default -> uso(sender);
        }
        return true;
    }

    private void tempo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.erro(sender, "Uso: /event time <2h5m53s> ou /event time cancel");
            return;
        }
        if (args[1].equalsIgnoreCase("cancel")) {
            plugin.cancelarContagem(sender);
            return;
        }
        // Aceita "2h5m53s" e também "2h 5m 53s" (junta os argumentos).
        String texto = String.join("", Arrays.copyOfRange(args, 1, args.length));
        long segundos = Tempo.interpretar(texto);
        if (segundos < 0) {
            plugin.erro(sender, "Tempo inválido. Exemplos: 2h5m53s, 45m, 90s.");
            return;
        }
        plugin.agendar(sender, segundos);
    }

    private void definirNpc(CommandSender sender) {
        if (!(sender instanceof Player jogador)) {
            plugin.erro(sender, "Só jogadores podem usar /event setnpc (ele usa a sua posição).");
            return;
        }
        plugin.definirNpc(jogador);
    }

    private void uso(CommandSender sender) {
        plugin.erro(sender, "Uso: /event <on|off|time <2h5m53s|cancel>|setnpc|status>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filtrar(List.of("on", "off", "time", "setnpc", "status"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("time")) {
            return filtrar(List.of("cancel", "30m", "1h", "1h30m"), args[1]);
        }
        return List.of();
    }

    private static List<String> filtrar(List<String> opcoes, String prefixo) {
        String p = prefixo.toLowerCase(Locale.ROOT);
        return opcoes.stream().filter(o -> o.startsWith(p)).toList();
    }
}
