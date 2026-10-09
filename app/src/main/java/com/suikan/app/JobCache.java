package com.suikan.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Validated complete batches only, private cache; never publishes a partial note. */
final class JobCache implements SummaryPipeline.Checkpoints {
    private static final long MAX_BYTES=8*1024*1024, MAX_AGE=7L*24*60*60*1000;
    private final AtomicFile file;
    private JSONObject results=new JSONObject();
    JobCache(Context context,JSONObject signature,boolean reset){
        File directory=new File(context.getCacheDir(),"summary-results");directory.mkdirs();
        File[] older=directory.listFiles();if(older!=null)for(File item:older)if(System.currentTimeMillis()-item.lastModified()>MAX_AGE)item.delete();
        file=new AtomicFile(new File(directory,Json.hash(signature.toString())+".json"));
        if(reset)file.delete();
        else try{if(file.getBaseFile().length()<=MAX_BYTES)results=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));}catch(Exception ignored){}
    }
    private String id(String kind,String prompt,JSONObject context){return Json.hash(kind+"\n"+prompt+"\n"+context.toString());}
    @Override public JSONObject load(String kind,String prompt,JSONObject context){
        JSONObject saved=results.optJSONObject(id(kind,prompt,context));
        try{return saved==null?null:new JSONObject(saved.toString());}catch(Exception e){return null;}
    }
    @Override public void save(String kind,String prompt,JSONObject context,JSONObject result){
        FileOutputStream out=null;
        try{
            String key=id(kind,prompt,context);results.put(key,new JSONObject(result.toString()));
            byte[] bytes=results.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES){results.remove(key);return;}
            out=file.startWrite();out.write(bytes);file.finishWrite(out);
        }catch(Exception ignored){if(out!=null)file.failWrite(out);}
    }
    void clear(){file.delete();}
}
