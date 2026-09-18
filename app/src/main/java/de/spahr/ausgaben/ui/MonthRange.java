package de.spahr.ausgaben.ui;

import android.widget.EditText;

import com.google.android.material.slider.RangeSlider;

import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Koppelt einen {@link RangeSlider} (in Monatsschritten) mit zwei Datums-Eingabefeldern. Der Slider rastet
 * monatsweise (Anzeige/Wert = Monatsanfang bzw. -ende); für ein taggenaues Datum tippt man es direkt ins
 * Feld – dieser exakte Wert wird dann verwendet. Anzeige und Eingabe laufen beide über
 * {@link DateFormats}, also über dasselbe Muster: Zeigte das Feld englisch an und läse deutsch, wäre der
 * Bereich still falsch. {@link #getFromMillis()}/{@link #getToMillis()}
 * liefern den aktuellen Bereich (von = Tagesbeginn, bis = Tagesende).
 */
final class MonthRange {

    private final RangeSlider slider;
    private final EditText fromField;
    private final EditText toField;
    private final int baseMonth;
    private long fromMillis;
    private long toMillis;
    private boolean syncing;

    static MonthRange attach(RangeSlider slider, EditText fromField, EditText toField,
                             long minMillis, long maxMillis, Long initFrom, Long initTo) {
        return new MonthRange(slider, fromField, toField, minMillis, maxMillis, initFrom, initTo);
    }

    private MonthRange(RangeSlider slider, EditText fromField, EditText toField,
                       long minMillis, long maxMillis, Long initFrom, Long initTo) {
        this.slider = slider;
        this.fromField = fromField;
        this.toField = toField;
        this.baseMonth = monthIndex(minMillis);
        int months = Math.max(1, monthIndex(maxMillis) - baseMonth);

        slider.setValueFrom(0f);
        slider.setValueTo(months);
        slider.setStepSize(1f);

        int fromIdx = initFrom != null ? clamp(monthIndex(initFrom) - baseMonth, 0, months) : 0;
        int toIdx = initTo != null ? clamp(monthIndex(initTo) - baseMonth, 0, months) : months;
        slider.setValues((float) fromIdx, (float) toIdx);
        fromMillis = initFrom != null ? dayStart(initFrom) : monthStart(baseMonth + fromIdx);
        toMillis = initTo != null ? dayEnd(initTo) : monthEnd(baseMonth + toIdx);
        updateFields();

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (syncing) {
                return;
            }
            int f = Math.round(s.getValues().get(0));
            int t = Math.round(s.getValues().get(1));
            fromMillis = monthStart(baseMonth + f);
            toMillis = monthEnd(baseMonth + t);
            updateFields();
        });
        fromField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                commitField(fromField, true);
            }
        });
        toField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                commitField(toField, false);
            }
        });
    }

    /** Übernimmt ein taggenau getipptes Datum; ungültige Eingabe wird auf die Anzeige zurückgesetzt. */
    private void commitField(EditText field, boolean isFrom) {
        String s = field.getText() == null ? "" : field.getText().toString().trim();
        Long millis = parse(s);
        if (millis == null) {
            updateFields();
            return;
        }
        if (isFrom) {
            fromMillis = dayStart(millis);
        } else {
            toMillis = dayEnd(millis);
        }
        // Slider-Daumen auf den passenden Monat setzen (rein optisch), ohne den exakten Wert zu verlieren.
        syncing = true;
        int max = Math.round(slider.getValueTo());
        int f = clamp(monthIndex(fromMillis) - baseMonth, 0, max);
        int t = clamp(monthIndex(toMillis) - baseMonth, 0, max);
        if (f <= t) {
            slider.setValues((float) f, (float) t);
        }
        syncing = false;
    }

    long getFromMillis() {
        return fromMillis;
    }

    long getToMillis() {
        return toMillis;
    }

    private void updateFields() {
        fromField.setText(DateFormats.date(fromMillis));
        toField.setText(DateFormats.date(toMillis));
    }

    private Long parse(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            Date d = DateFormats.parse(s);
            return d == null ? null : d.getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int monthIndex(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH);
    }

    private static long monthStart(int monthIndex) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(monthIndex / 12, monthIndex % 12, 1, 0, 0, 0);
        return c.getTimeInMillis();
    }

    private static long monthEnd(int monthIndex) {
        return monthStart(monthIndex + 1) - 1;
    }

    private static long dayStart(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long dayEnd(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 999);
        return c.getTimeInMillis();
    }
}
