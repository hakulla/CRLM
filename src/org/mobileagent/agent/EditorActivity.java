package org.mobileagent.agent;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class EditorActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int BG = AgentKit.BG;
    private static final int CARD = AgentKit.CARD;
    private static final int TEXT = AgentKit.TEXT;
    private static final int MUTED = AgentKit.MUTED;
    private static final int MAX_BYTES = 512 * 1024;

    private Workspace workspace;
    private String relativePath;
    private EditText editor;
    private Button saveButton;
    private TextView note;
    private boolean dirty;
    private boolean loading;
    private String parentLabel = "Workspace Root Directory";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        String workspacePath = getIntent().getStringExtra("workspace");
        workspace = new Workspace(this).sub(workspacePath);
        relativePath = getIntent().getStringExtra("path");
        if (relativePath == null || relativePath.trim().isEmpty()) {
            Toast.makeText(this, "Missing file path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(buildUi());
        loadFile();
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(AgentKit.dp(this, 10), AgentKit.dp(this, 8),
                AgentKit.dp(this, 12), AgentKit.dp(this, 8));
        root.addView(toolbar, new LinearLayout.LayoutParams(MATCH, WRAP));

        ImageView back = AgentKit.iconButton(this, "ic_back", TEXT, 40);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithCheck();
            }
        });
        toolbar.addView(back, new LinearLayout.LayoutParams(AgentKit.dp(this, 40),
                AgentKit.dp(this, 40)));
        LinearLayout titleBlock = AgentKit.column(this);
        titleBlock.setPadding(AgentKit.dp(this, 10), 0, AgentKit.dp(this, 6), 0);
        int slash = relativePath.lastIndexOf('/');
        String parent = slash < 0 ? "Workspace Root Directory" : relativePath.substring(0, slash);
        parentLabel = parent;
        String fileName = slash < 0 ? relativePath : relativePath.substring(slash + 1);
        TextView title = AgentKit.title(this, fileName, 15f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
        note = AgentKit.hint(this, parent + " · UTF-8");
        note.setSingleLine(true);
        note.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(note, new LinearLayout.LayoutParams(MATCH, WRAP));
        toolbar.addView(titleBlock, new LinearLayout.LayoutParams(0, WRAP, 1f));

        saveButton = AgentKit.primary(this, "Save");
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFile();
            }
        });
        toolbar.addView(saveButton, new LinearLayout.LayoutParams(WRAP, WRAP));

        View divider = new View(this);
        divider.setBackgroundColor(AgentKit.DIVIDER);
        root.addView(divider, new LinearLayout.LayoutParams(MATCH, 1));

        editor = new EditText(this);
        editor.setTextColor(AgentKit.TEXT);
        editor.setHintTextColor(AgentKit.DIM);
        editor.setHint("Edit text here...");
        editor.setTextSize(14.5f);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setLineSpacing(AgentKit.dp(this, 3), 1f);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(AgentKit.dp(this, 14), AgentKit.dp(this, 12),
                AgentKit.dp(this, 14), AgentKit.dp(this, 12));
        editor.setBackground(AgentKit.rounded(this, CARD, 14));
        editor.setSingleLine(false);
        editor.setHorizontallyScrolling(false);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        editorParams.setMargins(AgentKit.dp(this, 12), AgentKit.dp(this, 6),
                AgentKit.dp(this, 12), AgentKit.dp(this, 10));
        root.addView(editor, editorParams);
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loading || dirty) {
                    return;
                }
                dirty = true;
                saveButton.setText("Save •");
            }
        });
        return root;
    }

    private void loadFile() {
        try {
            File file = workspace.resolve(relativePath);
            if (file.isDirectory()) {
                Toast.makeText(this, "This is the directory.", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            if (!file.isFile()) {
                loading = true;
                editor.setText("");
                loading = false;
                dirty = false;
                saveButton.setText("Save");
                return;
            }
            if (file.length() > MAX_BYTES) {
                Toast.makeText(this, "File Over 512KB, Unedited", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            byte[] data = readFile(file);
            for (byte value : data) {
                if (value == 0) {
                    Toast.makeText(this, "Binary files are not supported for editing", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
            loading = true;
            editor.setText(new String(data, StandardCharsets.UTF_8));
            loading = false;
            dirty = false;
            saveButton.setText("Save");
            note.setText(parentLabel + " · UTF-8");
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void saveFile() {
        try {
            File file = workspace.resolve(relativePath);
            if (file.isDirectory()) {
                Toast.makeText(this, "Path is directory", Toast.LENGTH_SHORT).show();
                return;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Toast.makeText(this, "Could not create directory", Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] data = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_BYTES) {
                Toast.makeText(this, "More than content 512KB", Toast.LENGTH_SHORT).show();
                return;
            }
            FileOutputStream output = new FileOutputStream(file, false);
            try {
                output.write(data);
            } finally {
                output.close();
            }
            dirty = false;
            saveButton.setText("Save");
            note.setText(parentLabel + " · UTF-8 · Saved");
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void finishWithCheck() {
        if (!dirty) {
            finish();
            return;
        }
        AgentKit.confirm(this, "Abandoning changes?", "Unsaved changes are lost.", "Abandon", true,
                new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
    }

    @Override
    public void onBackPressed() {
        finishWithCheck();
    }

    private static byte[] readFile(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
