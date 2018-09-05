// Copyright 2018 Google Inc. All Rights Reserved.
package com.google.monitoring.runtime.cpu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(name = "JvmProfiler", value = "/debug/pprof/profile")
public class JvmProfiler extends HttpServlet {
  private static final JvmProfiler p = new JvmProfiler();
  private final Object lock = new Object();
  private boolean started = false;

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String param = req.getParameter("seconds");
      int seconds = param == null ? 10 : Integer.parseInt(param);
      collectCpuProfile(seconds, resp.getOutputStream());
      resp.setContentType("application/octet-stream");
      resp.setHeader("Content-disposition", "attachment; filename=profile.pb.gz");
    } catch (IllegalStateException e) {
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (NumberFormatException e) {
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Cannot parse seconds param as a number");
    }
  }

  private static void collectCpuProfile(int seconds, OutputStream os) throws IOException {
    long tag = p.startCpuProfiling(seconds);
    sleepNoInterrupt(1000 * seconds);
    p.stopCpuProfiling(tag, os);
  }

  /** Starts the CPU profiler. */
  private long startCpuProfiling(int seconds) {
    synchronized (lock) {
      if (started) {
        throw new IllegalStateException("CPU profiling is already in use");
      }
      long tag = startProfilingId0(1 /* PROCESS_CPU */, 100 /* 1/sec */, seconds /* sec */, false);
      started = true;
      return tag;
    }
  }

  /** Stops the CPU profiler and saves the profile proto to the specified stream. */
  private void stopCpuProfiling(long tag, OutputStream os) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    synchronized (lock) {
      if (!started) {
        throw new IllegalStateException("CPU profiler hasn't been started");
      }
      stopProfiling0(tag);
      printProfilerResults0(0, 5 /* PROTO */, baos);
      started = false;
    }
    if (baos.size() == 0) {
      throw new IllegalStateException("No CPU profile samples captured -- app is idle?");
    }
    os.write(baos.toByteArray());
  }

  /** Sleeps for the specified duration in milliseconds ignoring interruptions. */
  private static void sleepNoInterrupt(long waitMillis) {
    long startMillis = System.currentTimeMillis();
    while (waitMillis > 0) {
      try {
        Thread.sleep(waitMillis);
      } catch (InterruptedException e) {
        // Fall-through to keep waiting.
      }
      long nowMillis = System.currentTimeMillis();
      waitMillis -= nowMillis - startMillis;
      startMillis = nowMillis;
    }
  }

  // These are experimental APIs. Use at your own risk, they may stop working or change any time.
  private native long startProfilingId0(int t, int f, int d, boolean reserved);
  private native void stopProfiling0(long tag);
  private native void printProfilerResults0(long bits, int f, OutputStream os);
}
