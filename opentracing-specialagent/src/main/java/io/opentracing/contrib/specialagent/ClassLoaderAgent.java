/* Copyright 2018 The OpenTracing Authors
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

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

import io.opentracing.contrib.specialagent.Agent.AllPluginsClassLoader;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Identified.Narrowable;
import net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.utility.JavaModule;

/**
 * The {@code ClassLoaderAgent} instruments the {@code ClassLoader} class to
 * achieve direct loading of bytecode into target class loaders, which is
 * necessary for the SpecialAgent to be able to load instrumentation classes
 * into class loaders.
 *
 * @author Seva Safris
 */
public class ClassLoaderAgent {
  public static void premain(final String agentArgs, final Instrumentation inst) {
    final Narrowable builder = new AgentBuilder.Default()
      .ignore(none())
      .disableClassFormatChanges()
    .with(new DebugListener())
      .with(RedefinitionStrategy.RETRANSFORMATION)
      .with(InitializationStrategy.NoOp.INSTANCE)
      .with(TypeStrategy.Default.REDEFINE)
      .type(isSubTypeOf(ClassLoader.class).and(not(is(PluginClassLoader.class)).and(not(is(AllPluginsClassLoader.class)))));

    builder
      .transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(Advice.to(FindClass.class).on(named("findClass").and(returns(Class.class).and(takesArguments(String.class)))));
        }})
      .installOn(inst);

    builder
      .transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(Advice.to(FindResource.class).on(named("findResource").and(returns(URL.class).and(takesArguments(String.class)))));
        }})
      .installOn(inst);

    builder
      .transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(Advice.to(FindResources.class).on(named("findResources").and(returns(Enumeration.class).and(takesArguments(String.class)))));
        }})
      .installOn(inst);
  }

  public static final ThreadLocal<AtomicBoolean> findClassMutex = new ThreadLocal<AtomicBoolean>() {
    @Override
    protected AtomicBoolean initialValue() {
      return new AtomicBoolean(false);
    }
  };

  public static class FindClass {
    @Advice.OnMethodExit(onThrowable = ClassNotFoundException.class)
    public static void exit(final @Advice.This ClassLoader thiz, final @Advice.Argument(0) String arg, @Advice.Return(readOnly=false, typing=Typing.DYNAMIC) Class<?> returned, @Advice.Thrown(readOnly = false, typing = Typing.DYNAMIC) ClassNotFoundException thrown) {
      if (findClassMutex.get().get() || returned != null)
        return;

      findClassMutex.get().set(true);
      try {
        final byte[] bytecode = Agent.findClass(thiz, arg);
        if (bytecode == null)
          return;

        System.err.println("<<<<<<<< defineClass(\"" + arg + "\")");
        final Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
        returned = (Class<?>)defineClass.invoke(thiz, arg, bytecode, 0, bytecode.length, null);
        thrown = null;
      }
      catch (final Throwable t) {
        System.err.println("<><><><> ClassLoaderAgent.FindClass#exit: " + t);
        t.printStackTrace();
      }
      finally {
        findClassMutex.get().set(false);
      }
    }
  }

  public static final ThreadLocal<AtomicBoolean> findResourceMutex = new ThreadLocal<AtomicBoolean>() {
    @Override
    protected AtomicBoolean initialValue() {
      return new AtomicBoolean(false);
    }
  };

  public static class FindResource {
    @Advice.OnMethodExit
    public static void exit(final @Advice.This ClassLoader thiz, final @Advice.Argument(0) String arg, @Advice.Return(readOnly=false, typing=Typing.DYNAMIC) URL returned) {
      if (findResourceMutex.get().get() || returned != null)
        return;

      findResourceMutex.get().set(true);
      try {
        final URL resource = Agent.findResource(thiz, arg);
        if (resource != null)
          returned = resource;
      }
      catch (final Throwable t) {
        System.err.println("<><><><> ClassLoaderAgent.FindResource#exit: " + t);
        t.printStackTrace();
      }
      finally {
        findResourceMutex.get().set(false);
      }
    }
  }

  public static final ThreadLocal<AtomicBoolean> findResourcesMutex = new ThreadLocal<AtomicBoolean>() {
    @Override
    protected AtomicBoolean initialValue() {
      return new AtomicBoolean(false);
    }
  };

  public static class FindResources {
    @Advice.OnMethodExit
    public static void exit(final @Advice.This ClassLoader thiz, final @Advice.Argument(0) String arg, @Advice.Return(readOnly=false, typing=Typing.DYNAMIC) Enumeration<URL> returned) {
      if (findResourcesMutex.get().get())
        return;

      findResourcesMutex.get().set(true);
      try {
        final Enumeration<URL> resources = Agent.findResources(thiz, arg);
        if (resources == null)
          return;

        returned = returned == null ? resources : new sun.misc.CompoundEnumeration<URL>(new Enumeration[] {returned, resources});
      }
      catch (final Throwable t) {
        System.err.println("<><><><> ClassLoaderAgent.FindResources#exit: " + t);
        t.printStackTrace();
      }
      finally {
        findResourcesMutex.get().set(false);
      }
    }
  }
}