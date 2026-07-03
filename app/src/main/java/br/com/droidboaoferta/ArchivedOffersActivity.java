package br.com.droidboaoferta;

import java.util.List;

public class ArchivedOffersActivity extends StoredOffersActivity {
    @Override
    int getTitleResource() {
        return R.string.archived_screen_title;
    }

    @Override
    int getEmptyTextResource() {
        return R.string.archived_empty;
    }

    @Override
    int getBottomNavigationItem() {
        return BottomNavigationController.ITEM_ARCHIVED;
    }

    @Override
    List<ObservedOffer> getOffers(OfferRepository repository) {
        return repository.getArchived();
    }

    @Override
    boolean hasSecondaryAction() {
        return true;
    }

    @Override
    int getSecondaryActionIcon() {
        return R.drawable.ic_unarchive;
    }

    @Override
    int getSecondaryActionDescription() {
        return R.string.action_unarchive_offer;
    }

    @Override
    void runSecondaryAction(OfferRepository repository, String id) {
        repository.unarchive(id);
    }

    @Override
    boolean hasDeleteAction() {
        return false;
    }

    @Override
    void deleteOffer(OfferRepository repository, String id) {
        repository.trashArchived(id);
    }
}
