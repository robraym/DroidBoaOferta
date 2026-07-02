package br.com.droidboaoferta;

final class TelegramGroup {
    private final long id;
    private final String title;

    TelegramGroup(long id, String title) {
        this.id = id;
        this.title = title;
    }

    long getId() {
        return id;
    }

    String getTitle() {
        return title;
    }
}
