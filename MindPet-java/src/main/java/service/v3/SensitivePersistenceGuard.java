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
        + "(?:long_term_memory|memory_fact|user_profile|user_profile_current|user_insight|kg_entity|kg_entity_alias|kg_relation|kg_fact_event|kg_evidence)\\b");
    private static final ThreadLocal<List<String>> SOURCES = ThreadLocal.withInitial(List::of);
    private SensitivePersistenceGuard() {}

    public static Scope context(String... sourceTexts) {
        List<String> previous=SOURCES.get();
        List<String> values=new ArrayList<>(previous);
        for(String text:sourceTexts) for(var s:V3SensitiveAccount.identifiers(text)) if(!values.contains(s.value())) values.add(s.value());
        SOURCES.set(List.copyOf(values));
        return () -> { if(previous.isEmpty()) SOURCES.remove(); else SOURCES.set(previous); };
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
        // applies the frozen redaction rules to statements on that same transaction.
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
                for(String text:strings.values()) for(var s:V3SensitiveAccount.identifiers(text)) values.add(s.value());
                // Profile key/value is a split semantic payload. Never join identity/UUID bindings
                // with evidence text: that could mistake source identifiers for account values.
                if(sql.toLowerCase(java.util.Locale.ROOT).contains("into user_profile")) {
                    int open=sql.indexOf('('), close=sql.indexOf(')',open);
                    if(open>=0 && close>open) {
                        String[] columns=sql.substring(open+1,close).split(",");
                        List<String> fields=new ArrayList<>();
                        for(int i=0;i<columns.length;i++) if(columns[i].trim().matches("category|prop_key|prop_value"))
                            fields.add(strings.getOrDefault(i+1,"").replaceAll("(?i)(account|login|user)_id", "$1 id"));
                        for(var s:V3SensitiveAccount.identifiers(String.join(" ",fields))) values.add(s.value());
                    }
                }
                for(var entry:strings.entrySet()) {
                    String clean=redact(entry.getValue(),values);
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
                List<String> values=new ArrayList<>(SOURCES.get());
                var literals=Pattern.compile("'(?:''|[^'])*'").matcher(sql);
                while(literals.find()) for(var s:V3SensitiveAccount.identifiers(literals.group())) values.add(s.value());
                args=args.clone();args[0]=redact(sql,values);
            }
            return invoke(actual,method,args);
        });
    }
    private static String redact(String text,List<String> values) {
        String clean=text;
        for(String value:values) clean=Pattern.compile(Pattern.quote(value),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(clean).replaceAll(REDACTED);
        return clean;
    }
}
