package ru.villagersociety;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Личная «картина мира» жителя.
 * opinions: UUID другого жителя -> насколько ЭТОТ житель считает его главнее (+) или хуже (-).
 * Пока мнений нет, иерархии у жителя тоже нет.
 */
public record SocietyData(String name, Map<String, Integer> opinions) {
    public static final int MAX = 30;
    public static final SocietyData EMPTY = new SocietyData("", Map.of());

    public static final Codec<SocietyData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(SocietyData::name),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("opinions", Map.of()).forGetter(SocietyData::opinions)
    ).apply(i, SocietyData::new));

    public int opinionOf(UUID id) {
        return opinions.getOrDefault(id.toString(), 0);
    }

    public SocietyData withName(String newName) {
        return new SocietyData(newName, opinions);
    }

    public SocietyData adjust(UUID id, int delta) {
        String key = id.toString();
        Map<String, Integer> m = new HashMap<>(opinions);
        int v = Math.max(-MAX, Math.min(MAX, m.getOrDefault(key, 0) + delta));
        if (v == 0) {
            m.remove(key);
        } else {
            m.put(key, v);
        }
        if (m.size() > 48) { // забываем самое слабое мнение, чтобы данные не росли бесконечно
            String weakest = null;
            int w = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getKey().equals(key)) continue;
                int a = Math.abs(e.getValue());
                if (a < w) {
                    w = a;
                    weakest = e.getKey();
                }
            }
            if (weakest != null) m.remove(weakest);
        }
        return new SocietyData(name, m);
    }
}
