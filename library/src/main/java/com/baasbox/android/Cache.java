package com.baasbox.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.baasbox.android.impl.DiskLruCache;
import com.baasbox.android.impl.Logger;

import java.io.*;

/**
 * Created by Andrea Tortorella on 05/02/14.
 */
final class Cache {
// ------------------------------ FIELDS ------------------------------

    private static final String BAASBOX_CACHE_DIR = "baasbox-cache-dir";
    private static final long MAX_CACHE_SIZE = 10 * 1024 * 1024;

    private final DiskLruCache mLruCache;

// --------------------------- CONSTRUCTORS ---------------------------
    Cache(Context context) {
        try {
            mLruCache = DiskLruCache.open(getCacheDir(context),
                    appVersion(context),
                    1,
                    MAX_CACHE_SIZE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File getCacheDir(Context context) {
        return new File(context.getCacheDir(), BAASBOX_CACHE_DIR);
    }

    private static int appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new AssertionError(e);
        }
    }

// -------------------------- OTHER METHODS --------------------------

    public CacheStream beginStream(String id) throws BaasException {
        DiskLruCache.Editor editor = null;
        OutputStream out = null;
        try {
            editor = mLruCache.edit(id);
            out = editor.newOutputStream(0);
            return new CacheStream(out, editor);
        } catch (IOException e) {
            throw new BaasIOException(e);
        }
    }

    public byte[] get(String id) {
        DiskLruCache.Snapshot s = null;
        DataInputStream din = null;

        try {
            s = mLruCache.get(id);
            if (s == null) return null;
            int len = (int) s.getLength(0);
            byte[] bytes = new byte[len];
            din = new DataInputStream(s.getInputStream(0));
            din.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (din != null) {
                try {
                    din.close();
                } catch (IOException e) {
                    // ignored
                }
            }


            if (s != null) {
                s.close();
            }
        }
    }

    public BaasStream getStream(String id) throws BaasIOException {
        DiskLruCache.Snapshot s = null;
        try {
            s = mLruCache.get(id);
            if (s == null) return null;
            return new BaasStream(id, s);
        } catch (IOException e) {
            throw new BaasIOException("Error while reading from cache", e);
        }
    }

    public void put(String id, byte[] data) {
        DiskLruCache.Editor edit = null;
        OutputStream out = null;
        try {
            edit = mLruCache.edit(id);
            out = edit.newOutputStream(0);
            out.write(data);
            out.flush();
            edit.commit();
        } catch (IOException e) {
            Logger.error(e, "Error using cache");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // ignored
                }
            }
            if (edit != null) {
                edit.abortUnlessCommitted();
            }
        }
    }

// -------------------------- INNER CLASSES --------------------------

    static class CacheStream extends FilterOutputStream {
        private final DiskLruCache.Editor editor;

        CacheStream(OutputStream out, DiskLruCache.Editor editor) {
            super(out);
            this.editor = editor;
        }

        public void commit() throws BaasException {
            try {
                editor.commit();
            } catch (IOException e) {
                throw new BaasException(e);
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (editor != null) {
                editor.abortUnlessCommitted();
            }
        }
    }
}
