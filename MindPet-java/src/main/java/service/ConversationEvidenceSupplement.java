package service;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only fallback over existing completed user messages. Never changes memory admission,
 * fact schemas, validation, lifecycle or stored state. Ranking uses only the current question. */
@Service
public class ConversationEvidenceSupplement {
    private final JdbcTemplate jdbc;
    private static final Pattern CHANGE=Pattern.compile("改为|改成|改到|改用|改交|改留|改做|改送|调到|转到|换到|降为|恢复|回到|不再|取消|撤销|续期|延期|改期|已完成|已经完成|进一步|重新");
    private static final Pattern TEMPORAL=Pattern.compile("当前|现在|目前|最新|后来|最终|之前|以前|原来|第一次|变更|调整前");
    private static final Pattern GENERIC=Pattern.compile("当前|现在|目前|最新|最终|第一次|之前|以前|原来|变更|调整|什么|哪个|多少|如何|怎样|我的|请问|请|使用|有效|日期|时间|[0-9年月日起至，。；：\\s]");
    public record Source(String id,String content,String turnId,double score,String occurredAt,
                         long sequence,boolean temporal,List<String> requiredIds) {}
    private record Turn(String id,String content,String at,long sequence) {}
    public ConversationEvidenceSupplement(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public List<Source> search(String userId,String query,List<String> focused) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT turn_id,user_message,occurred_at,sequence FROM curator_turns WHERE user_id=? "
            + "AND julianday(occurred_at)<=julianday(?) ORDER BY sequence DESC LIMIT 500",userId,java.time.Instant.now().toString());
        List<Turn> turns=new ArrayList<>();
        for(var r:rows) {
            String content=String.valueOf(r.get("user_message"));
            if(MemoryContentSafety.looksSensitive(content))continue;
            turns.add(new Turn(String.valueOf(r.get("turn_id")),content,String.valueOf(r.get("occurred_at")),((Number)r.get("sequence")).longValue()));
        }
        List<String> contents=turns.stream().map(Turn::content).toList();
        var primary=MemoryRetrievalRanking.lexicalQuery(query,contents);
        var lexical=focused.stream().map(q->MemoryRetrievalRanking.lexicalQuery(q,contents)).toList();
        Set<String> anchors=anchors(query);
        // Query-specific subject cues, never broad topic expansion, reserve chronological sources.
        List<Turn> related=turns.stream().filter(t->anchored(t.content(),anchors)).toList();
        List<Turn> transitions=TEMPORAL.matcher(query).find()&&!anchors.isEmpty()
            ?related.stream().filter(t->CHANGE.matcher(t.content()).find()).limit(3).toList():List.of();
        Set<String> temporalIds=new LinkedHashSet<>();
        for(Turn t:transitions)temporalIds.add("dialogue:"+t.id());
        if(!transitions.isEmpty())related.stream().filter(t->t.sequence()<transitions.get(0).sequence())
            .findFirst().ifPresent(t->temporalIds.add("dialogue:"+t.id()));
        List<Source> found=new ArrayList<>();
        for(Turn t:turns) {
            double expanded=lexical.stream().mapToDouble(l->l.score(t.content())).max().orElse(0);
            double score=Math.max(primary.score(t.content()),0.8*expanded);
            String id="dialogue:"+t.id();
            if(!temporalIds.contains(id)&&score<0.25)continue;
            boolean head=!transitions.isEmpty()&&transitions.get(0).id().equals(t.id());
            found.add(new Source(id,t.content(),t.id(),head?Math.max(0.95,score):score,t.at(),t.sequence(),head,
                head?List.copyOf(temporalIds):List.of(id)));
        }
        // Keep dependencies even if lower lexical ranks would push them beyond the top forty.
        var ranked=found.stream().sorted(Comparator.comparing(Source::temporal).reversed()
            .thenComparing(Comparator.comparingDouble(Source::score).reversed())
            .thenComparing(Comparator.comparingLong(Source::sequence).reversed())).toList();
        List<Source> selected=new ArrayList<>(ranked.stream().filter(s->temporalIds.contains(s.id())).toList());
        for(Source s:ranked)if(selected.size()<40&&!selected.contains(s))selected.add(s);
        return List.copyOf(selected);
    }
    private static Set<String> anchors(String text) {
        Set<String> result=new LinkedHashSet<>();
        for(String part:GENERIC.split(MemoryRetrievalRanking.normalize(text))) {
            if(part.length()<2)continue;
            for(int n=2;n<=Math.min(4,part.length());n++)for(int i=0;i+n<=part.length();i++)result.add(part.substring(i,i+n));
        }
        return result;
    }
    private static boolean anchored(String text,Set<String> anchors) {
        String normalized=MemoryRetrievalRanking.normalize(text);
        return anchors.stream().anyMatch(normalized::contains);
    }
}
