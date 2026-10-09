package com.suikan.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Last extraction survives leaving the app and activity recreation. */
final class SubtitleStore {
    private static final Object LOCK=new Object();
    private final AtomicFile file;
    SubtitleStore(Context c){file=new AtomicFile(new File(c.getFilesDir(),"last-subtitle.json"));}
    JSONObject read(){synchronized(LOCK){try{return new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));}catch(Exception e){return null;}}}
    void save(JSONObject source)throws Exception{synchronized(LOCK){FileOutputStream out=null;try{out=file.startWrite();out.write(source.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}}
}
