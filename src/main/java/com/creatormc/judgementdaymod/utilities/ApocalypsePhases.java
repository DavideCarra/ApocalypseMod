package com.creatormc.judgementdaymod.utilities;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class ApocalypsePhases {

    public enum Phase {
        PHASE_1(0, 19, "apocalypse.phase1.title", "apocalypse.phase1.description",
                ChatFormatting.YELLOW, ChatFormatting.GOLD),

        PHASE_2(20, 39, "apocalypse.phase2.title", "apocalypse.phase2.description",
                ChatFormatting.GOLD, ChatFormatting.RED),

        PHASE_3(40, 59, "apocalypse.phase3.title", "apocalypse.phase3.description",
                ChatFormatting.RED, ChatFormatting.DARK_RED),

        PHASE_4(60, 79, "apocalypse.phase4.title", "apocalypse.phase4.description",
                ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE),

        PHASE_5(80, 99, "apocalypse.phase5.title", "apocalypse.phase5.description",
                ChatFormatting.DARK_PURPLE, ChatFormatting.BLACK),

        PHASE_6(100, 100, "apocalypse.phase6.title", "apocalypse.phase6.description",
                ChatFormatting.BLACK, ChatFormatting.DARK_RED);

        private final int minPercent;
        private final int maxPercent;
        private final String titleKey;
        private final String descriptionKey;
        private final ChatFormatting titleColor;
        private final ChatFormatting descColor;

        Phase(int minPercent, int maxPercent, String titleKey, String descriptionKey,
                ChatFormatting titleColor, ChatFormatting descColor) {
            this.minPercent = minPercent;
            this.maxPercent = maxPercent;
            this.titleKey = titleKey;
            this.descriptionKey = descriptionKey;
            this.titleColor = titleColor;
            this.descColor = descColor;
        }

        public Component getTitleComponent() {
            return Component.translatable(titleKey).withStyle(ChatFormatting.BOLD, titleColor);
        }

        public Component getDescriptionComponent() {
            return Component.translatable(descriptionKey).withStyle(descColor);
        }

        public int getMinPercent() {
            return minPercent;
        }

        public int getMaxPercent() {
            return maxPercent;
        }

        // Returns true if percent is in this phase's range
        public boolean matchesPercent(int percent) {
            return percent >= minPercent && percent <= maxPercent;
        }

        // Clamp 0..100
        private static int normalize(int percent) {
            return Math.max(0, Math.min(100, percent));
        }

        // Find phase for given percentage
        public static Phase getPhaseForPercent(int percent) {
            int p = normalize(percent);
            for (Phase phase : Phase.values()) {
                if (phase.matchesPercent(p)) {
                    return phase;
                }
            }
            return PHASE_1;
        }

        // Check if phase changed
        public static Phase checkPhaseTransition(int oldPercent, int newPercent) {
            Phase oldPhase = getPhaseForPercent(oldPercent);
            Phase newPhase = getPhaseForPercent(newPercent);
            return oldPhase != newPhase ? newPhase : null;
        }

        // Returns next phase start, or -1 if last
        public int getNextPhaseStart() {
            int idx = this.ordinal();
            Phase[] all = Phase.values();
            if (idx + 1 < all.length) {
                return all[idx + 1].minPercent;
            }
            return -1;
        }

        // Convert (current, max) to percentage 0..100
        public static int toPercent(long current, long max) {
            if (max <= 0)
                return 0;
            if (current < 0)
                return -1;
            if (current == 0)
                return 0;
            double pct = (current * 100.0) / (double) max;
            return normalize((int) Math.round(pct));
        }
    }
}