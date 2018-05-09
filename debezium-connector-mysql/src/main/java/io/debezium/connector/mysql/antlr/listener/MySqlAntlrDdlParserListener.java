/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.mysql.antlr.listener;

/**
 * @author Roman Kuchár <kucharrom@gmail.com>.
 */

import io.debezium.antlr.AntlrDdlParserListener;
import io.debezium.antlr.ProxyParseTreeListenerUtil;
import io.debezium.connector.mysql.antlr.MySqlAntlrDdlParser;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.ddl.parser.mysql.generated.MySqlParserBaseListener;
import io.debezium.text.ParsingException;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Parser listener for MySQL column definition queries.
 */
public class MySqlAntlrDdlParserListener extends MySqlParserBaseListener implements AntlrDdlParserListener {

    private List<ParseTreeListener> listeners = new CopyOnWriteArrayList<>();

    private boolean skipNodes;
    private int skippedNodesCount = 0;
    private Collection<ParsingException> errors = new ArrayList<>();

    public MySqlAntlrDdlParserListener(MySqlAntlrDdlParser parserCtx) {
        listeners.add(new CreateAndAlterDatabaseParserListener(parserCtx));
        listeners.add(new DropDatabaseParserListener(parserCtx));
        listeners.add(new CreateTableParserListener(parserCtx, listeners));
        listeners.add(new AlterTableParserListener(parserCtx, listeners));
        listeners.add(new DropTableParserListener(parserCtx));
        listeners.add(new RenameTableParserListener(parserCtx));
        listeners.add(new TruncateTableParserListener(parserCtx));
        listeners.add(new CreateViewParserListener(parserCtx, listeners));
        listeners.add(new AlterViewParserListener(parserCtx, listeners));
        listeners.add(new DropViewParserListener(parserCtx));
        listeners.add(new CreateUniqueIndexParserListener(parserCtx));
        listeners.add(new SetStatementParserListener(parserCtx));
        listeners.add(new UseStatementParserListener(parserCtx));
    }

    /**
     * Returns all caught errors during tree walk.
     *
     * @return list of Parsing exceptions
     */
    public Collection<ParsingException> getErrors() {
        return errors;
    }

    @Override
    public void enterEveryRule(ParserRuleContext ctx) {
        if (skipNodes) {
            skippedNodesCount++;
        }
        else {
            ProxyParseTreeListenerUtil.delegateEnterRule(ctx, listeners, errors);
        }
    }

    @Override
    public void exitEveryRule(ParserRuleContext ctx) {
        if (skipNodes) {
            if (skippedNodesCount == 0) {
                // back in the node where skipping started
                skipNodes = false;
            }
            else {
                // going up in a tree, means decreasing a number of skipped nodes
                skippedNodesCount--;
            }
        }
        else {
            ProxyParseTreeListenerUtil.delegateExitRule(ctx, listeners, errors);
        }
    }

    @Override
    public void visitErrorNode(ErrorNode node) {
        ProxyParseTreeListenerUtil.visitErrorNode(node, listeners, errors);
    }

    @Override
    public void visitTerminal(TerminalNode node) {
        ProxyParseTreeListenerUtil.visitTerminal(node, listeners, errors);
    }

    @Override
    public void enterRoutineBody(MySqlParser.RoutineBodyContext ctx) {
        skipNodes = true;
    }

}