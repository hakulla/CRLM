package org.mobileagent.agent;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AgentKit {
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final int BG = Color.rgb(10, 10, 10);
    static final int CARD = Color.rgb(22, 22, 22);
    static final int CARD_SOFT = Color.rgb(28, 28, 28);
    static final int CARD_ERROR = Color.rgb(44, 22, 23);
    static final int ICON_BG = Color.rgb(32, 32, 32);
    static final int DIVIDER = Color.rgb(35, 35, 35);
    static final int TEXT = Color.rgb(244, 244, 244);
    static final int MUTED = Color.rgb(158, 158, 158);
    static final int DIM = Color.rgb(105, 105, 105);
    static final int ERROR = Color.rgb(255, 107, 107);
    static final int SUCCESS = Color.rgb(118, 214, 170);

    interface InputHandler {
        boolean onInput(String value, Dialog dialog);
    }

    interface ToggleHandler {
        void onToggle(boolean checked);
    }

    static final class ValueRow {
        final LinearLayout view;
        final TextView valueView;

        ValueRow(LinearLayout view, TextView valueView) {
            this.view = view;
            this.valueView = valueView;
        }

        void setValue(String value) {
            valueView.setText(value);
        }
    }

    private AgentKit() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable roundedTop(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float radius = dp(context, radiusDp);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }

    static ImageView icon(Context context, String name, int color) {
        ImageView view = new ImageView(context);
        int id = context.getResources().getIdentifier(name, "drawable",
                context.getPackageName());
        if (id != 0) {
            view.setImageResource(id);
        }
        view.setColorFilter(color);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return view;
    }

    static ImageView iconButton(Context context, String name, int color, int sizeDp) {
        ImageView view = icon(context, name, color);
        view.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        view.setBackground(rounded(context, ICON_BG, sizeDp / 2f));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout card(Context context) {
        LinearLayout layout = column(context);
        layout.setBackground(rounded(context, CARD, 16));
        layout.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        return layout;
    }

    static TextView title(Context context, String text, float size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(12.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(context, 13), 0, dp(context, 4));
        return view;
    }

    static TextView hint(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(DIM);
        view.setTextSize(10.5f);
        view.setLineSpacing(dp(context, 2), 1f);
        view.setPadding(0, dp(context, 4), 0, 0);
        return view;
    }

    static TextView section(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(DIM);
        view.setTextSize(10f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 5));
        return view;
    }

    static Button button(Context context, String text, int background, int textColor,
                         boolean bold) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12.5f);
        button.setTextColor(textColor);
        button.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setBackground(rounded(context, background, 12));
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        return button;
    }

    static Button primary(Context context, String text) {
        return button(context, text, Color.WHITE, Color.rgb(8, 8, 8), true);
    }

    static Button secondary(Context context, String text) {
        return button(context, text, CARD_SOFT, TEXT, false);
    }

    static Button danger(Context context, String text) {
        return button(context, text, CARD_ERROR, ERROR, false);
    }

    static Button ghost(Context context, String text, int color) {
        return button(context, text, Color.TRANSPARENT, color, false);
    }

    static EditText field(Context context, String value, String hint, boolean multiline) {
        EditText field = new EditText(context);
        field.setTextSize(14f);
        field.setTextColor(TEXT);
        field.setHintTextColor(DIM);
        field.setHint(hint);
        field.setBackground(rounded(context, CARD_SOFT, 12));
        field.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        field.setSingleLine(!multiline);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        if (multiline) {
            field.setMinLines(3);
            field.setMaxLines(8);
            field.setGravity(Gravity.TOP | Gravity.START);
        }
        field.setText(value == null ? "" : value);
        field.setSelection(field.getText().length());
        return field;
    }

    static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(context, heightDp)));
        return view;
    }

    static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(DIVIDER);
        view.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 1));
        return view;
    }

    static LinearLayout toggleRow(Context context, String title, String description,
                                  boolean checked, final ToggleHandler handler) {
        LinearLayout row = row(context);
        row.setBackground(rounded(context, CARD, 14));
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 12), dp(context, 12));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout info = column(context);
        TextView titleView = title(context, title, 13f);
        info.addView(titleView, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (description != null && !description.isEmpty()) {
            TextView desc = hint(context, description);
            desc.setPadding(0, dp(context, 2), 0, 0);
            info.addView(desc, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
        final TextView state = new TextView(context);
        final boolean[] value = {checked};
        state.setText(checked ? "Open" : "Closed");
        state.setTextColor(checked ? SUCCESS : MUTED);
        state.setTextSize(10.5f);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2));
        state.setBackground(rounded(context, ICON_BG, 9));
        row.addView(state, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                value[0] = !value[0];
                state.setText(value[0] ? "Open" : "Closed");
                state.setTextColor(value[0] ? SUCCESS : MUTED);
                if (handler != null) {
                    handler.onToggle(value[0]);
                }
            }
        });
        return row;
    }

    static ValueRow selectRow(Context context, String title, String value,
                              View.OnClickListener listener) {
        LinearLayout row = row(context);
        row.setBackground(rounded(context, CARD, 14));
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 12), dp(context, 12));
        row.setClickable(true);
        row.setFocusable(true);
        TextView titleView = title(context, title, 12.5f);
        row.addView(titleView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView valueView = new TextView(context);
        valueView.setText(value);
        valueView.setTextColor(MUTED);
        valueView.setTextSize(10.5f);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setPadding(dp(context, 8), 0, dp(context, 6), 0);
        row.addView(valueView, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.addView(icon(context, "ic_chevron_right", DIM),
                new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)));
        if (listener != null) {
            row.setOnClickListener(listener);
        }
        return new ValueRow(row, valueView);
    }

    static Dialog page(Context context, String title, View content) {
        return page(context, title, content, null);
    }

    static Dialog page(Context context, String titleText, View content, View footer) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column(context);
        root.setBackgroundColor(BG);

        FrameLayout header = new FrameLayout(context);
        header.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        ImageView back = iconButton(context, "ic_back", TEXT, 40);
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(
                dp(context, 40), dp(context, 40), Gravity.START | Gravity.CENTER_VERTICAL);
        header.addView(back, backParams);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        TextView title = title(context, titleText, 18f);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(MATCH, dp(context, 48),
                Gravity.CENTER));
        View spacer = new View(context);
        header.addView(spacer, new FrameLayout.LayoutParams(dp(context, 40),
                dp(context, 40), Gravity.END | Gravity.CENTER_VERTICAL));

        View line = new View(context);
        line.setBackgroundColor(DIVIDER);
        root.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
        if (content != null) {
            root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        }
        if (footer != null) {
            root.addView(footer, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(BG);
            window.setNavigationBarColor(BG);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(MATCH, MATCH);
        }
        return dialog;
    }

    static Dialog sheet(Context context, String titleText, View content) {
        return sheet(context, titleText, content, 0);
    }

    static Dialog sheet(Context context, String titleText, View content, int maxHeightDp) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column(context);
        panel.setBackground(roundedTop(context, CARD, 22));
        panel.setPadding(0, dp(context, 8), 0, dp(context, 18));

        View handle = new View(context);
        handle.setBackground(rounded(context, Color.rgb(80, 80, 80), 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                dp(context, 38), dp(context, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handle, handleParams);

        FrameLayout header = new FrameLayout(context);
        panel.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView title = title(context, titleText, 15f);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(MATCH, dp(context, 52)));
        ImageView close = iconButton(context, "ic_close", MUTED, 30);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(context, 30), dp(context, 30), Gravity.END | Gravity.CENTER_VERTICAL);
        closeParams.setMargins(0, 0, dp(context, 14), 0);
        header.addView(close, closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        if (content != null) {
            panel.addView(content, new LinearLayout.LayoutParams(MATCH,
                    maxHeightDp > 0 ? dp(context, maxHeightDp) : WRAP));
        }

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(MATCH, WRAP);
        }
        return dialog;
    }

    static Dialog confirm(Context context, String titleText, String message,
                          String positiveText, boolean danger, final Runnable onConfirm) {
        return confirm(context, titleText, message, positiveText, danger, onConfirm, false);
    }

    /**
     * positiveOnLeft=true (b) The time line is left-aligned and the confirmation button is placed on the leftmost;
     * Only for deletions that require a single-hand operation, other dialogue boxes are left in the same right alignment.
     */
    static Dialog confirm(Context context, String titleText, String message,
                          String positiveText, boolean danger, final Runnable onConfirm,
                          boolean positiveOnLeft) {
        LinearLayout content = column(context);
        content.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        TextView messageView = new TextView(context);
        messageView.setText(message);
        messageView.setTextColor(MUTED);
        messageView.setTextSize(13f);
        messageView.setLineSpacing(dp(context, 3), 1f);
        content.addView(messageView, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout actions = row(context);
        actions.setGravity(positiveOnLeft ? Gravity.START : Gravity.END);
        actions.setPadding(0, dp(context, 16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = secondary(context, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        Button confirm = danger ? danger(context, positiveText) : primary(context, positiveText);
        if (positiveOnLeft) {
            actions.addView(confirm, new LinearLayout.LayoutParams(WRAP, WRAP));
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(WRAP, WRAP);
            cancelParams.setMargins(dp(context, 8), 0, 0, 0);
            actions.addView(cancel, cancelParams);
        } else {
            actions.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
            LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(WRAP, WRAP);
            confirmParams.setMargins(dp(context, 8), 0, 0, 0);
            actions.addView(confirm, confirmParams);
        }
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (onConfirm != null) {
                    onConfirm.run();
                }
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = sheet(context, titleText, content);
        return holder[0];
    }

    static Dialog input(Context context, String titleText, String hint, String initial,
                        String positiveText, final InputHandler handler) {
        LinearLayout content = column(context);
        content.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        final EditText field = field(context, initial, hint, false);
        content.addView(field, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout actions = row(context);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(context, 16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = secondary(context, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button confirm = primary(context, positiveText);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        confirmParams.setMargins(dp(context, 8), 0, 0, 0);
        actions.addView(confirm, confirmParams);
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] == null) {
                    return;
                }
                if (handler == null || handler.onInput(field.getText().toString().trim(),
                        holder[0])) {
                    holder[0].dismiss();
                }
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = sheet(context, titleText, content);
        Window window = holder[0].getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        field.requestFocus();
        return holder[0];
    }
}
