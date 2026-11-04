package com.calvinmazhindu.skitcam.project;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Simulated destinations, installed only in the test APK. Never exposes recordings. */
public final class TestProjectProvider extends DocumentsProvider {
    private final ConcurrentHashMap<String,CountDownLatch> pending=new ConcurrentHashMap<>();
    private File root() { File f=new File(getContext().getFilesDir(),"project-provider"); if (!f.isDirectory() && !f.mkdirs()) throw new IllegalStateException(); return f; }
    private SharedPreferences prefs() { return getContext().getSharedPreferences("project-provider",0); }
    private String authority() { return getContext().getPackageName()+".project-docs"; }
    private String mode() { return prefs().getString("mode","normal"); }
    private File metadata(String id) { if (!id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException(); return new File(root(),id+".meta"); }
    private File bytes(String id) { return new File(root(),id+".zip"); }
    private String name(String id) throws FileNotFoundException {
        try (Reader in=new InputStreamReader(new FileInputStream(metadata(id)),StandardCharsets.UTF_8)) { Properties p=new Properties();p.load(in);return p.getProperty("name"); }
        catch(IOException e) { throw new FileNotFoundException(e.toString()); }
    }
    private void saveName(String id,String name) {
        try (Writer out=new OutputStreamWriter(new FileOutputStream(metadata(id)),StandardCharsets.UTF_8)) { Properties p=new Properties();p.setProperty("name",name);p.store(out,""); }
        catch(IOException e) { throw new IllegalStateException(e); }
    }
    private void grant(String id) {
        String pkg=prefs().getString("grantTo",null);
        if (pkg!=null) getContext().grantUriPermission(pkg,DocumentsContract.buildDocumentUri(authority(),id),Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if (!method.startsWith("test:")) return super.call(method,arg,extras);
        if (getContext().getPackageManager().checkSignatures(Binder.getCallingUid(),android.os.Process.myUid())!=PackageManager.SIGNATURE_MATCH) throw new SecurityException("Test harness only");
        Bundle b=new Bundle();
        try {
            if (method.equals("test:configure")) {
                if (!prefs().edit().putString("mode",extras.getString("mode")).putString("grantTo",extras.getString("grantTo")).commit()) throw new IllegalStateException();
            } else if (method.equals("test:create")) {
                b.putString("uri",DocumentsContract.buildDocumentUri(authority(),createDocument("root","application/zip",extras.getString("name"))).toString());
            } else if (method.equals("test:grant")) { grant(extras.getString("id"));
            } else if (method.equals("test:stats")) {
                File[] files=root().listFiles((d,n)->n.endsWith(".meta")); b.putInt("documents",files==null?0:files.length);
                b.putString("lastUri",prefs().getString("lastUri",null));b.putInt("writes",prefs().getInt("writes",0));
                b.putString("bridgeUri",prefs().getString("bridgeUri",null));b.putString("bridgeTag",prefs().getString("bridgeTag",null));
            } else throw new IllegalArgumentException();
        } catch(FileNotFoundException e) { throw new IllegalStateException(e); }
        return b;
    }
    private void add(MatrixCursor c,String id) throws FileNotFoundException {
        boolean root=id.equals("root");
        int flags=root?Document.FLAG_DIR_SUPPORTS_CREATE:Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | (mode().equals("no-rename")?0:Document.FLAG_SUPPORTS_RENAME);
        MatrixCursor.RowBuilder r=c.newRow();
        for(String column:c.getColumnNames()) {
            Object value=null;
            if(column.equals(Document.COLUMN_DOCUMENT_ID)) value=id;
            if(column.equals(Document.COLUMN_DISPLAY_NAME)) value=root?"Simulated M11 storage":name(id);
            if(column.equals(Document.COLUMN_MIME_TYPE)) value=root?Document.MIME_TYPE_DIR:"application/zip";
            if(column.equals(Document.COLUMN_FLAGS)) value=flags;
            if(column.equals(Document.COLUMN_SIZE)) value=root?0:bytes(id).length();
            r.add(value);
        }
    }
    private String[] columns() { return new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE}; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c=new MatrixCursor(projection==null?new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_AVAILABLE_BYTES}:projection);
        MatrixCursor.RowBuilder r=c.newRow();
        for(String column:c.getColumnNames()) {
            Object value=null;
            if(column.equals(Root.COLUMN_ROOT_ID)||column.equals(Root.COLUMN_DOCUMENT_ID)) value="root";
            if(column.equals(Root.COLUMN_TITLE)) value="Simulated M11 storage";
            if(column.equals(Root.COLUMN_FLAGS)) value=Root.FLAG_SUPPORTS_CREATE;
            if(column.equals(Root.COLUMN_AVAILABLE_BYTES)) value=new StatFs(root().getPath()).getAvailableBytes();
            r.add(value);
        }
        return c;
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException { MatrixCursor c=new MatrixCursor(projection==null?columns():projection);add(c,id);return c; }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order) throws FileNotFoundException {
        if(!parent.equals("root")) throw new FileNotFoundException();MatrixCursor c=new MatrixCursor(projection==null?columns():projection);
        File[] files=root().listFiles((d,n)->n.endsWith(".meta"));if(files!=null) for(File f:files) add(c,f.getName().substring(0,f.getName().length()-5));return c;
    }
    @Override public String createDocument(String parent,String mime,String displayName) throws FileNotFoundException {
        if(!parent.equals("root")) throw new FileNotFoundException();String id=UUID.randomUUID().toString();saveName(id,displayName);
        try { if(!bytes(id).createNewFile()) throw new IOException(); } catch(IOException e) { throw new FileNotFoundException(e.toString()); }
        grant(id);prefs().edit().putString("lastUri",DocumentsContract.buildDocumentUri(authority(),id).toString()).commit();return id;
    }
    @Override public String renameDocument(String old,String displayName) throws FileNotFoundException {
        name(old);boolean lost=mode().equals("lost-rename")&&!displayName.endsWith(".partial.zip");
        String id=lost||mode().equals("new-id")?UUID.randomUUID().toString():old;
        if(!id.equals(old)) { if(!bytes(old).renameTo(bytes(id))||!metadata(old).delete()) throw new FileNotFoundException(); }
        saveName(id,displayName);prefs().edit().putString("lastUri",DocumentsContract.buildDocumentUri(authority(),id).toString()).commit();
        if(lost) throw new FileNotFoundException("Simulated lost final rename response");
        // DocumentsProvider revokes OLD grants when a non-null ID is returned.
        // Unchanged IDs must return null, per the platform provider contract.
        return id.equals(old)?null:id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { if(!bytes(id).delete()||!metadata(id).delete()) throw new FileNotFoundException(); }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        try {
            CountDownLatch previous=pending.get(id);if(previous!=null&&!previous.await(30,TimeUnit.SECONDS)) throw new IOException("Previous provider write did not close");
            name(id);File file=bytes(id);String fault=mode();
            if(mode.contains("w")) {
                prefs().edit().putInt("writes",prefs().getInt("writes",0)+1).commit();
                if(fault.equals("slow")||fault.equals("fail-write")) {
                    ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();CountDownLatch done=new CountDownLatch(1);pending.put(id,done);
                    new Thread(()->{
                        try (OutputStream out=new FileOutputStream(file,false);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                            byte[] buffer=new byte[8192];long total=0;int n;
                            while((n=in.read(buffer))>=0) { out.write(buffer,0,n);total+=n;
                                if(fault.equals("fail-write")&&total>=4096) { pipe[0].closeWithError("Simulated destination full");break; }
                                if(fault.equals("slow")) Thread.sleep(25);
                            }
                        } catch(Exception ignored) { try { pipe[0].close(); } catch(IOException alsoIgnored) {} }
                        finally { pending.remove(id,done);done.countDown(); }
                    }).start();return pipe[1];
                }
                return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
            }
            if(fault.equals("corrupt-read")&&file.length()>100) {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
                new Thread(()->{
                    try (OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);InputStream in=new FileInputStream(file)) {
                        byte[] buffer=new byte[8192];long position=0;int n;
                        while((n=in.read(buffer))>=0) { if(position<=100&&position+n>100) buffer[(int)(100-position)]^=1;out.write(buffer,0,n);position+=n; }
                    } catch(Exception ignored) { try { pipe[1].close(); } catch(IOException alsoIgnored) {} }
                }).start();return pipe[0];
            }
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        } catch(Exception e) { throw new FileNotFoundException(e.toString()); }
    }
}
