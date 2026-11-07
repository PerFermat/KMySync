package de.spahr.ausgaben.ui;

import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;

import de.spahr.ausgaben.R;

/** Die Seite „Neuigkeiten": die neuen Funktionen aller Versionen, die neueste oben. */
public class NewsActivity extends LocalizedActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        LinearLayout container = findViewById(R.id.newsContainer);
        int gap = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                getResources().getDisplayMetrics());
        boolean first = true;
        for (WhatsNew.Entry e : WhatsNew.load(this)) {
            TextView head = new TextView(this);
            head.setText(getString(R.string.news_version, e.version));
            head.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = first ? 0 : gap;
            head.setLayoutParams(lp);
            container.addView(head);
            TextView body = new TextView(this);
            body.setText(WhatsNew.itemsText(e.items));
            body.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.topMargin = gap / 3;
            body.setLayoutParams(bp);
            container.addView(body);
            first = false;
        }
    }
}
