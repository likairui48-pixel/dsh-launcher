package com.dsh.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** 文件页：工作区文件的浏览与内容管理。 */
public class FilesPage {

    public static final int REQ_PICK = 7001;
    private static final long MAX_IMPORT = 32L * 1024 * 1024;

    private final MainActivity act;
    private final Prefs prefs;
    private final View root;
    private final ListView list;
    private final TextView tvPath;
    private final TextView stateTitle;
    private final TextView stateDesc;
    private final TextView stateAction;
    private final GlyphView stateGlyph;
    private final View stateBox;
    private final View progress;
    private final LinearLayout crumbRow;
    private final Adapter adapter;

    private final ArrayList<FsEntry> all = new ArrayList<>();
    private final ArrayList<FsEntry> shown = new ArrayList<>();

    private String dir;
    private String query;
    private String error;
    private boolean loading;
    private boolean started;

    public FilesPage(MainActivity act) {
        CrashLog.breadcrumb("FilesPage:ctor 开始");
        this.act = act;
        this.prefs = Prefs.get(act);
        root = LayoutInflater.from(act).inflate(R.layout.page_files, null);

        list = root.findViewById(R.id.list);
        tvPath = root.findViewById(R.id.tvPath);
        stateBox = root.findViewById(R.id.stateBox);
        stateTitle = root.findViewById(R.id.stateTitle);
        stateDesc = root.findViewById(R.id.stateDesc);
        stateAction = root.findViewById(R.id.stateAction);
        stateGlyph = root.findViewById(R.id.stateGlyph);
        progress = root.findViewById(R.id.progress);
        crumbRow = root.findViewById(R.id.crumbRow);

        dir = prefs.workspace();
        adapter = new Adapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < shown.size()) {
                    FsEntry e = shown.get(position);
                    if (e.dir) {
                        open(e.path);
                    } else {
                        viewSheet(e);
                    }
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < shown.size()) {
                    viewSheet(shown.get(position));
                    return true;
                }
                return false;
            }
        });

        root.findViewById(R.id.btnUp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                up();
            }
        });
        root.findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                query = null;
                reload();
            }
        });
        root.findViewById(R.id.btnMore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moreSheet();
            }
        });
        root.findViewById(R.id.fab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fabSheet();
            }
        });
        tvPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpDialog();
            }
        });
        stateAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reload();
            }
        });

        render();
        CrashLog.breadcrumb("FilesPage:ctor 完成");
    }

    public View view() {
        return root;
    }

    public void onShow() {
        if (!started) {
            started = true;
            open(prefs.workspace());
            return;
        }
        reload();
    }

    public void resetToWorkspace() {
        query = null;
        dir = prefs.workspace();
        started = true;
        reload();
    }

    public String currentDir() {
        return dir;
    }

    /* ===================== 导航 ===================== */

    public void open(String path) {
        if (path == null || path.length() == 0) {
            return;
        }
        query = null;
        dir = path;
        reload();
    }

    private void up() {
        if (query != null) {
            query = null;
            reload();
            return;
        }
        String p = Util.parent(dir);
        if (p != null && !p.equals(dir)) {
            open(p);
        } else {
            Util.toast(act, "已经在根目录了");
        }
    }

    /** 返回 true 表示这次返回键已被页面消费。 */
    public boolean onBack() {
        if (query != null) {
            query = null;
            reload();
            return true;
        }
        String parent = Util.parent(dir);
        if (parent != null && !parent.equals(dir) && dir.length() > 1) {
            open(parent);
            return true;
        }
        return false;
    }

    private void jumpDialog() {
        Util.input(act, "跳转到路径", act.getString(R.string.hint_path), dir, "跳转",
                new Util.TextCb() {
                    @Override
                    public void run(String text) {
                        open(text);
                    }
                });
    }

    private void crumb(String name, final String path, boolean current) {
        TextView t = new TextView(act);
        t.setText(name);
        t.setTextSize(12f);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setBackgroundResource(R.drawable.bg_crumb);
        int ph = Util.dp(act, 7);
        int pv = Util.dp(act, 3);
        t.setPadding(ph, pv, ph, pv);
        t.setTextColor(act.getColor(current ? R.color.textPrimary : R.color.textSecondary));
        t.setTypeface(null, current ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(path);
            }
        });
        crumbRow.addView(t);
    }

    private void separate() {
        TextView s = new TextView(act);
        s.setText("/");
        s.setTextSize(12f);
        s.setTextColor(act.getColor(R.color.textTertiary));
        int ph = Util.dp(act, 2);
        s.setPadding(ph, 0, ph, 0);
        crumbRow.addView(s);
    }

    private void renderCrumbs() {
        crumbRow.removeAllViews();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        String acc = "";
        for (String part : dir.split("/")) {
            if (part.length() == 0) {
                continue;
            }
            acc = acc + "/" + part;
            names.add(part);
            paths.add(acc);
        }
        if (names.isEmpty()) {
            crumb("根目录", "/", true);
            return;
        }
        int from = Math.max(0, names.size() - 4);
        if (from > 0) {
            crumb("…", paths.get(from - 1), false);
            separate();
        }
        for (int i = from; i < names.size(); i++) {
            if (i > from) {
                separate();
            }
            crumb(names.get(i), paths.get(i), i == names.size() - 1);
        }
    }

    /* ===================== 加载 ===================== */

    public void reload() {
        loading = true;
        error = null;
        render();
        if (query != null && query.length() > 0) {
            FsClient.search(act, dir, query, new FsClient.Cb<ArrayList<FsEntry>>() {
                @Override
                public void done(ArrayList<FsEntry> v, String e) {
                    finishLoad(v, e);
                }
            });
        } else {
            FsClient.list(act, dir, new FsClient.Cb<ArrayList<FsEntry>>() {
                @Override
                public void done(ArrayList<FsEntry> v, String e) {
                    finishLoad(v, e);
                }
            });
        }
    }

    private void finishLoad(ArrayList<FsEntry> v, String e) {
        loading = false;
        if (e != null) {
            error = e;
            all.clear();
        } else {
            error = null;
            all.clear();
            if (v != null) {
                all.addAll(v);
            }
        }
        applySort();
        render();
    }

    private void applySort() {
        shown.clear();
        boolean hidden = prefs.showHidden();
        for (FsEntry e : all) {
            if (!hidden && e.hidden()) {
                continue;
            }
            shown.add(e);
        }
        final boolean ff = prefs.folderFirst();
        final boolean asc = prefs.sortAsc();
        final String key = prefs.sortKey();
        Collections.sort(shown, new Comparator<FsEntry>() {
            @Override
            public int compare(FsEntry a, FsEntry b) {
                if (ff && a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                int c;
                if ("size".equals(key)) {
                    c = Long.compare(a.size, b.size);
                } else if ("time".equals(key)) {
                    c = Long.compare(a.mtime, b.mtime);
                } else {
                    c = a.name.compareToIgnoreCase(b.name);
                }
                if (c == 0) {
                    c = a.name.compareTo(b.name);
                }
                return asc ? c : -c;
            }
        });
    }

    private void render() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        tvPath.setText(query != null ? "搜索：" + query : shortPath(dir));
        renderCrumbs();
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            stateBox.setVisibility(View.GONE);
            return;
        }
        if (error != null) {
            stateBox.setVisibility(View.VISIBLE);
            stateGlyph.setGlyph("info");
            stateGlyph.setGlyphTint(act.getColor(R.color.danger));
            stateTitle.setText(R.string.state_error_title);
            stateDesc.setText(error);
            stateAction.setVisibility(View.VISIBLE);
        } else if (shown.isEmpty()) {
            stateBox.setVisibility(View.VISIBLE);
            stateGlyph.setGlyph("folder");
            stateGlyph.setGlyphTint(act.getColor(R.color.textTertiary));
            stateTitle.setText(query != null ? "没有匹配的文件" : act.getString(R.string.state_empty_title));
            stateDesc.setText(query != null ? ("关键词：" + query) : act.getString(R.string.state_empty_desc));
            stateAction.setVisibility(View.GONE);
        } else {
            stateBox.setVisibility(View.GONE);
        }
    }

    private String shortPath(String p) {
        String ws = prefs.workspace();
        if (p != null && ws != null && p.equals(ws)) {
            return act.getString(R.string.files_root);
        }
        if (p != null && ws != null && p.startsWith(ws + "/")) {
            return act.getString(R.string.files_root) + p.substring(ws.length());
        }
        return p;
    }

    /* ===================== 动作面板 ===================== */

    private void fabSheet() {
        Sheet s = Sheet.create(act, act.getString(R.string.fab_new), shortPath(dir));
        s.action("folder", act.getString(R.string.sheet_new_folder), 0, new Util.Run() {
            @Override
            public void run() {
                newFolder();
            }
        });
        s.action("file", act.getString(R.string.sheet_new_file), 0, new Util.Run() {
            @Override
            public void run() {
                newFile();
            }
        });
        s.action("download", act.getString(R.string.sheet_import), 0, new Util.Run() {
            @Override
            public void run() {
                pickImport();
            }
        });
        s.action("upload", act.getString(R.string.sheet_export) + "（整个目录镜像）", 0,
                new Util.Run() {
                    @Override
                    public void run() {
                        exportDir();
                    }
                });
        s.divider();
        s.action("refresh", act.getString(R.string.sheet_refresh), 0, new Util.Run() {
            @Override
            public void run() {
                reload();
            }
        });
        s.cancel();
        s.show();
    }

    private void moreSheet() {
        final Sheet s = Sheet.create(act, "更多", shortPath(dir));
        s.action("target", act.getString(R.string.sheet_set_workspace), 0, new Util.Run() {
            @Override
            public void run() {
                prefs.setWorkspace(dir);
                act.onSettingsChanged();
                Util.toast(act, "已设为工作区：" + dir);
            }
        });
        s.action("external", act.getString(R.string.sheet_open_with), 0, new Util.Run() {
            @Override
            public void run() {
                FileActions.openExternal(act, dir, true);
            }
        });
        s.action("copy", act.getString(R.string.sheet_copy_path), 0, new Util.Run() {
            @Override
            public void run() {
                Util.copy(act, "path", dir);
                Util.toast(act, act.getString(R.string.clipboard_path));
            }
        });
        s.divider();
        s.action("search", act.getString(R.string.sheet_deep_search), 0, new Util.Run() {
            @Override
            public void run() {
                deepSearch();
            }
        });
        s.action("eye", (prefs.showHidden() ? "✓ " : "") + act.getString(R.string.sheet_hidden),
                0, new Util.Run() {
                    @Override
                    public void run() {
                        prefs.setShowHidden(!prefs.showHidden());
                        applySort();
                        render();
                    }
                });
        s.action("sort", act.getString(R.string.sheet_sort) + " · " + sortText(), 0,
                new Util.Run() {
                    @Override
                    public void run() {
                        sortSheet();
                    }
                });
        s.divider();
        s.action("home", act.getString(R.string.chip_home), 0, new Util.Run() {
            @Override
            public void run() {
                open(Prefs.TERMUX_HOME);
            }
        });
        s.action("sd", act.getString(R.string.chip_shared), 0, new Util.Run() {
            @Override
            public void run() {
                open("/sdcard");
            }
        });
        s.action("download", act.getString(R.string.chip_download), 0, new Util.Run() {
            @Override
            public void run() {
                open("/sdcard/Download");
            }
        });
        s.action("folder", act.getString(R.string.files_root), 0, new Util.Run() {
            @Override
            public void run() {
                open(prefs.workspace());
            }
        });
        s.cancel();
        s.show();
    }

    private String sortText() {
        String k = prefs.sortKey();
        String base = "size".equals(k) ? act.getString(R.string.sort_size)
                : ("time".equals(k) ? act.getString(R.string.sort_time)
                : act.getString(R.string.sort_name));
        return base + (prefs.sortAsc() ? " ↑" : " ↓");
    }

    private void sortSheet() {
        final boolean asc = prefs.sortAsc();
        final boolean ff = prefs.folderFirst();
        Sheet s = Sheet.create(act, act.getString(R.string.sheet_sort));
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
        applySort();
        render();
    }

    private void deepSearch() {
        Util.input(act, act.getString(R.string.sheet_deep_search), "关键词", null, "搜索",
                new Util.TextCb() {
                    @Override
                    public void run(String text) {
                        query = text;
                        reload();
                    }
                });
    }

    private void viewSheet(final FsEntry e) {
        Sheet s = Sheet.create(act, e.name, shortPath(e.path));
        if (!e.dir) {
            s.action("edit", act.getString(R.string.sheet_edit), 0, new Util.Run() {
                @Override
                public void run() {
                    editFile(e);
                }
            });
            s.action("external", act.getString(R.string.sheet_open_file_with), 0, new Util.Run() {
                @Override
                public void run() {
                    FileActions.openExternal(act, e.path, false);
                }
            });
        } else {
            s.action("folder", "打开目录", 0, new Util.Run() {
                @Override
                public void run() {
                    open(e.path);
                }
            });
            s.action("external", act.getString(R.string.sheet_open_with), 0, new Util.Run() {
                @Override
                public void run() {
                    FileActions.openExternal(act, e.path, true);
                }
            });
        }
        s.action("upload", act.getString(R.string.sheet_export), 0, new Util.Run() {
            @Override
            public void run() {
                exportOne(e);
            }
        });
        s.action("edit", act.getString(R.string.sheet_rename), 0, new Util.Run() {
            @Override
            public void run() {
                rename(e);
            }
        });
        s.action("copy", act.getString(R.string.sheet_copy), 0, new Util.Run() {
            @Override
            public void run() {
                duplicate(e);
            }
        });
        s.action("info", act.getString(R.string.sheet_copy_path), 0, new Util.Run() {
            @Override
            public void run() {
                Util.copy(act, "path", e.path);
                Util.toast(act, act.getString(R.string.clipboard_path));
            }
        });
        s.divider();
        s.action("info", act.getString(R.string.sheet_info), 0, new Util.Run() {
            @Override
            public void run() {
                info(e);
            }
        });
        s.action("trash", act.getString(R.string.sheet_delete), R.color.danger, new Util.Run() {
            @Override
            public void run() {
                confirmDelete(e);
            }
        });
        s.cancel();
        s.show();
    }

    /* ===================== 具体操作 ===================== */

    private void editFile(FsEntry e) {
        if (!e.isTextLike()) {
            Util.toast(act, "这类文件建议用其他应用打开");
            return;
        }
        if (e.size > FsClient.MAX_EDIT) {
            Util.confirm(act, e.name, "文件有 " + Util.size(e.size) + "，只加载前面一部分，继续编辑吗？",
                    "继续", false, new Util.Run() {
                        @Override
                        public void run() {
                            startEditor(e.path);
                        }
                    });
            return;
        }
        startEditor(e.path);
    }

    private void startEditor(String path) {
        Intent i = new Intent(act, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_PATH, path);
        try {
            act.startActivity(i);
        } catch (Throwable t) {
            Util.toast(act, "打开编辑器失败");
        }
    }

    private void newFolder() {
        Util.input(act, act.getString(R.string.dlg_new_folder_title),
                act.getString(R.string.hint_name), null, act.getString(R.string.dlg_ok),
                new Util.TextCb() {
                    @Override
                    public void run(String text) {
                        final Dialog busy = Busy.show(act, act.getString(R.string.busy_wait));
                        FsClient.mkdir(act, Util.join(dir, text), new FsClient.Cb<String>() {
                            @Override
                            public void done(String v, String error) {
                                Busy.hide(busy);
                                if (error != null) {
                                    Util.toast(act, act.getString(R.string.op_fail) + "：" + error);
                                } else {
                                    reload();
                                }
                            }
                        });
                    }
                });
    }

    private void newFile() {
        Util.input(act, act.getString(R.string.dlg_new_file_title),
                act.getString(R.string.hint_name), "note.md", act.getString(R.string.dlg_ok),
                new Util.TextCb() {
                    @Override
                    public void run(final String text) {
                        final Dialog busy = Busy.show(act, act.getString(R.string.busy_wait));
                        final String path = Util.join(dir, text);
                        FsClient.touch(act, path, new FsClient.Cb<String>() {
                            @Override
                            public void done(String v, String error) {
                                Busy.hide(busy);
                                if (error != null) {
                                    Util.toast(act, act.getString(R.string.op_fail) + "：" + error);
                                    return;
                                }
                                reload();
                                startEditor(path);
                            }
                        });
                    }
                });
    }

    private void rename(final FsEntry e) {
        Util.input(act, act.getString(R.string.dlg_rename_title), act.getString(R.string.hint_name),
                e.name, act.getString(R.string.dlg_ok), new Util.TextCb() {
                    @Override
                    public void run(String text) {
                        if (text.equals(e.name)) {
                            return;
                        }
                        final Dialog busy = Busy.show(act, act.getString(R.string.busy_wait));
                        FsClient.rename(act, e.path, Util.join(dir, text), new FsClient.Cb<String>() {
                            @Override
                            public void done(String v, String error) {
                                Busy.hide(busy);
                                if (error != null) {
                                    Util.toast(act, act.getString(R.string.op_fail) + "：" + error);
                                } else {
                                    reload();
                                }
                            }
                        });
                    }
                });
    }

    private void duplicate(final FsEntry e) {
        String base = e.name;
        String ext = Util.ext(base);
        String stem = ext.length() > 0 ? base.substring(0, base.length() - ext.length() - 1) : base;
        String suffix = ext.length() > 0 ? "." + ext : "";
        String target = Util.join(dir, stem + "-copy" + suffix);
        int n = 2;
        while (exists(target) && n < 50) {
            target = Util.join(dir, stem + "-copy" + n + suffix);
            n++;
        }
        final Dialog busy = Busy.show(act, act.getString(R.string.busy_wait));
        final String dest = target;
        FsClient.copy(act, e.path, dest, new FsClient.Cb<String>() {
            @Override
            public void done(String v, String error) {
                Busy.hide(busy);
                if (error != null) {
                    Util.toast(act, act.getString(R.string.op_fail) + "：" + error);
                } else {
                    reload();
                }
            }
        });
    }

    private boolean exists(String path) {
        for (FsEntry e : all) {
            if (e.path.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private void confirmDelete(final FsEntry e) {
        Util.confirm(act, act.getString(R.string.dlg_delete_title),
                e.name + "\n" + act.getString(R.string.dlg_delete_msg),
                act.getString(R.string.sheet_delete), true, new Util.Run() {
                    @Override
                    public void run() {
                        final Dialog busy = Busy.show(act, act.getString(R.string.busy_wait));
                        FsClient.delete(act, e.path, new FsClient.Cb<String>() {
                            @Override
                            public void done(String v, String error) {
                                Busy.hide(busy);
                                if (error != null) {
                                    Util.toast(act, act.getString(R.string.op_fail) + "：" + error);
                                } else {
                                    Util.toast(act, act.getString(R.string.op_done));
                                    reload();
                                }
                            }
                        });
                    }
                });
    }

    private void exportOne(final FsEntry e) {
        final String dest = FsClient.exportDest(e.path, prefs.workspace(), prefs.sharedDir());
        final Dialog busy = Busy.show(act, act.getString(R.string.exporting));
        FsClient.copyout(act, e.path, dest, new FsClient.Cb<String>() {
            @Override
            public void done(String v, String error) {
                Busy.hide(busy);
                if (error != null) {
                    Util.toast(act, act.getString(R.string.export_fail) + "：" + error);
                } else {
                    Util.toast(act, act.getString(R.string.export_done) + "：" + v);
                }
            }
        });
    }

    private void exportDir() {
        final String dest = FsClient.exportDest(dir, prefs.workspace(), prefs.sharedDir());
        final Dialog busy = Busy.show(act, act.getString(R.string.exporting));
        FsClient.copyout(act, dir, dest, new FsClient.Cb<String>() {
            @Override
            public void done(String v, String error) {
                Busy.hide(busy);
                if (error != null) {
                    Util.toast(act, act.getString(R.string.export_fail) + "：" + error);
                    return;
                }
                Util.toast(act, act.getString(R.string.export_done) + "：" + v);
                FileActions.openExternal(act, v, true);
            }
        });
    }

    private void info(FsEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("路径：").append(e.path).append('\n');
        sb.append("类型：").append(e.dir ? "目录" : (e.link ? "符号链接" : "文件")).append('\n');
        if (!e.dir) {
            sb.append("大小：").append(Util.size(e.size)).append("（").append(e.size).append(" 字节）").append('\n');
        }
        sb.append("修改时间：").append(Util.time(e.mtime));
        if (e.linkTarget != null && e.linkTarget.length() > 0) {
            sb.append('\n').append("指向：").append(e.linkTarget);
        }
        new android.app.AlertDialog.Builder(act, R.style.AppTheme_Dialog)
                .setTitle(e.name)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.dlg_ok, null)
                .show();
    }

    /* ===================== 从手机导入 ===================== */

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            act.startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Util.toast(act, "没有可用的文件选择器");
        }
    }

    public void onImportResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        try {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        } catch (Throwable ignored) {
        }
        if (uris.isEmpty()) {
            return;
        }
        final Dialog busy = Busy.show(act, act.getString(R.string.importing));
        importNext(uris, 0, busy, 0, 0);
    }

    private void importNext(final ArrayList<Uri> uris, final int idx, final Dialog busy,
                            final int okCount, final int failCount) {
        if (idx >= uris.size()) {
            Busy.hide(busy);
            Util.toast(act, act.getString(R.string.import_done) + "：" + okCount
                    + (failCount > 0 ? "，失败 " + failCount : ""));
            reload();
            return;
        }
        final Uri uri = uris.get(idx);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = null;
                byte[] bytes = null;
                String err = null;
                try {
                    name = displayName(uri);
                    bytes = readAll(uri);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final String fName = name;
                final byte[] fBytes = bytes;
                final String fErr = err;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fErr != null || fBytes == null) {
                            Util.toast(act, "跳过：" + (fErr == null ? "读取失败" : fErr));
                            importNext(uris, idx + 1, busy, okCount, failCount + 1);
                            return;
                        }
                        String safe = uniqueName(fName == null || fName.length() == 0
                                ? "import-" + idx : fName);
                        FsClient.write(act, Util.join(dir, safe), fBytes, new FsClient.Cb<Long>() {
                            @Override
                            public void done(Long v, String error) {
                                importNext(uris, idx + 1, busy,
                                        error == null ? okCount + 1 : okCount,
                                        error == null ? failCount : failCount + 1);
                            }
                        });
                    }
                });
            }
        }).start();
    }

    private String uniqueName(String name) {
        if (!exists(Util.join(dir, name))) {
            return name;
        }
        String ext = Util.ext(name);
        String stem = ext.length() > 0 ? name.substring(0, name.length() - ext.length() - 1) : name;
        String suffix = ext.length() > 0 ? "." + ext : "";
        for (int i = 2; i < 100; i++) {
            String candidate = stem + "-" + i + suffix;
            if (!exists(Util.join(dir, candidate))) {
                return candidate;
            }
        }
        return name;
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = act.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && s.length() > 0) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "import" : last;
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = act.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("无法打开输入流");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_IMPORT) {
                    throw new Exception("文件超过 " + Util.size(MAX_IMPORT));
                }
                bos.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return bos.toByteArray();
    }

    /* ===================== 列表适配器 ===================== */

    private int tintFor(FsEntry e) {
        String g = e.glyph();
        if ("folder".equals(g)) {
            return act.getColor(R.color.brand);
        }
        if ("image".equals(g)) {
            return act.getColor(R.color.success);
        }
        if ("audio".equals(g) || "video".equals(g)) {
            return act.getColor(R.color.warning);
        }
        if ("archive".equals(g) || "apk".equals(g)) {
            return act.getColor(R.color.textSecondary);
        }
        return act.getColor(R.color.brandDark);
    }

    private static final class Holder {
        GlyphView glyph;
        TextView name;
        TextView meta;
        TextView tag;
    }

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(act).inflate(R.layout.row_entry, parent, false);
                h = new Holder();
                h.glyph = convertView.findViewById(R.id.rowGlyph);
                h.name = convertView.findViewById(R.id.rowName);
                h.meta = convertView.findViewById(R.id.rowMeta);
                h.tag = convertView.findViewById(R.id.rowTag);
                convertView.setTag(h);
            } else {
                h = (Holder) convertView.getTag();
            }
            FsEntry e = shown.get(position);
            h.name.setText(e.name);
            h.meta.setText(e.metaText());
            h.glyph.setGlyph(e.glyph());
            h.glyph.setGlyphTint(tintFor(e));
            String tag = e.tagText();
            if (tag == null) {
                h.tag.setVisibility(View.GONE);
            } else {
                h.tag.setVisibility(View.VISIBLE);
                h.tag.setText(tag);
            }
            convertView.setAlpha(e.hidden() ? 0.5f : 1f);
            return convertView;
        }
    }
}
