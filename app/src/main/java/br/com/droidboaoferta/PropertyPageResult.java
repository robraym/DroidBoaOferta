package br.com.droidboaoferta;

import java.util.Collections;
import java.util.List;

final class PropertyPageResult {
    private final String condominiumName;
    private final boolean hasCondominiumName;
    private final List<PropertyPageListing> saleListings;

    PropertyPageResult(String condominiumName, List<PropertyPageListing> saleListings) {
        String normalizedName = normalizeCondominiumName(condominiumName);
        this.hasCondominiumName = !normalizedName.isEmpty();
        this.condominiumName = !hasCondominiumName
                ? "Condomínio monitorado"
                : normalizedName;
        this.saleListings = saleListings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(saleListings);
    }

    String getCondominiumName() {
        return condominiumName;
    }

    boolean hasCondominiumName() {
        return hasCondominiumName;
    }

    static String normalizeCondominiumName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceFirst("(?i)^condom[ií]nio\\s+", "").trim();
    }

    List<PropertyPageListing> getSaleListings() {
        return saleListings;
    }
}
