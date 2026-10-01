package com.dsh.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.provider.DocumentsContract;

/** 「打开所在位置 / 交给其他 App」的统一入口。 */
public final class FileActions {

    private FileActions() {
    }

    public static void openExternal(final Activity act, final String path, final boolean dir) {
        if (act == null || path == null) {
            return;
        }
        if (Util.isShared(path)) {
            if (!OpenWith.openShared(act, path, dir, Util.baseName(path))) {
                Util.toast(act, act.getString(R.string.open_with_none));
            }
            return;
        }

        final Prefs prefs = Prefs.get(act);
        final String dest = FsClient.exportDest(path, prefs.workspace(), prefs.sharedDir());
        final String type = dir ? DocumentsContract.Document.MIME_TYPE_DIR : Util.mimeOf(path);

        Sheet s = Sheet.create(act, act.getString(R.string.open_with_title),
                Util.baseName(path) + "\n" + act.getString(R.string.open_with_export_hint));
        s.action("external", act.getString(R.string.sheet_open_with), 0, new Util.Run() {
            @Override
            public void run() {
                mirrorThenOpen(act, path, dest, dir);
            }
        });
        s.action("upload", act.getString(R.string.sheet_save_phone) + "（镜像到共享存储）", 0,
                new Util.Run() {
                    @Override
                    public void run() {
                        Util.toast(act, act.getString(R.string.exporting));
                        FsClient.copyout(act, path, dest, new FsClient.Cb<String>() {
                            @Override
                            public void done(String v, String error) {
                                if (error != null) {
                                    Util.toast(act, act.getString(R.string.export_fail) + "：" + error);
                                } else {
                                    Util.toast(act, act.getString(R.string.export_done) + "：" + v);
                                }
                            }
                        });
                    }
                });
        s.action("sync", act.getString(R.string.sheet_sync_back), 0, new Util.Run() {
            @Override
            public void run() {
                Util.toast(act, act.getString(R.string.importing));
                FsClient.copyin(act, dest, path, new FsClient.Cb<String>() {
                    @Override
                    public void done(String v, String error) {
                        if (error != null) {
                            Util.toast(act, act.getString(R.string.import_fail) + "：" + error);
                        } else {
                            Util.toast(act, act.getString(R.string.import_done));
                        }
                    }
                });
            }
        });
        s.action("terminal", "尝试直接交给文件管理器（Termux 私有目录，需授权）", 0, new Util.Run() {
            @Override
            public void run() {
                if (!OpenWith.tryFileUri(act, path, type)) {
                    Util.toast(act, act.getString(R.string.open_with_none));
                }
            }
        });
        s.action("copy", act.getString(R.string.sheet_copy_path), 0, new Util.Run() {
            @Override
            public void run() {
                Util.copy(act, "path", path);
                Util.toast(act, act.getString(R.string.clipboard_path));
            }
        });
        s.cancel();
        s.show();
    }

    private static void mirrorThenOpen(final Activity act, final String path, final String dest,
                                       final boolean dir) {
        final Dialog[] holder = new Dialog[1];
        holder[0] = Busy.show(act, act.getString(R.string.exporting));
        FsClient.copyout(act, path, dest, new FsClient.Cb<String>() {
            @Override
            public void done(String v, String error) {
                Busy.hide(holder[0]);
                if (error != null) {
                    Util.toast(act, act.getString(R.string.export_fail) + "：" + error);
                    return;
                }
                Util.toast(act, act.getString(R.string.export_done) + "：" + v);
                if (!OpenWith.openShared(act, v, dir, Util.baseName(path))) {
                    Util.toast(act, act.getString(R.string.open_with_none));
                }
            }
        });
    }
}
