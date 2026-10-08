package app.relay.lan;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import java.io.*;

public class RecordingProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    String n = uri.getLastPathSegment();
    if (n == null || !n.matches("relay_[0-9]+_[a-f0-9-]+\\.wav")) throw new FileNotFoundException();
    File f = new File(new File(getContext().getFilesDir(), "recordings"), n);
    if (!f.isFile()) throw new FileNotFoundException();
    return f;
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public String getType(Uri u) {
    return "audio/wav";
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String order) {
    try {
      File f = file(u);
      MatrixCursor c = new MatrixCursor(new String[] {"_display_name", "_size"});
      c.addRow(new Object[] {f.getName(), f.length()});
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
