package net.aguiar.trailerosion;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The erosion hierarchy, parsed from config. Each chain is an ordered list of materials:
 * index 0 is the original, each subsequent entry is a more-eroded stage. Erosion walks the
 * chain forward; reversion walks it backward.
 */
final class ErosionChains {

    /** root material -> ordered stages (index 0 == root). */
    private final Map<Material, List<Material>> chains = new EnumMap<>(Material.class);

    /** any stage material -> the chain root it belongs to (first chain wins for shared stages). */
    private final Map<Material, Material> rootByMaterial = new EnumMap<>(Material.class);

    /** The chain root for a given material, or {@code null} if the material isn't part of any chain. */
    Material rootOf(Material m) {
        return rootByMaterial.get(m);
    }

    /** The ordered stages for a chain root. */
    List<Material> stages(Material root) {
        return chains.get(root);
    }

    boolean isEmpty() {
        return chains.isEmpty();
    }

    int chainCount() {
        return chains.size();
    }

    /**
     * Build chains from the {@code chains:} config section. Every material name is validated
     * against the running server's {@link Material} enum (the SPEC insists names be verified,
     * since block enum names drift between versions). Unknown names are skipped with a warning.
     */
    static ErosionChains fromConfig(ConfigurationSection section, Logger log) {
        ErosionChains ec = new ErosionChains();
        if (section == null) {
            return ec;
        }
        for (String rootKey : section.getKeys(false)) {
            List<String> names = section.getStringList(rootKey);
            List<Material> stages = new ArrayList<>(names.size());
            for (String name : names) {
                Material m = Material.matchMaterial(name);
                if (m == null) {
                    log.warning("[chains." + rootKey + "] unknown material '" + name + "' — skipped");
                    continue;
                }
                if (!m.isBlock()) {
                    log.warning("[chains." + rootKey + "] '" + name + "' is not a block — skipped");
                    continue;
                }
                stages.add(m);
            }
            if (stages.size() < 2) {
                log.warning("[chains." + rootKey + "] needs at least 2 valid stages — chain ignored");
                continue;
            }
            Material root = stages.get(0);
            ec.chains.put(root, Collections.unmodifiableList(stages));
            // First chain to claim a material owns it (resolves ambiguity, e.g. DIRT_PATH
            // appearing in both the grass and podzol chains).
            for (Material m : stages) {
                ec.rootByMaterial.putIfAbsent(m, root);
            }
        }
        return ec;
    }
}
