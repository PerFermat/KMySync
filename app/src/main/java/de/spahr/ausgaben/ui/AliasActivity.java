package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.PayeeCorrection;
import de.spahr.ausgaben.db.Repository;

/** Verwaltungsseite: listet alle gelernten Alias-Namen; Zeile antippen = bearbeiten, Button = neu. */
public class AliasActivity extends LocalizedActivity {

    private Repository repository;
    private LinearLayout container;
    private TextView emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alias);
        repository = new Repository(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        container = findViewById(R.id.aliasContainer);
        emptyHint = findViewById(R.id.aliasEmpty);

        ((MaterialButton) findViewById(R.id.btnAddAlias)).setOnClickListener(
                v -> startActivity(new Intent(this, AliasEditActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        repository.getAllAliases(this::showAliases);
    }

    @SuppressLint("SetTextI18n")   // Daten mit Trennzeichen, kein Satz
    private void showAliases(List<PayeeCorrection> aliases) {
        container.removeAllViews();
        boolean empty = aliases == null || aliases.isEmpty();
        emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        int pad = dp(12);
        for (PayeeCorrection a : aliases) {
            TextView row = new TextView(this);
            String prefix = a.preferred ? "★ " : "";
            row.setText(prefix + a.spoken + " " + getString(R.string.alias_arrow) + " " + a.corrected);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setPadding(pad, pad, pad, pad);
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setClickable(true);
            final long id = a.id;
            row.setOnClickListener(v -> {
                Intent i = new Intent(this, AliasEditActivity.class);
                i.putExtra(AliasEditActivity.EXTRA_ALIAS_ID, id);
                startActivity(i);
            });
            container.addView(row);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
