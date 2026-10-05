package service;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Removes only repeated validity boilerplate, retaining the assertion and its explicit interval. */
public final class MemoryForgettingArchiveProjection {
    private MemoryForgettingArchiveProjection() {}
    private static final String DATE="(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日)";
    private static final Pattern RANGE=Pattern.compile(
        "(?:确认)?有效期(?:为|是|[:：])?\\s*("+DATE+")\\s*(?:至|到|~|～)\\s*("+DATE+")");
    private static final Pattern END=Pattern.compile(
        "(?:有效至|有效截止(?:日期)?(?:为|是|至|到|[:：])?|到期(?:日期|日)(?:为|是|[:：])?)\\s*("+DATE+")");
    private static final Pattern ENVELOPE_ONLY=Pattern.compile(
        "(?:到期后(?:需要重新确认(?:不能继续当作当前安排)?|失效|不再有效))?");
    public record Projection(String content,String validFrom,String validTo,boolean shortened) {}

    public static Projection project(String content) {
        var range=RANGE.matcher(content);
        int start,end;String from=null,to;
        if(range.find()) {
            start=range.start();end=range.end();from=date(range.group(1));to=date(range.group(2));
            if(range.find()||from==null||to==null||from.compareTo(to)>=0)return unchanged(content);
        } else {
            var finish=END.matcher(content);
            if(!finish.find())return unchanged(content);
            start=finish.start();end=finish.end();to=date(finish.group(1));
            if(finish.find()||to==null)return unchanged(content);
        }
        // A date of an event, condition, or any unfamiliar trailing information is not an envelope.
        String suffix=content.substring(end).replaceAll("[\\p{P}\\p{Z}\\s]+","");
        if(!ENVELOPE_ONLY.matcher(suffix).matches())return unchanged(content);
        String prefix=content.substring(0,start).strip();
        prefix=prefix.replaceFirst("(?:[。.!！?？]\\s*)?这项安排的(?:确认)?$","");
        prefix=prefix.replaceFirst("[，,；;：:。.!！?？\\s]+$","").strip();
        if(prefix.isBlank())return unchanged(content);
        // Keep validity in the text/embedding as well as storage. Retrieval Evidence omits interval
        // metadata, and the fixed serializer prints valid_to only when valid_from is present.
        // An end-only interval must not acquire an invented start date.
        String interval=from==null?"有效至"+to:"有效期"+from+"至"+to;
        String projected=prefix+"（"+interval+"）。";
        return new Projection(projected,from,to,!content.equals(projected));
    }
    private static Projection unchanged(String text){return new Projection(text,null,null,false);}
    private static String date(String value) {
        try {
            String[] parts=value.replace('年','-').replace('月','-').replace("日","").split("-");
            return LocalDate.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])).toString();
        }catch(RuntimeException invalid){return null;}
    }
}
