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

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.PassivationCapable;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionManager;

/**
 * Internal CDI metadata for a mapper bean.
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class MyBatisBean implements Bean<Object>, Serializable, PassivationCapable {

  private static final long serialVersionUID = 1L;

  protected final Class<Type> type;

  protected final Set<Annotation> qualifiers;

  protected final String sqlSessionFactoryName;

  protected final String id;

  /**
   * Instantiates a new my batis bean.
   *
   * @param id
   *          the id
   * @param type
   *          the type
   * @param qualifiers
   *          the qualifiers
   * @param sqlSessionFactoryName
   *          the sql session factory name
   */
  public MyBatisBean(String id, Class<Type> type, Set<Annotation> qualifiers, String sqlSessionFactoryName) {
    this.id = id;
    this.type = type;
    this.sqlSessionFactoryName = sqlSessionFactoryName;
    if (qualifiers == null || qualifiers.isEmpty()) {
      this.qualifiers = new HashSet<>();
      this.qualifiers.add(new CDIUtils.SerializableDefaultAnnotationLiteral());
      this.qualifiers.add(new CDIUtils.SerializableAnyAnnotationLiteral());
    } else {
      this.qualifiers = qualifiers;
    }
  }

  @Override
  public Set<Type> getTypes() {
    Set<Type> types = new HashSet<>();
    types.add(this.type);
    return types;
  }

  @Override
  public Set<Annotation> getQualifiers() {
    return this.qualifiers;
  }

  @Override
  public Class<Dependent> getScope() {
    return Dependent.class;
  }

  @Override
  public String getName() {
    return null;
  }

  @Override
  public Set<Class<? extends Annotation>> getStereotypes() {
    return Collections.emptySet();
  }

  @Override
  public Class<Type> getBeanClass() {
    return this.type;
  }

  @Override
  public boolean isAlternative() {
    return false;
  }

  @Override
  public boolean isNullable() {
    return false;
  }

  @Override
  public Set<InjectionPoint> getInjectionPoints() {
    return Collections.emptySet();
  }

  @Override
  public Object create(CreationalContext<Object> creationalContext) {
    if (SqlSession.class.equals(this.type)) {
      return findSqlSessionManager(creationalContext);
    }
    ErrorContext.instance().reset();
    return Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(), new Class[] { this.type },
        new SerializableMapperProxy<Object>(this, creationalContext));
  }

  @Override
  public void destroy(Object instance, CreationalContext<Object> creationalContext) {
    creationalContext.release();
  }

  private <T> SqlSessionManager findSqlSessionManager(CreationalContext<T> creationalContext) {
    SqlSessionFactory factory = CDIUtils.findSqlSessionFactory(this.sqlSessionFactoryName, this.qualifiers,
        creationalContext);
    return CDIUtils.getRegistry(creationalContext).getManager(factory);
  }

  @Override
  public String getId() {
    return this.id;
  }

}
