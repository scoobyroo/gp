import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Thin JDBC wrapper. Connection details come from a java.util.Properties XML file with
 * url / user / password (same format as the other tools in this repository).
 * Only SELECT statements are ever issued by this tool.
 */
final class Db implements AutoCloseable {
    private final Connection conn;
    private final boolean debug;

    Db(String configPath, String schema, boolean debug) throws Exception {
        this.debug = debug;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(configPath)) {
            p.loadFromXML(in);
        }
        conn = DriverManager.getConnection(
                p.getProperty("url").trim(), p.getProperty("user").trim(), p.getProperty("password").trim());
        if (schema != null && !Text.isBlank(schema)) {
            if (!schema.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
                throw new IllegalArgumentException("Invalid schema name: " + schema);
            }
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schema);
            }
        }
    }

    List<Row> query(String sql, Object... params) throws SQLException {
        if (debug) {
            System.err.println("[sql] " + sql.replaceAll("\\s+", " ") + "  " + java.util.Arrays.toString(params));
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(500);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Row> rows = new ArrayList<>();
                while (rs.next()) {
                    Row r = new Row();
                    for (int c = 1; c <= n; c++) {
                        Object v = rs.getObject(c);
                        if (v instanceof java.sql.Clob) {
                            // CLOBs (e.g. element comments) must be read while the result set is open.
                            java.sql.Clob clob = (java.sql.Clob) v;
                            v = clob.getSubString(1, (int) Math.min(clob.length(), 200_000L));
                        }
                        r.put(md.getColumnLabel(c), v);
                    }
                    rows.add(r);
                }
                return rows;
            }
        }
    }

    /** Column names of a table or view (upper case, in table order); empty when it does not exist. */
    List<String> columns(String table) {
        List<String> cols = new ArrayList<>();
        if (!table.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
            return cols;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + table + " WHERE 1 = 0");
                ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++) {
                cols.add(md.getColumnLabel(c).toUpperCase());
            }
        } catch (SQLException e) {
            if (debug) {
                System.err.println("[sql-error] " + e.getMessage().split("\n")[0]);
            }
        }
        return cols;
    }

    /** Like query() but returns an empty list when the table/column does not exist in this installation. */
    List<Row> tryQuery(String sql, Object... params) {
        try {
            return query(sql, params);
        } catch (SQLException e) {
            if (debug) {
                System.err.println("[sql-error] " + e.getMessage().split("\n")[0]);
            }
            return new ArrayList<>();
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
