package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.settings.AccentColor;
import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Liste aller Profile (Farbpunkt + Name): Tipp auf ein inaktives Profil wechselt sofort dorthin (kein
 * App-Neustart, siehe {@link ProfileManager#switchTo}); ein langer Druck öffnet die Profil-Maske
 * ({@link OnboardingActivity}) zum Bearbeiten – bei einem noch nicht aktiven Profil wird vorher
 * dorthin gewechselt. Je Zeile außerdem ein Menü zum Umbenennen, Farbe ändern oder Löschen. Ein neues
 * Profil legt man über „Neues Profil anlegen" in den Einstellungen an, nicht hier.
 */
public final class ProfileSwitchDialog {

    private ProfileSwitchDialog() {
    }

    public static void show(Activity activity) {
        ProfileManager pm = new ProfileManager(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);

        AlertDialog[] holder = new AlertDialog[1];
        String activeId = pm.getActiveProfileId();
        for (ProfileManager.Profile profile : pm.getProfiles()) {
            list.addView(buildRow(activity, pm, profile, profile.id.equals(activeId), holder));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);

        AlertDialog dialog = new AppDialog(activity)
                .setTitle(R.string.action_switch_profile)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    private static View buildRow(Activity activity, ProfileManager pm, ProfileManager.Profile profile,
                                  boolean active, AlertDialog[] dialogHolder) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 12), Ui.dp(activity, 8), Ui.dp(activity, 12));
        row.setClickable(true);
        row.setFocusable(true);

        View swatch = new View(activity);
        swatch.setBackground(swatchDrawable(profile.accentColor));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(Ui.dp(activity, 24), Ui.dp(activity, 24));
        swLp.setMarginEnd(Ui.dp(activity, 16));
        row.addView(swatch, swLp);

        TextView name = new TextView(activity);
        name.setText(active ? activity.getString(R.string.profile_active_marker, profile.name) : profile.name);
        name.setTextSize(16);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton menuButton = new ImageButton(activity);
        menuButton.setImageResource(R.drawable.ic_more_vert);
        // Das Icon ist fest weiß (sonst für Toolbars gedacht) – hier liegt es auf dem normalen
        // Dialoghintergrund und wäre in Hellmodus unsichtbar. Vordergrundfarbe der Zeile statt fester
        // Farbe: Schwarz im Hell-, Weiß im Dunkelmodus (gleiches Muster wie AccountManageActivity).
        menuButton.setColorFilter(primaryText(activity));
        menuButton.setBackground(null);
        menuButton.setOnClickListener(v -> showRowMenu(activity, pm, profile, menuButton, dialogHolder));
        row.addView(menuButton);

        if (!active) {
            row.setOnClickListener(v -> {
                pm.switchTo(activity, profile.id);
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                restartToMain(activity);
            });
        }
        // Langer Druck bearbeitet das Profil (Datenquelle + Akzentfarbe), egal ob aktiv oder nicht.
        row.setOnLongClickListener(v -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            ProfileSettingsActivity.startForEditing(activity, profile.id);
            return true;
        });
        return row;
    }

    private static void showRowMenu(Activity activity, ProfileManager pm, ProfileManager.Profile profile,
                                     View anchor, AlertDialog[] dialogHolder) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.profile_rename);
        menu.getMenu().add(0, 2, 1, R.string.profile_change_color);
        menu.getMenu().add(0, 4, 2, R.string.settings_profile_change);
        boolean canDelete = pm.getProfiles().size() > 1;
        if (canDelete) {
            menu.getMenu().add(0, 3, 3, R.string.profile_delete);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showRenameDialog(activity, pm, profile, dialogHolder);
            } else if (item.getItemId() == 2) {
                showColorPicker(activity, pm, profile, dialogHolder);
            } else if (item.getItemId() == 3) {
                confirmDelete(activity, pm, profile, dialogHolder);
            } else if (item.getItemId() == 4) {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                ProfileSettingsActivity.startForEditing(activity, profile.id);
            }
            return true;
        });
        menu.show();
    }

    private static void showRenameDialog(Activity activity, ProfileManager pm, ProfileManager.Profile profile,
                                          AlertDialog[] dialogHolder) {
        TextInputLayout box = new TextInputLayout(activity);
        int pad = Ui.dp(activity, 24);
        box.setPadding(pad, pad / 2, pad, 0);
        TextInputEditText field = new TextInputEditText(box.getContext());
        field.setText(profile.name);
        box.addView(field);

        new AppDialog(activity)
                .setTitle(R.string.profile_rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String text = field.getText() == null ? "" : field.getText().toString();
                    pm.renameProfile(profile.id, text);
                    if (dialogHolder[0] != null) {
                        dialogHolder[0].dismiss();
                    }
                    show(activity);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showColorPicker(Activity activity, ProfileManager pm, ProfileManager.Profile profile,
                                         AlertDialog[] dialogHolder) {
        ColorPickerDialog.show(activity, R.string.profile_change_color, ProfileManager.ACCENT_PALETTE, color -> {
            pm.setAccentColor(profile.id, color);
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            if (profile.id.equals(pm.getActiveProfileId()) && activity instanceof LocalizedActivity) {
                AccentColor.apply(activity);
            }
            show(activity);
        });
    }

    private static void confirmDelete(Activity activity, ProfileManager pm, ProfileManager.Profile profile,
                                       AlertDialog[] dialogHolder) {
        AppDialog.destructive(activity)
                .setTitle(activity.getString(R.string.profile_delete_confirm_title, profile.name))
                .setMessage(R.string.profile_delete_confirm_message)
                .setPositiveButton(R.string.profile_delete, (d, w) -> {
                    boolean wasActive = profile.id.equals(pm.getActiveProfileId());
                    pm.deleteProfile(activity, profile.id);
                    if (dialogHolder[0] != null) {
                        dialogHolder[0].dismiss();
                    }
                    if (wasActive) {
                        restartToMain(activity);
                    } else {
                        show(activity);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Kein App-Neustart: nur der Activity-Stack wird auf MainActivity zurückgesetzt (wie nach Restore). */
    private static void restartToMain(Activity activity) {
        Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
    }

    private static GradientDrawable swatchDrawable(int color) {
        return ColorPickerDialog.swatchDrawable(color);
    }


    /** Vordergrundfarbe für normalen Text/Icons: Schwarz im Hell-, Weiß im Dunkelmodus. */
    private static int primaryText(Activity activity) {
        android.util.TypedValue tv = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
        return androidx.core.content.ContextCompat.getColor(
                activity, tv.resourceId != 0 ? tv.resourceId : android.R.color.black);
    }
}
