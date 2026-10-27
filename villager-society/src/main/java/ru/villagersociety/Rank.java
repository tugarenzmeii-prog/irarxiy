package ru.villagersociety;

import net.minecraft.util.Formatting;

/** Ранг считается из мнений самих жителей. NONE = иерархии ещё нет. Малыши рангов не имеют вообще. */
public enum Rank {
    NONE("", Formatting.GRAY, 2, 3),
    RULER("Правитель", Formatting.GOLD, 4, 16),
    NOBLE("Знать", Formatting.YELLOW, 3, 8),
    COMMON("Простолюдин", Formatting.WHITE, 2, 3),
    OUTCAST("Изгой", Formatting.DARK_GRAY, 1, 1);

    public final String title;
    public final Formatting color;
    /** чем больше, тем главнее */
    public final int power;
    /** сколько единиц еды положено на раздаче */
    public final int foodQuota;

    Rank(String title, Formatting color, int power, int foodQuota) {
        this.title = title;
        this.color = color;
        this.power = power;
        this.foodQuota = foodQuota;
    }
}
