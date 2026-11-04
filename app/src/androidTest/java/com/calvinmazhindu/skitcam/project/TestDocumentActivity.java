package com.calvinmazhindu.skitcam.project;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Standalone test-APK component: Java avoids depending on the target APK's Kotlin runtime. */
public final class TestDocumentActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!getPackageName().endsWith(".m11validation.test")) { finish(); return; }
        if (saved == null) startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, getIntent().getStringExtra("filename"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 11);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request == 11 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri=data.getData();
            int flags=Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (!getPackageName().concat(".project-docs").equals(uri.getAuthority())) getContentResolver().takePersistableUriPermission(uri,flags);
            String target=getPackageName().substring(0,getPackageName().length()-5);
            grantUriPermission(target,uri,flags | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            if (!getSharedPreferences("project-provider",0).edit().putString("bridgeUri",uri.toString()).putString("bridgeTag",getIntent().getStringExtra("tag")).commit()) throw new IllegalStateException("Checkpoint save failed");
        }
        finish();
    }
}
