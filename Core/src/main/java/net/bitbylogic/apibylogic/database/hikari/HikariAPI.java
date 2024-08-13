package net.bitbylogic.apibylogic.database.hikari;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.NonNull;
import net.bitbylogic.apibylogic.database.hikari.data.HikariObject;
import net.bitbylogic.apibylogic.database.hikari.data.HikariTable;
import net.bitbylogic.apibylogic.util.Pair;
import net.bitbylogic.apibylogic.util.reflection.ReflectionUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Getter
public class HikariAPI {

    private final HikariDataSource hikari;

    private final HashMap<String, Pair<String, HikariTable<?>>> tables = new HashMap<>();

    public HikariAPI(String address, String database, String port, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        config.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("serverName", address);
        config.addDataSourceProperty("port", port);
        config.addDataSourceProperty("databaseName", database);
        config.addDataSourceProperty("user", username);
        config.addDataSourceProperty("password", password);

        hikari = new HikariDataSource(config);
    }

    public HikariAPI(File databaseFile) {
        if (!databaseFile.exists()) {
            try {
                databaseFile.createNewFile();
            } catch (IOException e) {
                System.out.println("(HikariAPI): Unable to find database file!");
                hikari = null;
                return;
            }
        }

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        config.setDataSourceClassName("jdbc:sqlite:" + databaseFile);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        hikari = new HikariDataSource(config);
    }

    public <O extends HikariObject, T extends HikariTable<O>> Optional<T> registerTable(Class<? extends T> tableClass) {
        if (getTables().containsKey(tableClass.getSimpleName())) {
            System.out.println("(HikariAPI): Couldn't register table " + tableClass.getSimpleName() + ", it's already registered.");
            return Optional.empty();
        }

        try {
            T table = ReflectionUtil.findAndCallConstructor(tableClass, this);

            if (table == null || table.getTable() == null) {
                System.out.println("(HikariAPI): Couldn't create instance of table " + tableClass.getSimpleName() + "!");
                return Optional.empty();
            }

            getTables().put(tableClass.getSimpleName(), new Pair<>(table.getTable(), table));
            return Optional.of(table);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
            System.out.println("(HikariAPI): Couldn't create instance of table " + tableClass.getSimpleName() + "!");
            e.printStackTrace();
        }

        return Optional.empty();
    }

    public HikariTable<?> getTable(@NonNull String tableName) {
        return tables.values().stream()
                .filter(stringHikariTablePair -> stringHikariTablePair.getKey().equalsIgnoreCase(tableName))
                .map(Pair::getValue).findFirst().orElse(null);
    }

    public void executeStatement(String query, Object... arguments) {
        executeStatement(query, null, arguments);
    }

    public void executeStatement(String query, Consumer<ResultSet> consumer, Object... arguments) {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = hikari.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS)) {
                    int index = 1;
                    for (Object argument : arguments) {
                        statement.setObject(index++, argument);
                    }

                    statement.executeUpdate();
                    try (ResultSet result = statement.getGeneratedKeys()) {
                        consumer.accept(result);
                    }
                }
            } catch (SQLException e) {
                // Printed below
            }
        }).handle((unused, e) -> {
            if (e == null) {
                return null;
            }

            System.out.println("(HikariAPI): Issue executing statement: " + query);
            e.printStackTrace();
            return null;
        });
    }

    public void executeQuery(String query, Consumer<ResultSet> consumer, Object... arguments) {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = hikari.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(query, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
                    int index = 1;
                    for (Object argument : arguments) {
                        statement.setObject(index++, argument);
                    }

                    try (ResultSet result = statement.executeQuery()) {
                        if (consumer == null) {
                            return;
                        }

                        consumer.accept(result);
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }).handle((unused, e) -> {
            if (e == null) {
                return null;
            }

            System.out.println("(HikariAPI): Issue executing statement: " + query);
            e.printStackTrace();
            return null;
        });
    }

}
