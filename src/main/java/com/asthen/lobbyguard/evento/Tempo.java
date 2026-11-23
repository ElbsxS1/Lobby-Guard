package com.asthen.lobbyguard.evento;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conversão entre texto ("2h5m53s") e segundos. Não depende do Bukkit. */
final class Tempo {

    private static final Pattern PADRAO =
            Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?", Pattern.CASE_INSENSITIVE);

    private Tempo() {
    }

    /** Retorna os segundos, ou -1 se o texto for inválido ou o total não for maior que zero. */
    static long interpretar(String texto) {
        if (texto == null || texto.isEmpty()) {
            return -1;
        }
        Matcher m = PADRAO.matcher(texto);
        if (!m.matches()) {
            return -1;
        }
        try {
            long horas = valor(m.group(1));
            long minutos = valor(m.group(2));
            long segundos = valor(m.group(3));
            long total = Math.addExact(
                    Math.addExact(Math.multiplyExact(horas, 3600L), Math.multiplyExact(minutos, 60L)),
                    segundos);
            return total > 0 ? total : -1;
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }

    private static long valor(String grupo) {
        return grupo == null ? 0 : Long.parseLong(grupo);
    }

    /** 7553 -> "2h05m53s"; 353 -> "5m53s"; 53 -> "53s". */
    static String formatar(long totalSegundos) {
        long s = Math.max(0, totalSegundos);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long seg = s % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh%02dm%02ds", h, m, seg);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%dm%02ds", m, seg);
        }
        return seg + "s";
    }
}
