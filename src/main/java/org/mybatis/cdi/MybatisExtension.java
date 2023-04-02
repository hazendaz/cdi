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

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedMember;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.InjectionTarget;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessInjectionTarget;
import jakarta.enterprise.inject.spi.ProcessProducer;
import jakarta.enterprise.inject.spi.WithAnnotations;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;

/**
 * MyBatis CDI extension.
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class MybatisExtension implements Extension {

  private static final Logger LOGGER = LoggerFactory.getLogger(MybatisExtension.class.getName());

  private final Set<BeanKey> sessionProducers = new HashSet<>();

  private final Set<Type> mapperTypes = new HashSet<>();

  private final Set<InjectionPoint> injectionPoints = new HashSet<>();

  /**
   * Collect types of all mappers annotated with Mapper.
   *
   * @param <T>
   *          the generic type
   * @param pat
   *          the pat
   */
  @SuppressWarnings("UnusedDeclaration")
  protected <T> void processAnnotatedType(
      @Observes @WithAnnotations({ Mapper.class }) final ProcessAnnotatedType<T> pat) {
    final AnnotatedType<T> at = pat.getAnnotatedType();
    if (at.isAnnotationPresent(Mapper.class)) {
      LOGGER.info("MyBatis CDI Module - Found class with @Mapper-annotation: {}", at.getJavaClass().getSimpleName());
      this.mapperTypes.add(at.getBaseType());
    }
  }

  /**
   * Collect all SqlSessionFactory producers annotated with SessionFactoryProvider.
   *
   * @param <T>
   *          the generic type
   * @param <X>
   *          the generic type
   * @param pp
   *          the pp
   */
  @SuppressWarnings("UnusedDeclaration")
  protected <T, X> void processProducer(@Observes final ProcessProducer<T, X> pp) {
    final AnnotatedMember<T> am = pp.getAnnotatedMember();
    final boolean isAnnotated = am.isAnnotationPresent(SessionFactoryProvider.class);
    final boolean isSqlSessionFactory = am.getBaseType().equals(SqlSessionFactory.class);
    final String logData = String.join(".", am.getJavaMember().getDeclaringClass().getSimpleName(),
        am.getJavaMember().getName());
    if (isAnnotated) {
      if (isSqlSessionFactory) {
        LOGGER.info("MyBatis CDI Module - SqlSessionFactory producer {}", logData);
        this.sessionProducers.add(new BeanKey((Class<Type>) (Type) SqlSession.class, am.getAnnotations()));
      } else {
        LOGGER.error("MyBatis CDI Module - Invalid return type (Must be SqlSessionFactory): {}", logData);
        pp.addDefinitionError(new MybatisCdiConfigurationException(
            String.format("SessionFactoryProvider producers must return SqlSessionFactory (%s)", logData)));
      }
    } else if (isSqlSessionFactory) {
      LOGGER.warn(
          "MyBatis CDI Module - Ignored SqlSessionFactory producer because it is not annotated with @SessionFactoryProvider: {}",
          logData);
    }
  }

  /**
   * Collect all targets to match Mappers and Session providers dependency.
   *
   * @param <X>
   *          the generic type
   * @param event
   *          the event
   */
  protected <X> void processInjectionTarget(@Observes ProcessInjectionTarget<X> event) {
    final InjectionTarget<X> it = event.getInjectionTarget();
    this.injectionPoints.addAll(it.getInjectionPoints());
  }

  /**
   * Register all mybatis injectable beans.
   *
   * @param abd
   *          the abd
   */
  protected void afterBeanDiscovery(@Observes final AfterBeanDiscovery abd) {
    LOGGER.info("MyBatis CDI Module - Activated");

    Set<BeanKey> mappers = new HashSet<>();
    Set<BeanKey> sessionTargets = new HashSet<>();

    for (InjectionPoint ip : injectionPoints) {
      if (this.mapperTypes.contains(ip.getAnnotated().getBaseType())) {
        LOGGER.info("MyBatis CDI Module - Found a bean, which needs a Mapper {}", ip.getAnnotated().getBaseType());
        mappers.add(new BeanKey((Class<Type>) ip.getAnnotated().getBaseType(), ip.getAnnotated().getAnnotations()));
      } else if (SqlSession.class.equals(ip.getAnnotated().getBaseType())) {
        sessionTargets
            .add(new BeanKey((Class<Type>) ip.getAnnotated().getBaseType(), ip.getAnnotated().getAnnotations()));
      }
    }
    this.injectionPoints.clear();

    // Mappers -----------------------------------------------------------------
    for (BeanKey key : mappers) {
      LOGGER.info("MyBatis CDI Module - Managed Mapper dependency: {}, {}", key.getKey(), key.type.getName());
      abd.addBean(key.createBean());
    }
    this.mapperTypes.clear();

    // SqlSessionFactories -----------------------------------------------------
    for (BeanKey key : this.sessionProducers) {
      LOGGER.info("MyBatis CDI Module - Managed SqlSession: {}, {}", key.getKey(), key.type.getName());
      abd.addBean(key.createBean());
      sessionTargets.remove(key);
    }
    this.sessionProducers.clear();

    // Unmanaged SqlSession targets --------------------------------------------
    for (BeanKey key : sessionTargets) {
      LOGGER.warn("MyBatis CDI Module - Unmanaged SqlSession: {}, {}", key.getKey(), key.type.getName());
    }

  }

  /**
   * Unique key for fully qualified Mappers and Sessions.
   */
  private static final class BeanKey implements Comparable<BeanKey> {

    private final String key;

    private final List<Annotation> qualifiers;

    private final Class<Type> type;

    private final String sqlSessionManagerName;

    public BeanKey(Class<Type> type, Set<Annotation> annotations) {
      this.type = type;
      this.qualifiers = sort(filterQualifiers(annotations));

      // Create key = type(.qualifier)*(.name)?
      final StringBuilder sb = new StringBuilder();
      String name = null;
      sb.append(type.getName());
      for (Annotation q : this.qualifiers) {
        if (q instanceof Named) {
          name = ((Named) q).value();
        } else {
          sb.append(".").append(q.annotationType().getSimpleName());
        }
      }
      if (name != null) {
        sb.append("_").append(name);
      }
      this.key = sb.toString();
      this.sqlSessionManagerName = name;
    }

    private Set<Annotation> filterQualifiers(Set<Annotation> annotations) {
      final Set<Annotation> set = new HashSet<>();
      for (Annotation a : annotations) {
        if (a.annotationType().isAnnotationPresent(Qualifier.class)) {
          set.add(a);
        }
      }
      return set;
    }

    private List<Annotation> sort(Set<Annotation> annotations) {
      final List<Annotation> list = new ArrayList<>(annotations);
      Collections.sort(list, (a, b) -> a.getClass().getName().compareTo(b.getClass().getName()));
      return list;
    }

    @Override
    public int compareTo(BeanKey o) {
      return this.key.compareTo(o.key);
    }

    @Override
    public int hashCode() {
      int hash = 3;
      return 43 * hash + (this.key != null ? this.key.hashCode() : 0);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null || this.getClass() != obj.getClass()) {
        return false;
      }
      final BeanKey other = (BeanKey) obj;
      return !(this.key == null ? other.key != null : !this.key.equals(other.key));
    }

    public MyBatisBean createBean() {
      return new MyBatisBean(this.key, this.type, new HashSet<>(this.qualifiers), this.sqlSessionManagerName);
    }

    public String getKey() {
      return this.key;
    }

    @Override
    public String toString() {
      return this.key;
    }

  }

}
