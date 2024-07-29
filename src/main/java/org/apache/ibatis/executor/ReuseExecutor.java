/*
 *    Copyright 2009-2023 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 */
public class ReuseExecutor extends BaseExecutor {

  private final Map<String, Statement> statementMap = new HashMap<>();

  public ReuseExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    // 创建StatementHandler 对象
    StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
    // 初始化 Statement
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    // 执行 StatementHandler  ，进行写操作
    return handler.update(stmt);
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,
      BoundSql boundSql) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    // 创建 StatementHandler
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler,
        boundSql);
    // 初始化 Statement
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    // 执行StatementHandler，进行读操作
    return handler.query(stmt, resultHandler);
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException {
    Configuration configuration = ms.getConfiguration();
    // 创建StatementHandler 对象
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    // 初始化 Statement
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    // 执行 StatementHandler ，进行读操作
    return handler.queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    // 关闭缓存的 Statement 对象们
    for (Statement stmt : statementMap.values()) {
      closeStatement(stmt);
    }
    statementMap.clear();
    // 返回空集合
    return Collections.emptyList();
  }

  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    // 获取boundSql
    BoundSql boundSql = handler.getBoundSql();
    // 获取执行的 sql
    String sql = boundSql.getSql();
    // 存在 Statement ?
    if (hasStatementFor(sql)) {
      // 获取 statement
      stmt = getStatement(sql);
      // 设置事务超时时间
      applyTransactionTimeout(stmt);
    } else {
      // 获得 connection 对象
      Connection connection = getConnection(statementLog);
      // 创建 statement 对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      // 添加到缓存中
      putStatement(sql, stmt);
    }
    // 设置 SQL 上的参数，例如 PrepareStatement 对象上的占位符
    handler.parameterize(stmt);
    return stmt;
  }

  /**
   * 判断对应的SQL 是否存在 Statement
   * @param sql
   * @return
   */
  private boolean hasStatementFor(String sql) {
    try {
      // 从 statementMap 根据 sql 获取 Statement
      Statement statement = statementMap.get(sql);
      // 判断当前 statement 是否不为空，并且未关闭
      return statement != null && !statement.getConnection().isClosed();
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * 返回缓存中的statement
   * @param s
   * @return
   */
  private Statement getStatement(String s) {
    return statementMap.get(s);
  }

  /**
   * 将 statement 放入缓存中
   * @param sql
   * @param stmt
   */
  private void putStatement(String sql, Statement stmt) {
    statementMap.put(sql, stmt);
  }

}
