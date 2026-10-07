package service.v3;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Current-evidence lifecycle resolution over existing identities, not extraction.
 * No entities, predicates or proposals are created. Historical source rows remain.
 */
public final class V3LifecycleInvalidation {
    private static final Pattern UNASSERTED = Pattern.compile(
        "如果|假设|假如|可能|也许|或许|考虑|据说|听说|他说|她说|[?？]|\\b(if|maybe|might|suppose|he said|she said)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FUTURE = Pattern.compile(
        "以后|之后|准备|打算|将要|未来|下(?:周|月|季度)|等.+(?:后|以后)|\\b(will|would|intend|plan to|going to|after|once)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAST = Pattern.compile(
        "以前|过去|曾经|之前|当时|\\b(previously|formerly|used to|in the past)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRESENT = Pattern.compile(
        "现在|目前|如今|已经|已正式|今天|不再|停用|结束|离开|退出|终止|取消|放弃|\\b(now|currently|already|no longer|stopped|left|cancelled|canceled|ended)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANGE = Pattern.compile(
        "改用|改为|改成|换成|换到|换过|换了|切换|替代|替换|更新为|转为|转到|搬到|搬来|搬走|搬家完成|离开|入职|旧.+(?:结束|过去)|\\b(switched|replaced|changed|moved|left|new employer|old.+ended)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP = Pattern.compile(
        "不再|不用|不使用|没有使用|没用|从未|不是|不住|停用|停止|结束|终止|取消|放弃|离开|退出|\\b(no longer|do not|don't|never|not|stopped|ceased|ended|cancelled|canceled|abandoned|left|quit)\\b", Pattern.CASE_INSENSITIVE);

    private V3LifecycleInvalidation() {}

    public record Fact(String id, String sourceId, String sourceName, boolean userAnchor, String targetId, String target,
                       String predicate, String semantic, String temporal, double importance,
                       double confidence) {}

    /** Semantic slots are more specific than normalized predicates, notably home vs current place. */
    public static String slot(String predicate, String semantic) {
        String s = semantic == null ? "" : semantic.toUpperCase(Locale.ROOT);
        if (s.matches(".*(?:HOME|HOMETOWN|ORIGIN|BORN|FAMILY_LOCATION).*")) return "HOME_LOCATION";
        if (s.matches(".*(?:LIV|RESID|STAY|MOVED|MOVE_TO|CURRENT_LOCATION|LOCATED_AT).*")) return "CURRENT_LOCATION";
        if (s.matches(".*(?:MEANS|METHOD|PRACTICE).*")) return "METHOD";
        if (s.matches(".*(?:WORKS_AT|WORKED_AT|EMPLOY|EMPLOYER|WORKPLACE|JOB_AT).*")) return "EMPLOYMENT";
        return switch (predicate) {
            case "prefers", "dislikes" -> "PREFERENCE";
            case "uses" -> "INSTRUMENT_USE";
            case "plans" -> "GOAL";
            case "builds", "works_on" -> "PROJECT_WORK";
            case "learns" -> "LEARNING";
            case "belongs_to" -> "AFFILIATION";
            case "knows" -> "KNOWLEDGE";
            case "experienced" -> "EXPERIENCE";
            default -> "OTHER:" + s;
        };
    }

    private static boolean compatible(Fact old, Fact next) {
        return old.sourceId().equals(next.sourceId()) && slot(old.predicate(),old.semantic()).equals(slot(next.predicate(),next.semantic()));
    }

    private static boolean assertedNow(String clause) {
        return V3LifecycleScope.effectiveNow(clause);
    }

    private static boolean mentions(String clause, String target) {
        return clause.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }

    private static boolean subjectMatches(String clause, Fact fact) {
        // Unnamed first-person lifecycle messages belong only to the reserved user.
        // A fact about another person requires that person's explicit name.
        return fact.userAnchor() || mentions(clause, fact.sourceName());
    }

    /** Denial is scoped to the same endpoint AND semantic slot; dislike is not deletion of an object. */
    private static boolean explicitlyEnded(String clause, Fact fact) {
        if (!assertedNow(clause) || !subjectMatches(clause,fact) || !mentions(clause,fact.target())) return false;
        return V3LifecycleScope.negative(clause,fact.target(),fact.predicate(),fact.semantic());
    }

    public static boolean confirmedReplacement(String message,String old,String next,String predicate,String semantic) {
        List<String> clauses=V3LifecycleScope.clauses(message);
        boolean nextAsserted=clauses.stream().anyMatch(c->V3LifecycleScope.positive(c,next,predicate,semantic));
        boolean cessation=clauses.stream().anyMatch(c->V3LifecycleScope.negative(c,old,predicate,semantic));
        boolean historicalContrast=clauses.stream().anyMatch(c->V3LifecycleScope.history(c)&&mentions(c,old));
        boolean effectiveSwitch=clauses.stream().anyMatch(c->assertedNow(c)&&CHANGE.matcher(c).find())
            && clauses.stream().anyMatch(c->mentions(c,old));
        return nextAsserted&&(cessation||historicalContrast||effectiveSwitch);
    }

    public static String resolvedTemporal(String message,String target,String predicate,String semantic,String proposed) {
        if(!V3LifecycleScope.affirms(message,target,predicate,semantic))return proposed;
        if((predicate.equals("prefers")||predicate.equals("dislikes"))&&!V3LifecycleScope.bounded(message))return "PERMANENT";
        if(Set.of("ENDED","FUTURE").contains(proposed))return "CURRENT";
        return proposed;
    }

    private static boolean legacyExplicitlyEnded(String clause, Fact fact) {
        String slot = slot(fact.predicate(),fact.semantic());
        String t = Pattern.quote(fact.target());
        String action = switch(slot) {
            case "INSTRUMENT_USE", "METHOD" -> "使用|用|uses?|using";
            case "CURRENT_LOCATION" -> "住在?|居住|居住在|live in|live at|reside in|stay at";
            case "HOME_LOCATION" -> "家在|家乡|home|hometown";
            case "EMPLOYMENT", "AFFILIATION" -> "属于|任职|工作|就职|工作于|work at|belong to|member of";
            case "GOAL", "PROJECT_WORK" -> "做|推进|计划|目标|开发|plan|pursue|work on";
            case "PREFERENCE" -> fact.predicate().equals("prefers") ? "喜欢|like|prefer" : "讨厌|不喜欢|dislike";
            case "LEARNING" -> "学习|学|learn|study";
            default -> "(?!)";
        };
        String scoped = "(?:不再|不|没|没有|从未|不是|不用|\\b(?:no longer|do not|don't|never|not)\\b)\\s*(?:继续|正在|再)?\\s*(?:"+action+")\\s*"+t;
        if (Pattern.compile(scoped,Pattern.CASE_INSENSITIVE).matcher(clause).find()) return true;
        if ((slot.equals("EMPLOYMENT") || slot.equals("AFFILIATION")) && Pattern.compile(
            "(?:不再|不|没有|没|不是)\\s*(?:继续|正在)?\\s*(?:在|属于)\\s*"+t+"(?:工作|任职|就职)?",Pattern.CASE_INSENSITIVE).matcher(clause).find()) return true;
        // A completed lifecycle action, unlike a hypothetical or a past negative clause.
        if (STOP.matcher(clause).find() && !slot.equals("PREFERENCE")) {
            return Pattern.compile("(?:停用|停止|取消|放弃|终止|离开|退出|stopped|ceased|abandoned|left|quit)\\s*(?:使用|用|学习|推进|开发|计划|了|the |using |learning |working on )?\\s*"+t
                + "|"+t+".{0,20}(?:停用|不用了|结束|取消|放弃|终止|过去的|no longer used|ended|cancelled|canceled)",Pattern.CASE_INSENSITIVE).matcher(clause).find();
        }
        return false;
    }

    private static boolean genericEnded(String clause, Fact fact, List<Fact> priors) {
        if (!assertedNow(clause) || !subjectMatches(clause,fact) || !STOP.matcher(clause).find()) return false;
        String slot = slot(fact.predicate(),fact.semantic());
        boolean namedSlot = switch(slot) {
            case "EMPLOYMENT" -> clause.matches(".*(?:旧工作|旧岗位|原工作|previous job|old job).*(?:结束|离开|ended|left).*" );
            case "GOAL" -> clause.matches(".*(?:目标|计划|goal|plan).*(?:取消|放弃|终止|cancelled|abandoned).*" );
            default -> false;
        };
        return namedSlot && priors.stream().filter(f -> f.sourceId().equals(fact.sourceId())
            && slot(f.predicate(),f.semantic()).equals(slot)).count()==1;
    }

    private static boolean affirmative(String clause, Fact next) {
        return subjectMatches(clause,next) && V3LifecycleScope.positive(clause,next.target(),next.predicate(),next.semantic());
    }

    /** Called once at the persistence boundary after admitted proposals have been written. */
    public static int apply(JdbcTemplate jdbc, String userId, String sessionId, String turnHash,
                            String message, String assistant, Instant at) {
        if (message==null || message.isBlank() || V3SensitiveAccount.containsIdentifier(message)) return 0;
        List<Fact> active = jdbc.query("SELECT r.*,t.display_name,s.display_name AS source_name,s.normalized_name AS source_normalized,s.summary AS source_summary FROM kg_relation r JOIN kg_entity t ON t.id=r.target_entity_id JOIN kg_entity s ON s.id=r.source_entity_id "
            + "WHERE r.user_id=? AND r.fact_status='ACTIVE' AND r.temporal_status<>'FUTURE'",
            (rs,n)->new Fact(rs.getString("id"),rs.getString("source_entity_id"),rs.getString("source_name"),"user".equals(rs.getString("source_normalized")) && "Current user".equals(rs.getString("source_summary")),rs.getString("target_entity_id"),rs.getString("display_name"),
                rs.getString("predicate"),rs.getString("semantic_predicate"),rs.getString("temporal_status"),rs.getDouble("importance"),rs.getDouble("confidence")),userId);
        Set<String> currentIds = new HashSet<>(jdbc.query("SELECT relation_id FROM kg_evidence WHERE user_id=? AND turn_hash=? AND relation_id IS NOT NULL",
            (rs,n)->rs.getString(1),userId,turnHash));
        List<Fact> priors = active.stream().filter(f->!currentIds.contains(f.id())).toList();
        List<Fact> current = active.stream().filter(f->currentIds.contains(f.id())).toList();
        List<String> clauses = new ArrayList<>();
        clauses.addAll(V3LifecycleScope.clauses(message));
        int changed=0;
        for (Fact old : active) {
            List<Fact> next = current.stream().filter(f->!f.targetId().equals(old.targetId()) && compatible(old,f)
                && clauses.stream().anyMatch(c->affirmative(c,f))).toList();
            boolean denied = clauses.stream().anyMatch(c->explicitlyEnded(c,old) || genericEnded(c,old,priors));
            boolean replacement = next.size()==1 && confirmedReplacement(message,old.target(),next.get(0).target(),old.predicate(),old.semantic());
            if (!denied && !replacement) continue;
            // Ending one user's assertion cannot affect another subject/user.
            String status = next.size()==1 ? "SUPERSEDED" : "ENDED";
            String successor = next.size()==1 ? next.get(0).id() : null;
            new V3FactEventJournal(jdbc).recordResolution(userId,sessionId,turnHash,message,assistant,at,old.id(),status,old.confidence(),old.importance());
            changed += jdbc.update("UPDATE kg_relation SET fact_status=?,temporal_status='ENDED',valid_to=?,superseded_by=?,"
                + "resolution_kind='SUPERSEDES',last_seen=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND fact_status='ACTIVE'",
                status,at.toString(),successor,old.id(),userId);
        }
        return changed;
    }
}
