package service.v3;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic evidence scope at fact resolution, not new semantic extraction. */
public final class V3LifecycleScope {
    private static final Pattern HISTORY=Pattern.compile("以前|过去|曾经|之前|当时|那时|原来的|\\b(previously|formerly|used to|in the past)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern FUTURE=Pattern.compile("以后|之后|准备|打算|将要|将来|未来|下(?:个)?(?:周|月|季度)|等.+(?:后|以后)|\\b(will|would|intend|plan to|going to|after|once)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL=Pattern.compile("如果|假设|假如|可能|也许|或许|考虑|据说|听说|他说|她说|[?？]|\\b(if|maybe|might|suppose|he said|she said)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern BOUNDED=Pattern.compile("今天|这周|这几天|暂时|临时|这(?:个)?月|直到|期间|\\b(today|this week|temporarily|until|for now)\\b",Pattern.CASE_INSENSITIVE);
    private V3LifecycleScope() {}
    public static List<String> clauses(String message) {
        if(message==null)return List.of();
        return Arrays.stream(message.replaceAll("(?<=.)(?=(?:但|而)?(?:现在|目前|如今|currently))","，")
            .split("[,，。;；!?！？\\n]+" )).map(String::trim).filter(x->!x.isBlank()).toList();
    }
    public static boolean effectiveNow(String clause) {
        return !HISTORY.matcher(clause).find() && !FUTURE.matcher(clause).find() && !CONDITIONAL.matcher(clause).find();
    }
    public static boolean history(String clause){return HISTORY.matcher(clause).find();}
    public static boolean bounded(String message){return message!=null && BOUNDED.matcher(message).find();}
    public static boolean contains(String clause,String endpoint) {return endpoint!=null && !endpoint.isBlank() && clause.toLowerCase(Locale.ROOT).contains(endpoint.toLowerCase(Locale.ROOT));}
    private static String action(String predicate,String semantic) {
        return switch(V3LifecycleInvalidation.slot(predicate,semantic)) {
            case "INSTRUMENT_USE","METHOD" -> "使用|用|换成|改成|改为|切换到|uses?|using|switched to";
            case "EMPLOYMENT","AFFILIATION" -> "在|任职|就职|工作|属于|work at|employed|member|belong";
            case "CURRENT_LOCATION" -> "住在?|居住在?|搬到|搬来|现居地|住址|住处|居所|live|reside|stay|moved";
            case "HOME_LOCATION" -> "家在|家乡|故乡|home|hometown";
            case "PROJECT_WORK","GOAL" -> "推进|做|开发|开展|目标|计划|work on|build|plan|pursue";
            case "LEARNING" -> "学习|学|learn|study";
            case "PREFERENCE" -> "喜欢|讨厌|like|prefer|dislike|hate";
            default -> "(?!)";
        };
    }
    public static boolean negative(String clause,String target,String predicate,String semantic) {
        if(!effectiveNow(clause)||!contains(clause,target))return false;
        String endpoint=Pattern.quote(target),slot=V3LifecycleInvalidation.slot(predicate,semantic);
        if(slot.equals("PREFERENCE")) {
            if(predicate.equals("prefers"))return Pattern.compile("不(?:再)?喜欢\\s*"+endpoint+"|讨厌\\s*"+endpoint+"|\\b(?:dislike|hate|do not like|don't like)\\s+"+endpoint,Pattern.CASE_INSENSITIVE).matcher(clause).find();
            return Pattern.compile("(?<!不)(?<!再)喜欢\\s*"+endpoint+"|不讨厌\\s*"+endpoint+"|\\b(?:like|prefer)\\s+"+endpoint,Pattern.CASE_INSENSITIVE).matcher(clause).find()
                && !Pattern.compile("不(?:再)?喜欢\\s*"+endpoint+"|\\b(?:dislike|do not like|don't like)\\s+"+endpoint,Pattern.CASE_INSENSITIVE).matcher(clause).find();
        }
        String a=action(predicate,semantic);
        if(Pattern.compile("(?:不再|不是|并非|并不|并没有|没有|不|没|从未)\\s*(?:继续|正在|再)?\\s*(?:"+a+")\\s*"+endpoint,Pattern.CASE_INSENSITIVE).matcher(clause).find())return true;
        return Pattern.compile("(?:停用|停止|取消|放弃|终止|结束|离开|退出|stopped|ceased|cancelled|canceled|abandoned|left|quit)\\s*(?:使用|用|学习|推进|开发|工作|了|using |learning |working on )?\\s*"+endpoint
            +"|"+endpoint+".{0,24}(?:停用|不再使用|不用了|停止|结束|取消|放弃|终止|不再是.{0,8}(?:现居地|住处|住所)|no longer used|ended|cancelled|canceled)",Pattern.CASE_INSENSITIVE).matcher(clause).find();
    }
    public static boolean positive(String clause,String target,String predicate,String semantic) {
        if(!effectiveNow(clause)||!contains(clause,target)||negative(clause,target,predicate,semantic))return false;
        String slot=V3LifecycleInvalidation.slot(predicate,semantic),t=Pattern.quote(target);
        if(slot.equals("PREFERENCE")) {
            String verbs=predicate.equals("dislikes")?"不(?:再)?喜欢|讨厌|dislike|hate":"(?<!不)(?<!再)喜欢|like|prefer";
            return Pattern.compile("(?:"+verbs+")\\s*"+t,Pattern.CASE_INSENSITIVE).matcher(clause).find();
        }
        return Pattern.compile(action(predicate,semantic),Pattern.CASE_INSENSITIVE).matcher(clause).find()
            && !Pattern.compile("(?:不再|没有|不|没)\\s*(?:"+action(predicate,semantic)+")\\s*"+t,Pattern.CASE_INSENSITIVE).matcher(clause).find();
    }
    public static boolean affirms(String message,String target,String predicate,String semantic) {return clauses(message).stream().anyMatch(c->positive(c,target,predicate,semantic));}
    public static boolean ceases(String message,String target,String predicate,String semantic) {return clauses(message).stream().anyMatch(c->negative(c,target,predicate,semantic));}
}
