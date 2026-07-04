package br.com.droidboaoferta;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class OfferDateFormatter {
    private OfferDateFormatter() {
    }

    static String formatTime(long timestamp) {
        Locale locale = new Locale("pt", "BR");
        return new SimpleDateFormat("HH:mm", locale).format(new Date(timestamp));
    }

    static String getGroupKey(long timestamp) {
        Calendar observed = Calendar.getInstance();
        observed.setTimeInMillis(timestamp);
        return observed.get(Calendar.YEAR) + ":" + observed.get(Calendar.DAY_OF_YEAR);
    }

    static String formatGroupLabel(Context context, long timestamp) {
        Locale locale = new Locale("pt", "BR");
        Calendar now = Calendar.getInstance();
        Calendar today = (Calendar) now.clone();
        clearTime(today);
        Calendar yesterday = (Calendar) today.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (timestamp >= today.getTimeInMillis()) {
            return context.getString(R.string.offer_group_today);
        }
        if (timestamp >= yesterday.getTimeInMillis()) {
            return context.getString(R.string.offer_group_yesterday);
        }

        Calendar observed = Calendar.getInstance();
        observed.setTimeInMillis(timestamp);
        String pattern = observed.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                ? "d 'de' MMMM"
                : "d 'de' MMMM 'de' yyyy";
        return new SimpleDateFormat(pattern, locale).format(new Date(timestamp));
    }

    private static void clearTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
