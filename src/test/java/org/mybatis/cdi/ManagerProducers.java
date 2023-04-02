/*
 *    Copyright 2013-2022 the original author or authors.
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
package org.mybatis.cdi;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

public class ManagerProducers {

  private SqlSessionFactory createSessionManager(int n) throws IOException {
    SqlSessionFactory manager;
    try (Reader reader = Resources.getResourceAsReader("org/mybatis/cdi/mybatis-config_" + n + ".xml")) {
      manager = new SqlSessionFactoryBuilder().build(reader);
    }

    try (SqlSession session = manager.openSession();
        Reader reader = Resources.getResourceAsReader("org/mybatis/cdi/CreateDB_" + n + ".sql");) {
      Connection conn = session.getConnection();
      ScriptRunner runner = new ScriptRunner(conn);
      runner.setLogWriter(null);
      runner.runScript(reader);
    }

    return manager;
  }

  @ApplicationScoped
  @Named("manager1")
  @Produces
  @SessionFactoryProvider
  public SqlSessionFactory createManager1() throws IOException {
    return createSessionManager(1);
  }

  @ApplicationScoped
  @Named("manager2")
  @Produces
  @SessionFactoryProvider
  public SqlSessionFactory createManager2() throws IOException {
    return createSessionManager(2);
  }

  @ApplicationScoped
  @Produces
  @MySpecialManager
  @OtherQualifier
  @SessionFactoryProvider
  public SqlSessionFactory createManager3() throws IOException {
    return createSessionManager(3);
  }

  @ApplicationScoped
  @Produces
  @Named("unmanaged")
  public SqlSession createNonCdiManagedSession() throws IOException {
    return SqlSessionManager.newInstance(createSessionManager(4));
  }

  @ApplicationScoped
  @Named("unmanaged")
  public void closeNonCdiManagedSession(@Disposes SqlSession session) {
    session.close();
  }

}
