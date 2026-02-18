/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.security.authentication.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

public class TestSubjectUtil {

  // "1.8"->8, "9"->9, "10"->10
  private static final int JAVA_SPEC_VER = Math.max(8, Integer.parseInt(
      System.getProperty("java.specification.version").split("\\.")[0]));

  @Test
  public void testHasCallAs() {
    assertEquals(JAVA_SPEC_VER > 17, SubjectUtil.HAS_CALL_AS);
  }

  @Test
  public void testDoAsPrivilegedActionExceptionPropagation() {
    // in Java 12 onwards, always throw the original exception thrown by action;
    // in lower Java versions, throw a PrivilegedActionException that wraps the
    // original exception when action throws a checked exception
    Throwable e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedAction<Object>() {
        @Override
        public Object run() {
          RuntimeException innerE = new RuntimeException("Inner Dummy RuntimeException");
          throw SubjectUtil.sneakyThrow(new IOException("Dummy IOException", innerE));
        }
      });
    } catch (Exception caught) {
      e = caught;
    }
    if (JAVA_SPEC_VER > 11) {
      assertTrue(e instanceof IOException);
      assertEquals("Dummy IOException", e.getMessage());
      assertTrue(e.getCause() instanceof RuntimeException);
      assertEquals("Inner Dummy RuntimeException", e.getCause().getMessage());
      assertNull(e.getCause().getCause());
    } else {
      assertTrue(e instanceof PrivilegedActionException);
      assertNull(e.getMessage());
      assertTrue(e.getCause() instanceof IOException);
      assertEquals("Dummy IOException", e.getCause().getMessage());
      assertTrue(e.getCause().getCause() instanceof RuntimeException);
      assertEquals("Inner Dummy RuntimeException", e.getCause().getCause().getMessage());
      assertNull(e.getCause().getCause().getCause());
    }

    // same as above case because PrivilegedActionException is a checked exception
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedAction<Object>() {
        @Override
        public Object run() {
          throw SubjectUtil.sneakyThrow(new PrivilegedActionException(null));
        }
      });
    } catch (Exception caught) {
      e = caught;
    }
    if (JAVA_SPEC_VER > 11) {
      assertTrue(e instanceof PrivilegedActionException);
      assertNull(e.getMessage());
      assertNull(e.getCause());
    } else {
      assertTrue(e instanceof PrivilegedActionException);
      assertNull(e.getMessage());
      assertTrue(e.getCause() instanceof PrivilegedActionException);
      assertNull(e.getCause().getMessage());
      assertNull(e.getCause().getCause());
    }

    // throw a PrivilegedActionException that wraps the original exception when action throws
    // a runtime exception
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedAction<Object>() {
        @Override
        public Object run() {
          throw new RuntimeException("Dummy RuntimeException");
        }
      });
    } catch (RuntimeException caught) {
      e = caught;
    }
    assertTrue(e instanceof RuntimeException);
    assertEquals("Dummy RuntimeException", e.getMessage());
    assertNull(e.getCause());

    // same as above case because CompletionException is a runtime exception
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedAction<Object>() {
        @Override
        public Object run() {
          throw new CompletionException("Dummy CompletionException", null);
        }
      });
    } catch (CompletionException caught) {
      e = caught;
    }
    assertTrue(e instanceof CompletionException);
    assertEquals("Dummy CompletionException", e.getMessage());
    assertNull(e.getCause());

    // throw the original error when action throws an error
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedAction<Object>() {
        @Override
        public Object run() {
          throw new LinkageError("Dummy LinkageError");
        }
      });
    } catch (LinkageError caught) {
      e = caught;
    }
    assertTrue(e instanceof LinkageError);
    assertEquals("Dummy LinkageError", e.getMessage());
    assertNull(e.getCause());

    // throw NPE when action is NULL
    try {
      SubjectUtil.doAs(SubjectUtil.current(), (PrivilegedAction<Object>) null);
      throw new AssertionError("Expected NullPointerException");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  public void testDoAsPrivilegedExceptionActionExceptionPropagation()
      throws Exception {
    // throw PrivilegedActionException that wraps the original exception when action throws
    // a checked exception
    Throwable e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          RuntimeException innerE = new RuntimeException("Inner Dummy RuntimeException");
          throw new IOException("Dummy IOException", innerE);
        }
      });
    } catch (PrivilegedActionException caught) {
      e = caught;
    }
    assertTrue(e instanceof PrivilegedActionException);
    assertNull(e.getMessage());
    assertTrue(e.getCause() instanceof IOException);
    assertEquals("Dummy IOException", e.getCause().getMessage());
    assertTrue(e.getCause().getCause() instanceof RuntimeException);
    assertEquals("Inner Dummy RuntimeException", e.getCause().getCause().getMessage());
    assertNull(e.getCause().getCause().getCause());

    // same as above because PrivilegedActionException is a checked exception
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          throw new PrivilegedActionException(null);
        }
      });
    } catch (PrivilegedActionException caught) {
      e = caught;
    }
    assertTrue(e instanceof PrivilegedActionException);
    assertNull(e.getMessage());
    assertTrue(e.getCause() instanceof PrivilegedActionException);
    assertNull(e.getCause().getMessage());
    assertNull(e.getCause().getCause());

    // throw the original exception when action throw a runtime exception
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          throw new RuntimeException("Dummy RuntimeException");
        }
      });
    } catch (RuntimeException caught) {
      e = caught;
    }
    assertTrue(e instanceof RuntimeException);
    assertEquals("Dummy RuntimeException", e.getMessage());
    assertNull(e.getCause());

    // same as above case because CompletionException is a runtime exception
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          throw new CompletionException(null);
        }
      });
    } catch (CompletionException caught) {
      e = caught;
    }
    assertTrue(e instanceof CompletionException);
    assertNull(e.getMessage());
    assertNull(e.getCause());

    // throw the original error when action throw an error
    e = null;
    try {
      SubjectUtil.doAs(SubjectUtil.current(), new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          throw new LinkageError("Dummy LinkageError");
        }
      });
    } catch (LinkageError caught) {
      e = caught;
    }
    assertTrue(e instanceof LinkageError);
    assertEquals("Dummy LinkageError", e.getMessage());
    assertNull(e.getCause());

    // throw NPE when action is NULL
    try {
      SubjectUtil.doAs(SubjectUtil.current(), (PrivilegedExceptionAction<Object>) null);
      throw new AssertionError("Expected NullPointerException");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  public void testCallAsExceptionPropagation() {
    // always throw a CompletionException that wraps the original exception, when action throw
    // a checked or runtime exception
    Throwable e = null;
    try {
      SubjectUtil.callAs(SubjectUtil.current(), new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          RuntimeException innerE = new RuntimeException("Inner Dummy RuntimeException");
          throw new IOException("Dummy IOException", innerE);
        }
      });
    } catch (CompletionException caught) {
      e = caught;
    }
    assertTrue(e instanceof CompletionException);
    if (JAVA_SPEC_VER > 11) {
      assertEquals("java.io.IOException: Dummy IOException", e.getMessage());
      assertTrue(e.getCause() instanceof IOException);
      assertEquals("Dummy IOException", e.getCause().getMessage());
      assertTrue(e.getCause().getCause() instanceof RuntimeException);
      assertEquals("Inner Dummy RuntimeException", e.getCause().getCause().getMessage());
      assertNull(e.getCause().getCause().getCause());
    } else {
      assertEquals(
          "java.security.PrivilegedActionException: java.io.IOException: Dummy IOException",
          e.getMessage());
      assertTrue(e.getCause() instanceof PrivilegedActionException);
      assertNull(e.getCause().getMessage());
      assertTrue(e.getCause().getCause() instanceof IOException);
      assertEquals("Dummy IOException", e.getCause().getCause().getMessage());
      assertTrue(e.getCause().getCause().getCause() instanceof RuntimeException);
      assertEquals("Inner Dummy RuntimeException",
          e.getCause().getCause().getCause().getMessage());
      assertNull(e.getCause().getCause().getCause().getCause());
    }

    e = null;
    try {
      SubjectUtil.callAs(SubjectUtil.current(), new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          throw new PrivilegedActionException(null);
        }
      });
    } catch (CompletionException caught) {
      e = caught;
    }
    assertTrue(e instanceof CompletionException);
    if (JAVA_SPEC_VER > 11) {
      assertEquals("java.security.PrivilegedActionException", e.getMessage());
      assertTrue(e.getCause() instanceof PrivilegedActionException);
      assertNull(e.getCause().getMessage());
      assertNull(e.getCause().getCause());
    } else {
      assertEquals(
          "java.security.PrivilegedActionException: java.security.PrivilegedActionException",
          e.getMessage());
      assertTrue(e.getCause() instanceof PrivilegedActionException);
      assertNull(e.getCause().getMessage());
      assertTrue(e.getCause().getCause() instanceof PrivilegedActionException);
      assertNull(e.getCause().getCause().getMessage());
      assertNull(e.getCause().getCause().getCause());
    }

    e = null;
    try {
      SubjectUtil.callAs(SubjectUtil.current(), new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          throw new RuntimeException("Dummy RuntimeException");
        }
      });
    } catch (CompletionException caught) {
      e = caught;
    }
    assertTrue(e instanceof CompletionException);
    assertEquals("java.lang.RuntimeException: Dummy RuntimeException", e.getMessage());
    assertTrue(e.getCause() instanceof RuntimeException);
    assertEquals("Dummy RuntimeException", e.getCause().getMessage());
    assertNull(e.getCause().getCause());

    e = null;
    try {
      SubjectUtil.callAs(SubjectUtil.current(), new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          throw new CompletionException(null);
        }
      });
    } catch (CompletionException caught) {
      e = caught;
    }
    assertTrue(e instanceof CompletionException);
    assertEquals("java.util.concurrent.CompletionException", e.getMessage());
    assertTrue(e.getCause() instanceof CompletionException);
    assertNull(e.getCause().getMessage());

    // throw original error when action throw an error
    e = null;
    try {
      SubjectUtil.callAs(SubjectUtil.current(), new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          throw new LinkageError("Dummy LinkageError");
        }
      });
    } catch (LinkageError caught) {
      e = caught;
    }
    assertTrue(e instanceof LinkageError);
    assertEquals("Dummy LinkageError", e.getMessage());
    assertNull(e.getCause());

    // throw NPE when action is NULL
    try {
      SubjectUtil.callAs(SubjectUtil.current(), null);
      throw new AssertionError("Expected NullPointerException");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  public void testSneakyThrow() {
    try {
      throwCheckedException();
      throw new AssertionError("Expected IOException");
    } catch (Exception e) {
      assertTrue(e instanceof IOException);
      assertEquals("Dummy IOException", e.getMessage());
    }
  }

  // A method that throw a checked exception, but has no exception declaration in signature
  private void throwCheckedException() {
    throw SubjectUtil.sneakyThrow(new IOException("Dummy IOException"));
  }
}
