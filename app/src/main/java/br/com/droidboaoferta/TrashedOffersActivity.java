package br.com.droidboaoferta;

import java.util.List;

public class TrashedOffersActivity extends StoredOffersActivity {
    @Override
    int getTitleResource() {
        return R.string.trash_screen_title;
    }

    @Override
    int getEmptyTextResource() {
        return R.string.trash_empty;
    }

    @Override
    int getBottomNavigationItem() {
        return BottomNavigationController.ITEM_TRASH;
    }

    @Override
    List<ObservedOffer> getOffers(OfferRepository repository) {
        return repository.getTrashed();
    }

    @Override
    boolean hasSecondaryAction() {
        return true;
    }

    @Override
    int getSecondaryActionIcon() {
        return R.drawable.ic_restore;
    }

    @Override
    int getSecondaryActionDescription() {
        return R.string.action_restore_offer;
    }

    @Override
    void runSecondaryAction(OfferRepository repository, String id) {
        repository.restoreTrashed(id);
    }

    @Override
    int getSecondaryActionBackground() {
        return android.R.color.transparent;
    }

    @Override
    boolean hasDeleteAction() {
        return false;
    }

    @Override
    boolean hasHeaderAction() {
        return true;
    }

    @Override
    int getHeaderActionIcon() {
        return R.drawable.ic_trash_outline;
    }

    @Override
    int getHeaderActionDescription() {
        return R.string.action_clear_trash;
    }

    @Override
    int getHeaderActionBackground() {
        return R.drawable.bg_icon_danger;
    }

    @Override
    int getHeaderConfirmationTitle() {
        return R.string.clear_trash_dialog_title;
    }

    @Override
    int getHeaderConfirmationMessage() {
        return R.string.clear_trash_dialog_message;
    }

    @Override
    void runHeaderAction(OfferRepository repository) {
        repository.clearTrashed();
    }

    @Override
    void deleteOffer(OfferRepository repository, String id) {
        repository.deleteTrashed(id);
    }
}
