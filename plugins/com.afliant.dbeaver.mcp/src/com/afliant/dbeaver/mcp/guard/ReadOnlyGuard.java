package com.afliant.dbeaver.mcp.guard;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;

import com.afliant.dbeaver.mcp.McpToolException;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Decides whether a statement may be executed through the MCP server. This is the code-level constraint:
 * nothing reaches a database unless it passes every check here, and the executor additionally runs it
 * inside a read-only transaction that is always rolled back.
 *
 * Layers:
 * 1. DBeaver's dialect-aware script splitter: exactly one statement, no client-side control commands.
 * 2. JSqlParser AST: the statement must be a SELECT (optionally wrapped in EXPLAIN), SHOW or DESCRIBE.
 *    Unparseable SQL is rejected (fail closed) because it cannot be proven read-only.
 * 3. Token-level checks on the statement with literals, comments and quoted identifiers blanked:
 *    no INTO / OUTFILE / DUMPFILE, no locking reads, no write or DDL keyword anywhere, no second statement.
 */
public final class ReadOnlyGuard {

    public enum Kind { SELECT, EXPLAIN, SHOW, DESCRIBE }

    public record Verdict(String sql, Kind kind) { }

    private static final long PARSE_TIMEOUT_MS = 5000;

    private static final Set<String> SHOW_STATEMENT_CLASSES = Set.of(
        "ShowStatement", "ShowColumnsStatement", "ShowTablesStatement", "ShowIndexStatement");

    private static final Pattern SELECT_FORBIDDEN = Pattern.compile(
        "\\b(INTO|OUTFILE|DUMPFILE)\\b"
            + "|\\bFOR\\s+(UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE)\\b"
            + "|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b",
        Pattern.CASE_INSENSITIVE);

    /** Reserved words that can never appear unquoted inside a legitimate read-only statement. */
    private static final Pattern WRITE_KEYWORDS = Pattern.compile(
        "\\b(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL|LOAD|LOCK|UNLOCK|KILL|RENAME|PURGE|OPTIMIZE|TRUNCATE\\s+TABLE)\\b",
        Pattern.CASE_INSENSITIVE);

    private ReadOnlyGuard() {
    }

    public static Verdict check(DBPDataSource dataSource, String rawSql) throws McpToolException {
        if (rawSql == null || rawSql.isBlank()) {
            throw new McpToolException("'sql' is empty.");
        }

        // Layer 1: dialect-aware split
        List<SQLScriptElement> elements;
        try {
            elements = SQLScriptParser.parseScript(dataSource, rawSql);
        } catch (Exception e) {
            throw new McpToolException("SQL could not be parsed: " + e.getMessage());
        }
        elements = elements.stream().filter(e -> e.getText() != null && !e.getText().isBlank()).toList();
        if (elements.size() != 1) {
            throw new McpToolException("Exactly one statement is allowed per call (found " + elements.size() + ").");
        }
        if (!(elements.get(0) instanceof SQLQuery)) {
            throw new McpToolException("Client-side control commands are not allowed.");
        }
        return verifyStatement(elements.get(0).getText());
    }

    /** Layers 2 and 3 on a single statement (no dialect-aware splitting). Public so it can be tested without a data source. */
    public static Verdict verifyStatement(String statementText) throws McpToolException {
        if (statementText == null || statementText.isBlank()) {
            throw new McpToolException("'sql' is empty.");
        }
        String sql = stripTrailingDelimiters(statementText);
        if (sql.isBlank()) {
            throw new McpToolException("'sql' contains no statement.");
        }
        if (sql.contains("/*!")) {
            throw new McpToolException("MySQL version comments (/*! ... */) are not allowed because the server executes their content.");
        }

        // Layer 3 first for the cheap structural check (also protects the prefix-only kinds below)
        String code = blankLiteralsAndComments(sql);
        if (code.indexOf(';') >= 0) {
            throw new McpToolException("Multiple statements are not allowed.");
        }

        // Layer 2: AST classification. SHOW and DESC[RIBE] are introspection-only in every supported dialect and
        // JSqlParser does not cover all their forms, so they are accepted by leading keyword.
        Kind kind;
        String firstWord = firstKeyword(code);
        if ("SHOW".equals(firstWord)) {
            kind = Kind.SHOW;
        } else if ("DESC".equals(firstWord) || "DESCRIBE".equals(firstWord)) {
            kind = Kind.DESCRIBE;
        } else {
            kind = classify(parseSingle(sql));
        }

        if (kind != Kind.SHOW) {
            Matcher forbidden = SELECT_FORBIDDEN.matcher(code);
            if (forbidden.find()) {
                throw new McpToolException("Clause not allowed in read-only mode: " + forbidden.group().trim().toUpperCase());
            }
            Matcher write = WRITE_KEYWORDS.matcher(code);
            if (write.find()) {
                throw new McpToolException("Keyword not allowed in read-only mode: " + write.group().trim().toUpperCase());
            }
        }
        return new Verdict(sql, kind);
    }

    private static String firstKeyword(String code) {
        Matcher m = Pattern.compile("^\\s*([A-Za-z]+)").matcher(code);
        return m.find() ? m.group(1).toUpperCase() : "";
    }

    private static Statement parseSingle(String sql) throws McpToolException {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(PARSE_TIMEOUT_MS));
        } catch (JSQLParserException e) {
            throw new McpToolException("SQL could not be parsed, so it cannot be verified as read-only and was rejected. "
                + "Only a single SELECT, SHOW, EXPLAIN or DESCRIBE statement is accepted. Parser message: " + rootMessage(e));
        } catch (RuntimeException e) {
            throw new McpToolException("SQL could not be parsed (" + rootMessage(e) + ") and was rejected.");
        }
        if (statements == null) {
            throw new McpToolException("'sql' contains no statement.");
        }
        List<Statement> list = statements.stream().filter(s -> s != null).toList();
        if (list.size() != 1) {
            throw new McpToolException("Exactly one statement is allowed per call (parser found " + list.size() + ").");
        }
        return list.get(0);
    }

    private static Kind classify(Statement statement) throws McpToolException {
        if (statement instanceof Select) {
            return Kind.SELECT;
        }
        if (statement instanceof ExplainStatement explain) {
            if (explain.getStatement() == null) {
                throw new McpToolException("EXPLAIN is only allowed for a SELECT statement.");
            }
            return Kind.EXPLAIN;
        }
        if (statement instanceof DescribeStatement) {
            return Kind.DESCRIBE;
        }
        if (SHOW_STATEMENT_CLASSES.contains(statement.getClass().getSimpleName())) {
            return Kind.SHOW;
        }
        String type = statement.getClass().getSimpleName().replace("Statement", "").toUpperCase();
        throw new McpToolException("Statement type " + type + " is not allowed. This server only executes SELECT, SHOW, EXPLAIN and DESCRIBE.");
    }

    private static String stripTrailingDelimiters(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        return s;
    }

    /** Replaces string literals, quoted identifiers and comments with spaces so keyword checks only see code. */
    static String blankLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n) {
                    char d = sql.charAt(j);
                    if (d == '\\' && c != '`') {
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                int end = Math.min(j + 1, n);
                out.append(" ".repeat(end - i));
                i = end;
            } else if (c == '-' && next == '-' || c == '#') {
                int j = i;
                while (j < n && sql.charAt(j) != '\n') {
                    j++;
                }
                out.append(" ".repeat(j - i));
                i = j;
            } else if (c == '/' && next == '*') {
                int j = sql.indexOf("*/", i + 2);
                int end = j < 0 ? n : j + 2;
                out.append(" ".repeat(end - i));
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null) {
            return cur.getClass().getSimpleName();
        }
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }
}
