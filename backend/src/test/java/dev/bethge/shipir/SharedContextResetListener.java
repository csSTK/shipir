package dev.bethge.shipir;

import dev.bethge.shipir.auth.LoginThrottle;
import dev.bethge.shipir.auth.totp.TwoFactorThrottleTestSupport;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Lets integration test classes share one cached Spring context instead of each loading its own (no
 * {@code @DirtiesContext}). The first class that runs on a context finds the database in its just-started
 * state (migrations, their seed rows, the initial admin) and this listener copies every non-empty table into
 * the {@value #SNAPSHOT} schema. Before any later class runs on the same context, it truncates all tables of
 * the public schema, inserts the snapshot back (parents before children) and makes the in-memory login and
 * two-factor throttles forget their attempts. Registered through {@code META-INF/spring.factories}, so it
 * applies to every Spring test automatically.
 *
 * <p>Between the methods of one class nothing changes: a class still cleans up what its own tests need.
 */
public class SharedContextResetListener extends AbstractTestExecutionListener {

    static final String SNAPSHOT = "shipir_test_snapshot";

    /** Contexts an earlier test class ran on; weak so an evicted (closed) context can be collected. */
    private static final Set<ApplicationContext> USED = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public int getOrder() {
        return 2500; // after dependency injection, before the transactional listener
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        ApplicationContext context = testContext.getApplicationContext();
        boolean firstUse;
        synchronized (USED) {
            firstUse = USED.add(context);
        }
        DataSource dataSource = context.getBeanProvider(DataSource.class).getIfAvailable();
        if (dataSource == null) {
            return;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (firstUse) {
            snapshot(jdbc);
            return;
        }
        restore(context, jdbc);
        context.getBeanProvider(LoginThrottle.class).ifAvailable(LoginThrottle::clear);
        TwoFactorThrottleTestSupport.clear(context);
    }

    /** Copies every table that has rows right after startup. Fails loudly if a snapshot already exists. */
    private static void snapshot(JdbcTemplate jdbc) {
        jdbc.execute("CREATE SCHEMA " + SNAPSHOT);
        for (String table : publicTables(jdbc)) {
            Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + quote(table), Integer.class);
            if (rows != null && rows > 0) {
                jdbc.execute("CREATE TABLE " + SNAPSHOT + "." + quote(table) + " AS TABLE " + quote(table));
            }
        }
    }

    private static void restore(ApplicationContext context, JdbcTemplate jdbc) {
        List<String> tables = publicTables(jdbc);
        List<String> seeded = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = ?", String.class, SNAPSHOT);
        List<String> ordered = parentsFirst(jdbc, seeded);
        String all = String.join(", ", tables.stream().map(SharedContextResetListener::quote).toList());
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
            // a transaction leaked by the previous class must fail this class loudly instead of hanging the build
            jdbc.execute("SET LOCAL lock_timeout = '30s'");
            jdbc.execute("TRUNCATE " + all + " CASCADE");
            for (String table : ordered) {
                jdbc.execute("INSERT INTO " + quote(table) + " SELECT * FROM " + SNAPSHOT + "." + quote(table));
            }
        });
    }

    private static List<String> publicTables(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename <> 'flyway_schema_history' ORDER BY tablename", String.class);
    }

    /** The given tables ordered so that a table referenced by a foreign key comes before the referencing one. */
    private static List<String> parentsFirst(JdbcTemplate jdbc, List<String> tables) {
        List<Map<String, Object>> edges = jdbc.queryForList("SELECT c.conrelid::regclass::text AS child, "
                + "c.confrelid::regclass::text AS parent FROM pg_constraint c "
                + "WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace");
        List<String> ordered = new ArrayList<>();
        Set<String> remaining = new HashSet<>(tables);
        while (!remaining.isEmpty()) {
            List<String> ready = remaining.stream().filter(t -> edges.stream().noneMatch(e ->
                    t.equals(e.get("child")) && !t.equals(e.get("parent")) && remaining.contains((String) e.get("parent"))))
                    .sorted().toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("Foreign key cycle among seeded tables " + remaining);
            }
            ordered.addAll(ready);
            ready.forEach(remaining::remove);
        }
        return ordered;
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }
}
