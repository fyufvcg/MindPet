package service.v3;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Last submission boundary, independent of extraction/admission. No values are logged or retained. */
public final class SensitivePersistenceGuard {
    public static final String REDACTED = "[REDACTED_ACCOUNT]";
    private static final Pattern DURABLE = Pattern.compile("(?is)^\\s*(?:insert(?:\\s+or\\s+\\w+)?\\s+into|replace\\s+into|update)\\s+[\"`\\[]?"
        + "(?:long_term_memory|memory_fact|memory_retrieval_unit|memory_retrieval_source|memory_compaction_snapshot|conversation_memory|sessions|session_messages|curator_turns|curator_state|curator_runs|curator_proposal_item|curator_accepted_event|user_profile|user_profile_current|user_insight|llm_growth|kg_entity|kg_entity_alias|kg_relation|kg_fact_event|kg_evidence|kg_turn_task)\\b");
    private static final ThreadLocal<List<String>> SOURCES = ThreadLocal.withInitial(List::of);
    private static final ThreadLocal<List<String>> ACCOUNTS = ThreadLocal.withInitial(List::of);
    private SensitivePersistenceGuard() {}

    public static Scope context(String... sourceTexts) {
        List<String> previous=SOURCES.get(), previousAccounts=ACCOUNTS.get();
        List<String> accounts=new ArrayList<>(previousAccounts);
        for(String text:sourceTexts) for(var span:V3SensitiveAccount.identifiers(text)) if(!accounts.contains(span.value())) accounts.add(span.value());
        ACCOUNTS.set(List.copyOf(accounts));
        List<String> values=new ArrayList<>(previous);
        for(String text:sourceTexts) for(String value:SecretSanitizer.values(text)) if(!values.contains(value)) values.add(value);
        SOURCES.set(List.copyOf(values));
        return () -> {
            if(previous.isEmpty()) SOURCES.remove(); else SOURCES.set(previous);
            if(previousAccounts.isEmpty()) ACCOUNTS.remove(); else ACCOUNTS.set(previousAccounts);
        };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }

    public static DataSource wrap(DataSource source) {
        if(source instanceof GuardedDataSource) return source;
        return new GuardedDataSource(source);
    }
    public static JdbcTemplate protect(JdbcTemplate jdbc) {
        if(jdbc.getDataSource()==null || jdbc.getDataSource() instanceof GuardedDataSource) return jdbc;
        return new JdbcTemplate(wrap(jdbc.getDataSource()));
    }
    private static final class GuardedDataSource extends TransactionAwareDataSourceProxy implements AutoCloseable {
        // The target remains the master transaction resource. The outer connection proxy
        // applies secret redaction to statements on that same transaction.
        GuardedDataSource(DataSource source){super(source);}
        @Override public Connection getConnection() throws SQLException {return connection(super.getConnection());}
        @Override public Connection getConnection(String user,String password) throws SQLException {return connection(super.getConnection(user,password));}
        @Override public void close() throws Exception {
            if(getTargetDataSource() instanceof AutoCloseable source) source.close();
        }
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    private static Connection connection(Connection actual) {
        return (Connection)Proxy.newProxyInstance(SensitivePersistenceGuard.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(args!=null && args.length>0 && args[0] instanceof String sql && DURABLE.matcher(sql).find()) {
                args=args.clone(); args[0]=sanitizeSql(sql);
            }
            Object result=invoke(actual,method,args);
            if(result instanceof PreparedStatement ps && args!=null && args.length>0 && args[0] instanceof String sql)
                return prepared(ps,sql);
            if(result instanceof Statement statement) return statement(statement);
            return result;
        });
    }
    private static PreparedStatement prepared(PreparedStatement actual,String sql) {
        Map<Integer,String> strings=new TreeMap<>();
        return (PreparedStatement)Proxy.newProxyInstance(SensitivePersistenceGuard.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("clearParameters")) strings.clear();
            if(name.startsWith("set") && args!=null && args.length>=2 && args[0] instanceof Integer index) {
                if(args[1] instanceof String text) strings.put(index,text); else strings.remove(index);
            }
            if(DURABLE.matcher(sql).find() && (name.startsWith("execute") || name.equals("addBatch"))) {
                List<String> values=new ArrayList<>(SOURCES.get());
                List<String> accounts=new ArrayList<>(ACCOUNTS.get());
                for(String text:strings.values()) {
                    values.addAll(SecretSanitizer.values(text));
                    for(var span:V3SensitiveAccount.identifiers(text)) accounts.add(span.value());
                }
                // Profile key/value is a split semantic payload. Never join identity/UUID bindings
                // with evidence text: that could mistake source identifiers for account values.
                if(sql.toLowerCase(java.util.Locale.ROOT).contains("into user_profile")) {
                    int open=sql.indexOf('('), close=sql.indexOf(')',open);
                    if(open>=0 && close>open) {
                        String[] columns=sql.substring(open+1,close).split(",");
                        List<String> fields=new ArrayList<>();
                        for(int i=0;i<columns.length;i++) if(columns[i].trim().matches("category|prop_key|prop_value"))
                            fields.add(strings.getOrDefault(i+1,"").replaceAll("(?i)(account|login|user)_id", "$1 id"));
                        for(var span:V3SensitiveAccount.identifiers(String.join(" ",fields))) { values.add(span.value()); accounts.add(span.value()); }
                        values.addAll(SecretSanitizer.values(String.join(" ",fields)));
                    }
                }
                for(var entry:strings.entrySet()) {
                    String clean=redact(entry.getValue(),values,accounts);
                    if(!clean.equals(entry.getValue())) actual.setString(entry.getKey(),clean);
                }
            }
            return invoke(actual,method,args);
        });
    }
    private static Statement statement(Statement actual) {
        return (Statement)Proxy.newProxyInstance(SensitivePersistenceGuard.class.getClassLoader(),new Class<?>[]{Statement.class},(proxy,method,args)->{
            if(args!=null && args.length>0 && args[0] instanceof String sql && DURABLE.matcher(sql).find()
                    && (method.getName().startsWith("execute") || method.getName().equals("addBatch"))) {
                args=args.clone(); args[0]=sanitizeSql(sql);
            }
            return invoke(actual,method,args);
        });
    }
    /** Only SQL literal contents are data. Never apply a text classifier to SQL syntax. */
    private static String sanitizeSql(String sql) {
        var matcher=Pattern.compile("'(?:''|[^'])*'").matcher(sql);
        List<String> values=new ArrayList<>(SOURCES.get()), accounts=new ArrayList<>(ACCOUNTS.get());
        while(matcher.find()) {
            String text=matcher.group().substring(1,matcher.group().length()-1).replace("''", "'");
            values.addAll(SecretSanitizer.values(text));
            for(var span:V3SensitiveAccount.identifiers(text)) accounts.add(span.value());
        }
        matcher.reset(); StringBuffer clean=new StringBuffer();
        while(matcher.find()) {
            String text=matcher.group().substring(1,matcher.group().length()-1).replace("''", "'");
            String literal="'"+redact(text,values,accounts).replace("'", "''")+"'";
            matcher.appendReplacement(clean,java.util.regex.Matcher.quoteReplacement(literal));
        }
        matcher.appendTail(clean); return clean.toString();
    }
    private static String redact(String text,List<String> values,List<String> accounts) {
        String clean=SecretSanitizer.redactAccounts(text, accounts);
        return SecretSanitizer.sanitize(clean, values.stream().filter(value -> !accounts.contains(value)).toList());
    }
}
