package br.com.droidboaoferta;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PropertyPriceTrendView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
    private final SimpleDateFormat date = new SimpleDateFormat("dd/MM", new Locale("pt", "BR"));
    private List<PropertyHistoryPoint> points = new ArrayList<>();

    PropertyPriceTrendView(Context context) {
        super(context);
        setMinimumHeight(dp(210));
        currency.setMaximumFractionDigits(0);
        line.setColor(context.getColor(R.color.action_blue));
        line.setStrokeWidth(dp(3));
        line.setStyle(Paint.Style.STROKE);
        dot.setColor(context.getColor(R.color.action_green));
        label.setColor(context.getColor(R.color.text_secondary));
        label.setTextSize(dp(11));
        label.setTextAlign(Paint.Align.CENTER);
    }

    void setPoints(List<PropertyHistoryPoint> values) {
        points = values == null ? new ArrayList<>() : values;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) {
            return;
        }
        float left = dp(48);
        float right = getWidth() - dp(48);
        float top = dp(34);
        float bottom = getHeight() - dp(48);
        double minimum = Double.MAX_VALUE;
        double maximum = -Double.MAX_VALUE;
        for (PropertyHistoryPoint point : points) {
            minimum = Math.min(minimum, point.getPrice());
            maximum = Math.max(maximum, point.getPrice());
        }
        if (maximum == minimum) {
            double margin = Math.max(1d, maximum * 0.03d);
            maximum += margin;
            minimum -= margin;
        }
        path.reset();
        for (int index = 0; index < points.size(); index++) {
            float x = xAt(index, left, right);
            float y = (float) (bottom - (points.get(index).getPrice() - minimum)
                    / (maximum - minimum) * (bottom - top));
            if (index == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, line);
        int labelStep = Math.max(1, (int) Math.ceil(points.size() / 4d));
        for (int index = 0; index < points.size(); index++) {
            PropertyHistoryPoint point = points.get(index);
            float x = xAt(index, left, right);
            float y = (float) (bottom - (point.getPrice() - minimum)
                    / (maximum - minimum) * (bottom - top));
            canvas.drawCircle(x, y, dp(5), dot);
            if (index == 0 || index == points.size() - 1 || index % labelStep == 0) {
                canvas.drawText(currency.format(point.getPrice()), x, y - dp(13), label);
                canvas.drawText(date.format(point.getObservedAt()), x, bottom + dp(27), label);
            }
        }
    }

    private float xAt(int index, float left, float right) {
        return points.size() == 1 ? (left + right) / 2f
                : left + (right - left) * index / (points.size() - 1f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
