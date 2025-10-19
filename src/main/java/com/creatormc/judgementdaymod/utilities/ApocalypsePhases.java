package com.creatormc.judgementdaymod.utilities;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class ApocalypsePhases {

    public enum Phase {
        PHASE_1(0, 19, "FASE 1: IL SOLE ARDENTE",
                "Il sole inizia a scottare... Le foglie bruciano, ma puoi ancora sopravvivere all'esterno.",
                ChatFormatting.YELLOW, ChatFormatting.GOLD),

        PHASE_2(20, 39, "FASE 2: LA TERRA SI SECCA",
                "L'acqua evapora lentamente. Gli alberi si trasformano in cenere. Il calore diventa insopportabile.",
                ChatFormatting.GOLD, ChatFormatting.RED),

        PHASE_3(40, 59, "FASE 3: TUTTO BRUCIA",
                "Il cielo si tinge di rosso. La terra diventa cenere. Le creature muoiono tra le fiamme.",
                ChatFormatting.RED, ChatFormatting.DARK_RED),

        PHASE_4(60, 79, "FASE 4: IL MONDO DI CENERE",
                "Dove c'erano foreste ora c'è solo cenere. Il fuoco ha consumato gran parte della vita.",
                ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE),

        PHASE_5(80, 99, "FASE 5: IL MONDO MUORE",
                "Tutto si trasforma in cenere. La vita si estingue. Solo la morte rimane.",
                ChatFormatting.DARK_PURPLE, ChatFormatting.BLACK),

        PHASE_6(100, 100, "FASE 6: GIORNO DEL GIUDIZIO",
                "Non c'è più speranza. L'apocalisse è completa. Il mondo è cenere.",
                ChatFormatting.BLACK, ChatFormatting.DARK_RED);

        private final int minPercent;
        private final int maxPercent;
        private final String title;
        private final String description;
        private final ChatFormatting titleColor;
        private final ChatFormatting descColor;

        Phase(int minPercent, int maxPercent, String title, String description,
                ChatFormatting titleColor, ChatFormatting descColor) {
            this.minPercent = minPercent;
            this.maxPercent = maxPercent;
            this.title = title;
            this.description = description;
            this.titleColor = titleColor;
            this.descColor = descColor;
        }

        public Component getTitleComponent() {
            return Component.literal(title).withStyle(ChatFormatting.BOLD, titleColor);
        }

        public Component getDescriptionComponent() {
            return Component.literal(description).withStyle(descColor);
        }

        public int getMinPercent() {
            return minPercent;
        }

        public int getMaxPercent() {
            return maxPercent;
        }

        /**
         * Ritorna true se la percentuale è compresa nell'intervallo della fase
         * (inclusivo).
         */
        public boolean matchesPercent(int percent) {
            return percent >= minPercent && percent <= maxPercent;
        }

        /** Clamp 0..100 per sicurezza. */
        private static int normalize(int percent) {
            return Math.max(0, Math.min(100, percent));
        }

        /**
         * Trova la fase corrispondente alla percentuale di progressione
         * 
         * @param percent percentuale da 0 a 100
         */
        public static Phase getPhaseForPercent(int percent) {
            int p = normalize(percent);
            for (Phase phase : Phase.values()) {
                if (phase.matchesPercent(p)) {
                    return phase;
                }
            }
            // Non dovrebbe mai arrivarci, ma mettiamo un fallback
            return PHASE_1;
        }

        /**
         * Controlla se la percentuale attuale ha raggiunto una nuova fase
         * 
         * @param oldPercent vecchia percentuale
         * @param newPercent nuova percentuale
         * @return la nuova fase se è cambiata, null altrimenti
         */
        public static Phase checkPhaseTransition(int oldPercent, int newPercent) {
            Phase oldPhase = getPhaseForPercent(oldPercent);
            Phase newPhase = getPhaseForPercent(newPercent);
            return oldPhase != newPhase ? newPhase : null;
        }

        /**
         * Restituisce l’inizio (minPercent) della fase successiva, o -1 se questa è
         * l’ultima.
         */
        public int getNextPhaseStart() {
            int idx = this.ordinal();
            Phase[] all = Phase.values();
            if (idx + 1 < all.length) {
                return all[idx + 1].minPercent;
            }
            return -1;
        }

        /** Converte (current, max) in percentuale intera 0..100 (arrotondata). */
        public static int toPercent(long current, long max) {
            if (max <= 0)
                return 0; // evita divisione per zero
            if (current <= 0)
                return 0;
            double pct = (current * 100.0) / (double) max;
            return normalize((int) Math.round(pct));
        }
    }
}
