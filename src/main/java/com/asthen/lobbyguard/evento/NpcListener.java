package com.asthen.lobbyguard.evento;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Clique no aldeão (teleporte) e proteção contra dano e troca. */
final class NpcListener implements Listener {

    private final EventoManager plugin;

    NpcListener(EventoManager plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void aoInteragir(PlayerInteractEntityEvent evento) {
        Entity alvo = evento.getRightClicked();
        if (!plugin.ehNpc(alvo)) {
            return;
        }
        evento.setCancelled(true); // impede abrir a tela de troca do aldeão
        if (evento.getHand() != EquipmentSlot.HAND) {
            return; // o clique também chega pela mão secundária; conta só uma vez
        }
        plugin.aoClicarNpc(evento.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void aoDanificar(EntityDamageEvent evento) {
        if (plugin.ehNpc(evento.getEntity())) {
            evento.setCancelled(true);
        }
    }

    @EventHandler
    public void aoSair(PlayerQuitEvent evento) {
        plugin.limparCliques(evento.getPlayer().getUniqueId());
    }
}
