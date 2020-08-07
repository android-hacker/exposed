package me.weishu.exposed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Create by wakfu on 2020/7/14
 */
public class InitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Log.e("InitProvider", "onCreate");
        Context context = getContext();
        if (context != null) {
            ExposedBridge.initOnce(context, context.getApplicationInfo(), context.getClassLoader());
            ExposedBridge.loadModule(context);
        } else {
            Log.e("InitProvider", "onCreateFail");
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
