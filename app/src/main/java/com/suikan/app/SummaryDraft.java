package com.suikan.app;

/** Bounded text-only overview preview. It is never treated as a saved result. */
final class SummaryDraft {
    static String overview(String json){
        int at=json.indexOf("\"overview\"");if(at<0)return "";at+=10;while(at<json.length()&&Character.isWhitespace(json.charAt(at)))at++;
        if(at>=json.length()||json.charAt(at++)!=':')return "";while(at<json.length()&&Character.isWhitespace(json.charAt(at)))at++;
        if(at>=json.length()||json.charAt(at++)!='\"')return "";StringBuilder out=new StringBuilder();
        while(at<json.length()&&out.length()<1500){char c=json.charAt(at++);if(c=='\"')break;
            if(c=='\\'){if(at>=json.length())break;char escape=json.charAt(at++);switch(escape){
                case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'b':case 'f':c=' ';break;
                case '\"':case '\\':case '/':c=escape;break;
                case 'u':if(at+4>json.length())return out.toString();try{c=(char)Integer.parseInt(json.substring(at,at+4),16);}catch(NumberFormatException e){return out.toString();}at+=4;break;
                default:return out.toString();}}
            out.append(c);
        }return out.toString();
    }
}
