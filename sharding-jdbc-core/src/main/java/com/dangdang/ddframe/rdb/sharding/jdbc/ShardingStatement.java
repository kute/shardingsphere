/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.jdbc;

import com.dangdang.ddframe.rdb.sharding.executor.StatementExecutor;
import com.dangdang.ddframe.rdb.sharding.executor.wrapper.StatementExecutorWrapper;
import com.dangdang.ddframe.rdb.sharding.jdbc.adapter.AbstractStatementAdapter;
import com.dangdang.ddframe.rdb.sharding.merger.ResultSetFactory;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.GeneratedKey;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.statement.insert.InsertStatement;
import com.dangdang.ddframe.rdb.sharding.routing.SQLExecutionUnit;
import com.dangdang.ddframe.rdb.sharding.routing.SQLRouteResult;
import com.dangdang.ddframe.rdb.sharding.routing.StatementRoutingEngine;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * 支持分片的静态语句对象.
 * 
 * @author gaohongtao
 * @author caohao
 */
public class ShardingStatement extends AbstractStatementAdapter {
    
    private static final Function<BackendStatementWrapper, Statement> TRANSFORM_FUNCTION = new Function<BackendStatementWrapper, Statement>() {
        
        @Override
        public Statement apply(final BackendStatementWrapper input) {
            return input.getStatement();
        }
    };
    
    @Getter(AccessLevel.PROTECTED)
    private final ShardingConnection shardingConnection;
    
    @Getter(AccessLevel.PROTECTED)
    private boolean returnGeneratedKeys;
    
    @Getter
    private final int resultSetType;
    
    @Getter
    private final int resultSetConcurrency;
    
    @Getter
    private final int resultSetHoldability;
    
    private final Deque<List<BackendStatementWrapper>> cachedRoutedStatements = Lists.newLinkedList();
    
    @Getter(AccessLevel.PROTECTED)
    @Setter(AccessLevel.PROTECTED)
    private SQLRouteResult sqlRouteResult;
    
    @Setter(AccessLevel.PROTECTED)
    private ResultSet currentResultSet;
    
    ShardingStatement(final ShardingConnection shardingConnection) {
        this(shardingConnection, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    ShardingStatement(final ShardingConnection shardingConnection, final int resultSetType, final int resultSetConcurrency) {
        this(shardingConnection, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingStatement(final ShardingConnection shardingConnection, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
        super(Statement.class);
        this.shardingConnection = shardingConnection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
        cachedRoutedStatements.add(new LinkedList<BackendStatementWrapper>());
        cachedRoutedStatements.add(new LinkedList<BackendStatementWrapper>());
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return shardingConnection;
    }
    
    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        ResultSet rs;
        try {
            rs = ResultSetFactory.getResultSet(generateExecutor(sql).executeQuery(), sqlRouteResult.getSqlStatement());
        } finally {
            clearRouteContext();
        }
        setCurrentResultSet(rs);
        return rs;
    }
    
    @Override
    public int executeUpdate(final String sql) throws SQLException {
        try {
            return generateExecutor(sql).executeUpdate();
        } finally {
            clearRouteContext();
        }
    }
    
    @Override
    public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
        if (Statement.RETURN_GENERATED_KEYS == autoGeneratedKeys) {
            markReturnGeneratedKeys();
        }
        try {
            return generateExecutor(sql).executeUpdate(autoGeneratedKeys);
        } finally {
            clearRouteContext();
        }
    }
    
    @Override
    public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
        markReturnGeneratedKeys();
        try {
            return generateExecutor(sql).executeUpdate(columnIndexes);
        } finally {
            clearRouteContext();
        }
    }
    
    @Override
    public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
        markReturnGeneratedKeys();
        try {
            return generateExecutor(sql).executeUpdate(columnNames);
        } finally {
            clearRouteContext();
        }
    }
    
    @Override
    public boolean execute(final String sql) throws SQLException {
        try {
            return generateExecutor(sql).execute();
        } finally {
            clearRouteContext();
        }
    }
    
    @Override
    public boolean execute(final String sql, final int autoGeneratedKeys) throws SQLException {
        if (Statement.RETURN_GENERATED_KEYS == autoGeneratedKeys) {
            markReturnGeneratedKeys();
        }
        try {
            return generateExecutor(sql).execute(autoGeneratedKeys);
        } finally {
            clearRouteContext();
        }
    }
    
    @Override
    public boolean execute(final String sql, final int[] columnIndexes) throws SQLException {
        markReturnGeneratedKeys();
        try {
            return generateExecutor(sql).execute(columnIndexes);
        } finally {
            clearRouteContext();
        }
    }
    
    @Override
    public boolean execute(final String sql, final String[] columnNames) throws SQLException {
        markReturnGeneratedKeys();
        try {
            return generateExecutor(sql).execute(columnNames);
        } finally {
            clearRouteContext();
        }
    }
    
    protected final void markReturnGeneratedKeys() {
        returnGeneratedKeys = true;
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        Optional<GeneratedKey> generatedKey = getGeneratedKey();
        if (generatedKey.isPresent() && returnGeneratedKeys) {
            return new GeneratedKeysResultSet(sqlRouteResult.getGeneratedKeys().iterator(), generatedKey.get().getColumn(), this);
        }
        return new GeneratedKeysResultSet();
    }
    
    protected final Optional<GeneratedKey> getGeneratedKey() {
        if (null != sqlRouteResult && sqlRouteResult.getSqlStatement() instanceof InsertStatement) {
            return Optional.fromNullable(((InsertStatement) sqlRouteResult.getSqlStatement()).getGeneratedKey());
        }
        return Optional.absent();
    }
    
    protected void clearRouteContext() throws SQLException {
        setCurrentResultSet(null);
        List<BackendStatementWrapper> firstList = cachedRoutedStatements.pollFirst();
        cachedRoutedStatements.getFirst().addAll(firstList);
        firstList.clear();
        cachedRoutedStatements.addLast(firstList);
    }
    
    private StatementExecutor generateExecutor(final String sql) throws SQLException {
        StatementExecutor result = new StatementExecutor(shardingConnection.getShardingContext().getExecutorEngine());
        sqlRouteResult = new StatementRoutingEngine(shardingConnection.getShardingContext()).route(sql);
        for (SQLExecutionUnit each : sqlRouteResult.getExecutionUnits()) {
            Statement statement = getStatement(shardingConnection.getConnection(each.getDataSource(), sqlRouteResult.getSqlStatement().getType()), each.getSql());
            replayMethodsInvocation(statement);
            result.addStatement(new StatementExecutorWrapper(statement, each));
        }
        return result;
    }
    
    protected Statement getStatement(final Connection connection, final String sql) throws SQLException {
        BackendStatementWrapper statement = null;
        for (Iterator<BackendStatementWrapper> iterator = cachedRoutedStatements.getFirst().iterator(); iterator.hasNext();) {
            BackendStatementWrapper each = iterator.next();
            if (each.isBelongTo(connection, sql)) {
                statement = each;
                iterator.remove();
            }
        }
        if (null == statement) {
            statement = generateStatement(connection, sql);
        }
        cachedRoutedStatements.getLast().add(statement);
        return statement.getStatement();
    }
    
    protected BackendStatementWrapper generateStatement(final Connection connection, final String sql) throws SQLException {
        return new BackendStatementWrapper(connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability));
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        if (null != currentResultSet) {
            return currentResultSet;
        }
        List<ResultSet> resultSets = new ArrayList<>(getRoutedStatements().size());
        if (getRoutedStatements().size() == 1) {
            currentResultSet = getRoutedStatements().iterator().next().getResultSet();
            return currentResultSet;
        }
        for (Statement each : getRoutedStatements()) {
            resultSets.add(each.getResultSet());
        }
        currentResultSet = ResultSetFactory.getResultSet(resultSets, sqlRouteResult.getSqlStatement());
        return currentResultSet;
    }
    
    @Override
    protected void clearRouteStatements() {
        cachedRoutedStatements.getFirst().clear();
        cachedRoutedStatements.getLast().clear();
    }
    
    @Override
    public Collection<? extends Statement> getRoutedStatements() {
        return  Lists.newArrayList(Iterators.concat(Iterators.transform(cachedRoutedStatements.getFirst().iterator(), TRANSFORM_FUNCTION), 
                Iterators.transform(cachedRoutedStatements.getLast().iterator(), TRANSFORM_FUNCTION)));
    }
}
