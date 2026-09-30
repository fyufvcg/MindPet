package experiment;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned evaluation contract, independent of production validation and compaction. */
public final class FrozenMemoryEvaluation {
    public static final String VERSION="memory-eval-v4-core-state";
    private FrozenMemoryEvaluation() {}
    public static String normalize(String text) {
        return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+","");
    }
    private static String canonical(String predicate,String text) {
        String value=normalize(text);
        if("preference".equals(predicate)) value=value.replace("先给结论","先说结论").replace("先看结论","先说结论")
            .replace("先给出结论","先说结论").replace("再看简洁步骤","再列简洁步骤");
        return value;
    }
    public static boolean supports(String content,String predicate,String value,String scope,String assertion) {
        String text=canonical(predicate,content),target=canonical(predicate,value);
        if(target.isBlank()||!text.contains(target))return false;
        int at=text.indexOf(target),start=at,end=at+target.length();
        while(start>0&&"，,。；;!?！？".indexOf(text.charAt(start-1))<0)start--;
        while(end<text.length()&&"，,。；;!?！？".indexOf(text.charAt(end))<0)end++;
        String local=text.substring(start,end),before=text.substring(start,at),after=text.substring(at+target.length(),end);
        String[] cues=switch(predicate) {
            case "current_location"->new String[]{"住","居","搬","生活","现居","地址","所在","前往"};
            case "home_location"->new String[]{"家","长期住","长期落脚","home_location"};
            case "occupation_current"->new String[]{"工作","职位","岗位","职业","担任","从事","转任","做过","现职","occupation_current"};
            case "preference"->new String[]{"偏好","希望","喜欢","习惯","回复","回答","答复","沟通","preference"};
            case "current_project"->new String[]{"项目","负责","参与","current_project"};
            case "plan","event"->new String[]{"计划","参加","取消","安排","考虑","活动","课程","event","plan"};
            case "experience"->new String[]{"经历","曾","完成","参加","experience"};
            default->new String[]{predicate};
        };
        if(List.of(cues).stream().noneMatch(local::contains))return false;
        if("current".equals(scope)&&before.matches(".*(?:以前|之前|过去|那时|当时|搬家前|曾任|曾居).*"))return false;
        boolean negative=Pattern.compile("(?:不是|不再|从未|没有|取消|放弃)[^，,]{0,8}$").matcher(before).find()
            ||after.matches("^[^，,]{0,7}(?:已取消|取消了|不是现居|不参加).*");
        if(before.endsWith("没有变：")||before.endsWith("保持不变："))negative=false;
        boolean possible=(before+after).matches(".*(?:可能|也许|尚未决定|备选|考虑的选项|还没决定).*");
        if("negated".equals(assertion))return negative||local.contains("取消");
        if(Set.of("possible","uncertain").contains(assertion))return possible;
        return !negative && !possible;
    }

    public static boolean wrongState(String answer,List<String> forbidden) {
        String text=normalize(answer);
        for(String value:forbidden) {
            String target=normalize(value);if(target.isBlank())continue;
            int from=0;
            while((from=text.indexOf(target,from))>=0) {
                int left=Math.max(0,from-28),right=Math.min(text.length(),from+target.length()+24);
                for(int i=left;i<from;i++)if("。；;！？!?，,".indexOf(text.charAt(i))>=0)left=i+1;
                for(int i=from+target.length();i<right;i++)if("。；;！？!?，,".indexOf(text.charAt(i))>=0){right=i;break;}
                String before=text.substring(left,from),after=text.substring(from+target.length(),right);
                boolean qualified=(before+after).matches(".*(?:之前|以前|过去|历史|原先|搬家前|曾经|做过|考虑过|可能|备选|计划|只是|不再|不是|并非|尚未|还没有|未搬).*");
                if(!qualified)return true;
                from+=target.length();
            }
        }
        return false;
    }
}
