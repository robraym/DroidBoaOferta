package br.com.droidboaoferta;

import java.util.Collections;
import java.util.List;

final class PropertyPageResult {
    private final String condominiumName;
    private final boolean hasCondominiumName;
    private final List<PropertyPageListing> saleListings;

    PropertyPageResult(String condominiumName, List<PropertyPageListing> saleListings) {
        this.hasCondominiumName = condominiumName != null && !condominiumName.trim().isEmpty();
        this.condominiumName = !hasCondominiumName
                ? "Condomínio monitorado"
                : condominiumName.trim();
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

    List<PropertyPageListing> getSaleListings() {
        return saleListings;
    }
}
