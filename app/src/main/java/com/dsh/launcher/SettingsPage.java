package com.dsh.launcher;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

/** 设置页：工作区、端口、局域网、排序、隐藏文件。 */
public class SettingsPage {

    private final MainActivity act;
    private final Prefs prefs;
    private final View root;
    private final EditText edtWorkspace;
    private final EditText edtPort;
    private final Switch swLan;
    private final Switch swHidden;
    private final TextView tvSortValue;
    private final TextView tvWorkspaceHint;
    private final TextView tvDiagState;

    public SettingsPage(MainActivity act) {
        CrashLog.breadcrumb("SettingsPage:ctor 开始");
        this.act = act;
        this.prefs = Prefs.get(act);
        root = LayoutInflater.from(act).inflate(R.layout.page_settings, null);

        edtWorkspace = root.findViewById(R.id.edtWorkspace);
        edtPort = root.findViewById(R.id.edtPort);
        swLan = root.findViewById(R.id.swLan);
        swHidden = root.findViewById(R.id.swHidden);
        tvSortValue = root.findViewById(R.id.tvSortValue);
        tvWorkspaceHint = root.findViewById(R.id.tvWorkspaceHint);
        TextView tvVersion = root.findViewById(R.id.tvVersion);
        tvDiagState = root.findViewById(R.id.tvDiagState);

        root.findViewById(R.id.rowDiag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagSheet();
            }
        });

        tvVersion.setText(versionName());

        root.findViewById(R.id.btnWorkspaceSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveWorkspace();
            }
        });
        root.findViewById(R.id.btnWorkspaceCheck).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkWorkspace();
            }
        });
        root.findViewById(R.id.rowHidden).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swHidden.setChecked(!swHidden.isChecked());
            }
        });
        swHidden.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setShowHidden(checked);
                act.onSettingsChanged();
            }
        });
        root.findViewById(R.id.rowSort).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sortSheet();
            }
        });
        root.findViewById(R.id.btnReset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Util.confirm(act, act.getString(R.string.settings_reset),
                        act.getString(R.string.settings_reset), act.getString(R.string.dlg_ok),
                        true, new Util.Run() {
                            @Override
                            public void run() {
                                prefs.reset();
                                bind();
                                act.onSettingsChanged();
                                Util.toast(act, act.getString(R.string.settings_reset_done));
                            }
                        });
            }
        });

        swLan.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setLan(checked);
                act.onSettingsChanged();
            }
        });

        bind();
        CrashLog.breadcrumb("SettingsPage:ctor 完成");
    }

    public View view() {
        return root;
    }

    public void onShow() {
        bind();
    }

    private void bind() {
        edtWorkspace.setText(prefs.workspace());
        edtPort.setText(String.valueOf(prefs.port()));
        swLan.setChecked(prefs.lan());
        swHidden.setChecked(prefs.showHidden());
        tvSortValue.setText(sortLabel());
        if (tvDiagState != null) {
            tvDiagState.setText(CrashLog.lastReport().length() > 0
                    ? act.getString(R.string.diag_exists) + " · /sdcard/Download/" + CrashLog.NAME
                    : act.getString(R.string.diag_path));
        }
        tvWorkspaceHint.setText(R.string.settings_workspace_desc);
    }

    private String versionName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    private String sortLabel() {
        String k = prefs.sortKey();
        String base;
        if ("size".equals(k)) {
            base = act.getString(R.string.sort_size);
        } else if ("time".equals(k)) {
            base = act.getString(R.string.sort_time);
        } else {
            base = act.getString(R.string.sort_name);
        }
        return base + (prefs.sortAsc() ? " ↑" : " ↓");
    }

    private void saveWorkspace() {
        String p = edtWorkspace.getText().toString().trim();
        if (p.length() == 0 || !p.startsWith("/")) {
            Util.toast(act, act.getString(R.string.err_bad_name));
            return;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        prefs.setWorkspace(p);
        int port;
        try {
            port = Integer.parseInt(edtPort.getText().toString().trim());
        } catch (Throwable t) {
            port = Prefs.DEF_PORT;
        }
        prefs.setPort(port);
        edtPort.setText(String.valueOf(prefs.port()));
        act.onSettingsChanged();
        Util.toast(act, act.getString(R.string.settings_saved));
        checkWorkspace();
    }

    private void checkWorkspace() {
        final String p = edtWorkspace.getText().toString().trim();
        tvWorkspaceHint.setText(act.getString(R.string.state_loading));
        FsClient.list(act, p, new FsClient.Cb<java.util.ArrayList<FsEntry>>() {
            @Override
            public void done(java.util.ArrayList<FsEntry> v, String error) {
                if (error != null) {
                    tvWorkspaceHint.setText("✗ 无法读取：" + error);
                } else {
                    tvWorkspaceHint.setText("✓ 目录可读，共 " + v.size() + " 项");
                }
            }
        });
    }

    private void diagSheet() {
        final String rep = CrashLog.lastReport();
        Sheet s = Sheet.create(act, act.getString(R.string.settings_diag),
                rep.length() == 0 ? act.getString(R.string.diag_empty)
                        : act.getString(R.string.diag_exists) + " · /sdcard/Download/" + CrashLog.NAME);
        s.action("info", act.getString(R.string.diag_view), 0, new Util.Run() {
            @Override
            public void run() {
                String text = CrashLog.lastReport();
                if (text.length() == 0) {
                    text = act.getString(R.string.diag_empty);
                }
                if (text.length() > 3500) {
                    text = text.substring(0, 3500) + "\n…（已截断）";
                }
                new android.app.AlertDialog.Builder(act, R.style.AppTheme_Dialog)
                        .setTitle(R.string.settings_diag)
                        .setMessage(text)
                        .setPositiveButton(R.string.dlg_ok, null)
                        .show();
            }
        });
        s.action("copy", act.getString(R.string.sheet_copy_path), 0, new Util.Run() {
            @Override
            public void run() {
                Util.copy(act, "path", "/sdcard/Download/" + CrashLog.NAME);
                Util.toast(act, act.getString(R.string.clipboard_path));
            }
        });
        s.action("trash", act.getString(R.string.diag_clear), R.color.danger, new Util.Run() {
            @Override
            public void run() {
                CrashLog.clear();
                bind();
                Util.toast(act, act.getString(R.string.diag_cleared));
            }
        });
        s.cancel();
        s.show();
    }

    private void sortSheet() {
        final Sheet s = Sheet.create(act, act.getString(R.string.sheet_sort));
        final boolean asc = prefs.sortAsc();
        final boolean ff = prefs.folderFirst();
        s.action("sort", act.getString(R.string.sort_name), 0, new Util.Run() {
            @Override
            public void run() {
                prefs.setSort("name", asc, ff);
                afterSort();
            }
        });
        s.action("sort", act.getString(R.string.sort_size), 0, new Util.Run() {
            @Override
            public void run() {
                prefs.setSort("size", asc, ff);
                afterSort();
            }
        });
        s.action("sort", act.getString(R.string.sort_time), 0, new Util.Run() {
            @Override
            public void run() {
                prefs.setSort("time", asc, ff);
                afterSort();
            }
        });
        s.divider();
        s.action("refresh", asc ? act.getString(R.string.sort_desc) : act.getString(R.string.sort_asc),
                0, new Util.Run() {
                    @Override
                    public void run() {
                        prefs.setSort(prefs.sortKey(), !asc, ff);
                        afterSort();
                    }
                });
        s.action("folder", (ff ? "✓ " : "") + act.getString(R.string.sort_folder_first), 0,
                new Util.Run() {
                    @Override
                    public void run() {
                        prefs.setSort(prefs.sortKey(), asc, !ff);
                        afterSort();
                    }
                });
        s.cancel();
        s.show();
    }

    private void afterSort() {
        tvSortValue.setText(sortLabel());
        if (tvDiagState != null) {
            tvDiagState.setText(CrashLog.lastReport().length() > 0
                    ? act.getString(R.string.diag_exists) + " · /sdcard/Download/" + CrashLog.NAME
                    : act.getString(R.string.diag_path));
        }
        act.onSettingsChanged();
    }
}
