package com.dsh.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;

/** 工作区文件的内容编辑：读取走 FsClient，保存走分片回写。 */
public class EditorActivity extends Activity {

    public static final String EXTRA_PATH = "path";

    private String path;
    private EditText edt;
    private TextView tvName;
    private TextView tvMeta;
    private TextView btnSave;
    private boolean dirty;
    private boolean loaded;
    private long fileSize;
    private boolean binary;
    private boolean truncated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || path.length() == 0) {
            finish();
            return;
        }

        tvName = findViewById(R.id.tvName);
        tvMeta = findViewById(R.id.tvMeta);
        edt = findViewById(R.id.edt);
        btnSave = findViewById(R.id.btnSave);

        tvName.setText(Util.baseName(path));
        tvMeta.setText(path);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptExit();
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btnOpenWith).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FileActions.openExternal(EditorActivity.this, path, false);
            }
        });

        edt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loaded) {
                    dirty = true;
                    refreshMeta();
                }
            }
        });

        load();
    }

    private void load() {
        final android.app.Dialog busy = Busy.show(this, getString(R.string.busy_wait));
        FsClient.readAll(this, path, FsClient.MAX_EDIT, new FsClient.Cb<FsClient.ReadResult>() {
            @Override
            public void done(FsClient.ReadResult v, String error) {
                Busy.hide(busy);
                if (error != null) {
                    Util.toast(EditorActivity.this, error);
                    tvMeta.setText(error);
                    loaded = true;
                    return;
                }
                fileSize = v.size;
                binary = v.binary;
                truncated = v.truncated;
                edt.setText(v.text);
                edt.setSelection(0);
                loaded = true;
                dirty = false;
                refreshMeta();
                if (truncated) {
                    Util.toast(EditorActivity.this, getString(R.string.editor_too_big));
                } else if (binary) {
                    Util.toast(EditorActivity.this, getString(R.string.editor_binary));
                }
            }
        });
    }

    private void refreshMeta() {
        String text = edt.getText().toString();
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        lines++;
        StringBuilder sb = new StringBuilder();
        sb.append(Util.size(fileSize)).append(" · ").append(lines)
                .append(getString(R.string.editor_lines));
        if (dirty) {
            sb.append(" · ").append(getString(R.string.editor_unsaved));
        } else if (loaded) {
            sb.append(" · ").append(getString(R.string.editor_saved));
        }
        tvMeta.setText(sb.toString());
        btnSave.setAlpha(dirty ? 1f : 0.45f);
    }

    private void save() {
        final byte[] data = edt.getText().toString().getBytes(StandardCharsets.UTF_8);
        final android.app.Dialog busy = Busy.show(this, getString(R.string.busy_wait));
        FsClient.write(this, path, data, new FsClient.Cb<Long>() {
            @Override
            public void done(Long v, String error) {
                Busy.hide(busy);
                if (error != null) {
                    Util.toast(EditorActivity.this, getString(R.string.op_fail) + "：" + error);
                    return;
                }
                dirty = false;
                fileSize = v == null ? data.length : v;
                truncated = false;
                refreshMeta();
                Util.toast(EditorActivity.this, getString(R.string.editor_saved));
            }
        });
    }

    private void attemptExit() {
        if (!dirty) {
            finish();
            return;
        }
        Util.confirm(this, getString(R.string.editor_discard_title),
                getString(R.string.editor_discard_msg), getString(R.string.editor_discard_ok), true,
                new Util.Run() {
                    @Override
                    public void run() {
                        dirty = false;
                        finish();
                    }
                });
    }

    @Override
    public void onBackPressed() {
        attemptExit();
    }
}
