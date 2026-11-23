package com.asthen.lobbyguard.evento;

/** Estados possíveis do evento. */
enum Estado {
    /** Sem evento: o aldeão mostra "Sem Evento" e só OP passa por ele. */
    FECHADO,
    /** Contagem regressiva correndo: o evento abre sozinho quando zerar. */
    AGENDADO,
    /** Mundo do evento carregado e liberado para todos pelo aldeão. */
    ABERTO
}
