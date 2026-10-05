package service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Parse only explicit deadlines in source text; a recorded event date is not an expiry. */
public final class MemorySourceValidity {
    private MemorySourceValidity() {}
    private static final String DATE="(\\d{4})[-年](\\d{1,2})[-月](\\d{1,2})(?:日)?";
    private static final Pattern END=Pattern.compile("(?:在|到|至)\\s*"+DATE
        +"(?:\\s*(\\d{1,2}):(\\d{2}))?\\s*(?:UTC)?\\s*(?:之前有效|之前|前为止|前到期|前|为止)");
    public static Instant explicitEnd(String text) {
        if (text == null || text.isBlank()) return null;
        var m=END.matcher(text);var ends=new LinkedHashSet<Instant>();
        while(m.find()) {
            // Only one assertion may precede a deadline. A second sentence can be a
            // validity envelope, but an independent fact must remain searchable.
            String[] prior = text.substring(0, m.start()).split("[。！？!?\\n]");
            if (prior.length > 2 || prior.length == 2
                    && !prior[1].strip().matches("(?:这项|该)?(?:安排|许可)?(?:的)?(?:有效期|适用期|许可期|时段|期限)?(?:为|是|自|从)?"))
                return null;
            // An expiry followed by an independent assertion cannot retire the whole row.
            String suffix = text.substring(m.end()).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
            if (!suffix.matches("(?:有效|结束|到期|之前到期)?(?:到期后(?:失效|不再有效|需要重新确认))?")) return null;
            try {
                int hour=m.group(4)==null?0:Integer.parseInt(m.group(4));
                int minute=m.group(5)==null?0:Integer.parseInt(m.group(5));
                ends.add(LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)))
                    .atTime(hour,minute).toInstant(ZoneOffset.UTC));
            } catch(RuntimeException invalid) { return null; }
        }
        return ends.size()==1?ends.iterator().next():null;
    }
}
