/* Copyright 2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentracing.contrib.specialagent;

import static net.bytebuddy.matcher.ElementMatchers.*;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Identified.Narrowable;
import net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.utility.JavaModule;

public class BootstrapAgent {
  public static final CachedClassFileLocator bootstrapLocator;

  static {
    try {
      bootstrapLocator = new CachedClassFileLocator(ClassFileLocator.ForClassLoader.ofSystemLoader(),
        // BootstrapAgent @Advice classes
        FindBootstrapResource.class, FindBootstrapResources.class,
        // ClassLoaderAgent @Advice classes (only necessary for ClassLoaderAgentTest)
        ClassLoaderAgent.FindClass.class, ClassLoaderAgent.FindResource.class, ClassLoaderAgent.FindResources.class,
        // SpecialAgentAgent @Advice classes (only necessary for ClassLoaderAgentTest)
        SpecialAgentAgent.FindClass.class, SpecialAgentAgent.FindResource.class, SpecialAgentAgent.FindResources.class);
    }
    catch (final IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static JarFile jarFile;

  public static void premain(final Instrumentation inst, final JarFile jarFile) {
    BootstrapAgent.jarFile = jarFile;
    final AgentBuilder builder = new AgentBuilder.Default()
      .ignore(none())
//    .with(new DebugListener())
      .with(RedefinitionStrategy.RETRANSFORMATION)
      .with(InitializationStrategy.NoOp.INSTANCE)
      .with(TypeStrategy.Default.REDEFINE);

    final Narrowable j8 = builder.type(is(ClassLoader.class));
    j8.transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(Advice.to(FindBootstrapResource.class, bootstrapLocator).on(isStatic().and(named("getBootstrapResource").and(returns(URL.class).and(takesArguments(String.class))))));
        }})
      .installOn(inst);

    j8.transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(Advice.to(FindBootstrapResources.class, bootstrapLocator).on(isStatic().and(named("getBootstrapResources").and(returns(Enumeration.class).and(takesArguments(String.class))))));
        }})
      .installOn(inst);

    final Narrowable j9 = builder.type(named("jdk.internal.loader.BootLoader"));
    j9.transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(Advice.to(FindBootstrapResource.class, bootstrapLocator).on(isStatic().and(named("findResource").and(returns(URL.class).and(takesArguments(String.class))))));
        }})
      .installOn(inst);

    j9.transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(Advice.to(FindBootstrapResources.class, bootstrapLocator).on(isStatic().and(named("findResources").and(returns(Enumeration.class).and(takesArguments(String.class))))));
        }})
      .installOn(inst);
  }

  public static class Mutex extends ThreadLocal<Set<String>> {
    @Override
    protected Set<String> initialValue() {
      return new HashSet<>();
    }
  }

  public static URL findBootstrapResource(final String name) {
    if (jarFile == null)
      return null;

    final JarEntry entry = jarFile.getJarEntry(name);
    if (entry == null)
      return null;

    try {
      return new URL("jar:file:" + jarFile.getName() + "!/" + name);
    }
    catch (final MalformedURLException e) {
      throw new UnsupportedOperationException(e);
    }
  }

  public static Enumeration<URL> findBootstrapResources(final String name) {
    if (jarFile == null)
      return null;

    final List<URL> resources = new ArrayList<>();
    final JarEntry entry = jarFile.getJarEntry(name);
    if (entry == null)
      return null;

    try {
      resources.add(new URL("jar:file:" + jarFile.getName() + "!/" + name));
    }
    catch (final MalformedURLException e) {
      throw new UnsupportedOperationException(e);
    }

    return Collections.enumeration(resources);
  }

  public static class FindBootstrapResource {
    public static final Mutex mutex = new Mutex();

    @Advice.OnMethodExit
    public static void exit(final @Advice.Argument(0) String arg, @Advice.Return(readOnly=false, typing=Typing.DYNAMIC) URL returned) {
      if (returned != null || !mutex.get().add(arg))
        return;

      try {
        final URL resource = findBootstrapResource(arg);
        if (resource != null)
          returned = resource;
      }
      catch (final Throwable t) {
        System.err.println("<><><><> BootstrapClassLoaderAgent.FindBootstrapResource#exit: " + t);
        t.printStackTrace();
      }
      finally {
        mutex.get().remove(arg);
      }
    }
  }

  public static class FindBootstrapResources {
    public static final Mutex mutex = new Mutex();

    @Advice.OnMethodExit
    public static void exit(final @Advice.Argument(0) String arg, @Advice.Return(readOnly=false, typing=Typing.DYNAMIC) Enumeration<URL> returned) {
      if (!mutex.get().add(arg))
        return;

      try {
        final URL resource = findBootstrapResource(arg);
        if (resource == null)
          return;

        final Enumeration<URL> enumeration = new SingletonEnumeration<>(resource);
        returned = returned == null ? enumeration : new CompoundEnumeration<>(returned, enumeration);
      }
      catch (final Throwable t) {
        System.err.println("<><><><> BootstrapClassLoaderAgent.FindBootstrapResources#exit: " + t);
        t.printStackTrace();
      }
      finally {
        mutex.get().remove(arg);
      }
    }
  }
}