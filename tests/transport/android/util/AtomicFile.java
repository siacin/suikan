package android.util;
/** Host tests use only LocalStore.markdown, never Android storage operations. */
public final class AtomicFile {
    public AtomicFile(java.io.File file) {}
    public byte[] readFully() throws java.io.IOException { throw new UnsupportedOperationException(); }
    public java.io.FileOutputStream startWrite() throws java.io.IOException { throw new UnsupportedOperationException(); }
    public void finishWrite(java.io.FileOutputStream out) { throw new UnsupportedOperationException(); }
    public void failWrite(java.io.FileOutputStream out) { throw new UnsupportedOperationException(); }
    public void delete() { throw new UnsupportedOperationException(); }
}
